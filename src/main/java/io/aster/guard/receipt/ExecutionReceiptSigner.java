package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行收据签发（ADR 0040 §12 D1/D6）：组载荷 → RFC 8785 规范化 → Ed25519 签名（不二次哈希）→ base64url 无填充。
 *
 * <p>载荷字节用私有的默认 {@link ObjectMapper} 转树后交给 {@link JcsCanonicalizer}，不用 Quarkus 托管的全局
 * mapper：载荷只有字符串与整数，签名字节不应受全局序列化配置（命名策略、日期格式等）影响。
 *
 * <p>{@link #verify} 复现执行方的本地验签（供测试与自检）：外层 kid 与载荷 kid 一致、kid 在当前密钥列表中、
 * 签名有效、{@code v == 1 && outcome == ALLOW}，且 {@code issuedAt − 60s <= now < expiresAt}。
 * 它不检查 revoked——撤销由服务端 consume 判定（409 {@code kid_revoked}），执行方只看 JWKS。
 *
 * <p>{@link Startup}：ttl 非正等配置错误在启动时即失败，而不是推迟到首次签发。
 *
 * <p><b>运行中 active key 到期</b>：启动校验只保证启动时刻 {@code now < notAfter}。进程运行期间越过 notAfter 后，
 * {@code /keys} 已不再发布该 kid，再用它签出的收据执行方必然验签失败；因此签发前复核，到期即拒绝签名
 * （调用方按 {@code receipt_unavailable} 处理：直接 ALLOW 改判 DENY，审批保持 PENDING），并只记一次 ERROR，
 * 让「全部执行停摆」在服务端可见，而不是静默签出无法验证的收据。恢复方式是轮换 active-kid 后滚动重启。
 */
@Startup
@Singleton
public class ExecutionReceiptSigner {
  static final String OUTCOME_ALLOW = "ALLOW";
  static final int PAYLOAD_VERSION = 1;
  /** 执行方本地验签容忍的时钟偏差：now 可早于 issuedAt 至多 60 秒。 */
  static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);

  private static final Logger LOG = Logger.getLogger(ExecutionReceiptSigner.class);
  private static final ObjectMapper PAYLOAD_JSON = new ObjectMapper();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final ReceiptSigningKeys keys;
  private final Duration ttl;
  private final Clock clock;
  /** active key 运行中到期的 ERROR 只记一次，避免逐请求刷屏。 */
  private final AtomicBoolean expiryLogged = new AtomicBoolean();

  @Inject
  public ExecutionReceiptSigner(ReceiptSigningKeys keys,
                                @ConfigProperty(name = "aster.guard.receipt.ttl", defaultValue = "PT5M") Duration ttl,
                                Clock clock) {
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalStateException("aster.guard.receipt.ttl 必须为正: " + ttl);
    }
    this.keys = keys;
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * 用 active key 签发收据；{@code expiresAt = issuedAt + ttl}（排他）。{@code policyVersionId} 为 null 时载荷不含该键。
   * 外层 {@code revision} 取当前密钥配置修订号。
   *
   * @throws IllegalStateException active key 在 {@code issuedAt} 时已到 notAfter，或签名失败
   */
  public ExecutionReceipt sign(String decisionId, String tenantId, String actionHash, Long policyVersionId,
                               Instant issuedAt) {
    ReceiptSigningKeys.SigningKey key = requireUsableActive(issuedAt);
    ExecutionReceipt.Payload payload = new ExecutionReceipt.Payload(actionHash, decisionId,
        issuedAt.plus(ttl).toString(), issuedAt.toString(), key.kid(), OUTCOME_ALLOW, policyVersionId, tenantId,
        PAYLOAD_VERSION);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key.keyPair().getPrivate());
      signer.update(payloadBytes(payload));
      return new ExecutionReceipt(key.kid(), payload, keys.revision(), B64.encodeToString(signer.sign()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("执行收据签名失败: kid=" + key.kid(), e);
    }
  }

  /** 当前时刻能否签发（active key 未到 notAfter）；审批在写审批事件之前用它预判，避免不可签发时空写链。 */
  public boolean canSignNow() {
    try {
      requireUsableActive(clock.instant());
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /** active key 在 {@code at} 时仍可签发则返回之；否则首次记 ERROR 并抛异常。 */
  private ReceiptSigningKeys.SigningKey requireUsableActive(Instant at) {
    ReceiptSigningKeys.SigningKey key = keys.active();
    if (key.expiredAt(at)) {
      if (expiryLogged.compareAndSet(false, true)) {
        LOG.errorf("执行收据 active key 已到 notAfter，拒绝签发（全部 ALLOW 将按 receipt_unavailable 处理），"
            + "须轮换 active-kid 并滚动重启: kid=%s notAfter=%s", key.kid(), key.notAfter());
      }
      throw new IllegalStateException("active key 已到 notAfter: kid=" + key.kid());
    }
    return key;
  }

  /** 本地验签：签名、kid 与时间窗全部通过才为 true；任何格式问题返回 false 而不抛异常。 */
  public boolean verify(ExecutionReceipt receipt) {
    if (receipt == null || receipt.payload() == null || receipt.kid() == null || receipt.signature() == null) {
      return false;
    }
    ExecutionReceipt.Payload p = receipt.payload();
    if (!receipt.kid().equals(p.kid()) || !OUTCOME_ALLOW.equals(p.outcome())
        || p.v() == null || p.v() != PAYLOAD_VERSION) {
      return false;
    }
    Optional<ReceiptSigningKeys.SigningKey> key = keys.find(p.kid());
    return key.isPresent() && signatureValid(key.get(), p, receipt.signature()) && withinWindow(p);
  }

  private static boolean signatureValid(ReceiptSigningKeys.SigningKey key, ExecutionReceipt.Payload p,
                                        String signature) {
    try {
      byte[] sig = Base64.getUrlDecoder().decode(signature);
      if (sig.length != 64) {
        return false;
      }
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key.keyPair().getPublic());
      verifier.update(payloadBytes(p));
      return verifier.verify(sig);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      return false;
    }
  }

  private boolean withinWindow(ExecutionReceipt.Payload p) {
    if (p.issuedAt() == null || p.expiresAt() == null) {
      return false;
    }
    try {
      Instant issuedAt = Instant.parse(p.issuedAt());
      Instant expiresAt = Instant.parse(p.expiresAt());
      Instant now = clock.instant();
      return !now.isBefore(issuedAt.minus(ISSUED_AT_SKEW)) && now.isBefore(expiresAt);
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  /** 被签名的字节：载荷的 RFC 8785 规范化 UTF-8 字节串。 */
  static byte[] payloadBytes(ExecutionReceipt.Payload payload) {
    return JcsCanonicalizer.canonicalize(PAYLOAD_JSON.valueToTree(payload));
  }
}
