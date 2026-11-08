package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;

/**
 * 执行收据签发（ADR 0040 §12 D1/D6）：组载荷 → RFC 8785 规范化 → Ed25519 签名（不二次哈希）→ base64url 无填充。
 *
 * <p>载荷字节用私有的默认 {@link ObjectMapper} 转树后交给 {@link JcsCanonicalizer}，不用 Quarkus 托管的全局
 * mapper：载荷只有字符串与整数，签名字节不应受全局序列化配置（命名策略、日期格式等）影响。
 *
 * <p>{@link #verify} 复现执行方的本地验签（供测试与自检）：外层 kid 与载荷 kid 一致、kid 在当前密钥列表中、
 * 签名有效、{@code v == 1 && outcome == ALLOW}，且 {@code issuedAt − 60s <= now < expiresAt}。
 * 它不检查 revoked——撤销由服务端 consume 判定（409 {@code kid_revoked}），执行方只看 JWKS。
 */
@Singleton
public class ExecutionReceiptSigner {
  static final String OUTCOME_ALLOW = "ALLOW";
  static final int PAYLOAD_VERSION = 1;
  /** 执行方本地验签容忍的时钟偏差：now 可早于 issuedAt 至多 60 秒。 */
  static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);

  private static final ObjectMapper PAYLOAD_JSON = new ObjectMapper();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final ReceiptSigningKeys keys;
  private final Duration ttl;
  private final Clock clock;

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

  /** 用 active key 签发收据；{@code expiresAt = issuedAt + ttl}（排他）。 */
  public ExecutionReceipt sign(String decisionId, String tenantId, String actionHash, long policyVersionId,
                               Instant issuedAt) {
    ReceiptSigningKeys.SigningKey key = keys.active();
    ExecutionReceipt.Payload payload = new ExecutionReceipt.Payload(actionHash, decisionId,
        issuedAt.plus(ttl).toString(), issuedAt.toString(), key.kid(), OUTCOME_ALLOW, policyVersionId, tenantId,
        PAYLOAD_VERSION);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key.keyPair().getPrivate());
      signer.update(payloadBytes(payload));
      return new ExecutionReceipt(key.kid(), payload, B64.encodeToString(signer.sign()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("执行收据签名失败: kid=" + key.kid(), e);
    }
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
