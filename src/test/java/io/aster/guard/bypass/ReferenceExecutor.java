package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.receipt.JcsCanonicalizer;
import io.restassured.response.Response;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 参考执行方（ADR 0054 §2.1 固定顺序：验签 → consume → 副作用 → 回写），加上跳过本地检查的攻击者原语。
 *
 * <p>本地验签顺序逐字照 ADR 0040 §12 D6：{@code kid} 在 {@code GET /api/v1/guard/keys} 的 JWKS 中（未命中刷新一次）
 * → Ed25519 签名作用于 {@code payload} 的 jcs-sha256/v1 字节串 → {@code payload.tenantId / decisionId / outcome}
 * → 时间窗 {@code issuedAt − 60s <= now < expiresAt} → {@code payload.actionHash} 等于本地对请求体原文自算的值。
 * 任一失败即 {@link Refused}（{@code stage = "local"}），不 consume。JWKS 只取自服务端端点，不读测试配置。
 *
 * <p>服务端没有「提交收据」入口（ADR 0040 §12 D6 末段）：伪造、篡改签名的收据被拒的判定点就是执行方本地，
 * 生产中由 aster-guard SDK（#464）承担；这里的实现是它的参照物。
 */
public final class ReferenceExecutor {
  /** 执行方本地时间窗允许 now 早于 issuedAt 的最大偏差。 */
  static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);
  /** Ed25519 SubjectPublicKeyInfo 的固定 DER 前缀（RFC 8410），后接 32 字节原始公钥。 */
  private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

  /** 执行方的结论。 */
  public sealed interface Outcome permits Executed, Refused {}

  /** 已 consume 200 并执行（账本已写一条）。 */
  public record Executed(String decisionId, String attemptId) implements Outcome {}

  /**
   * 拒绝执行：{@code stage} 为 {@code local}（本地验签失败，未 consume）或 {@code consume}（服务端 consume 非 200）；
   * {@code code} 为本地原因，或 consume 响应：409 时为其 {@code error}，其余状态一律 {@code http_<status>}
   * （带 error 时为 {@code http_<status>:<error>}）——401 / 403 等不能冒充期望的 409 拒绝码。
   */
  public record Refused(String stage, String code) implements Outcome {}

  private final BypassFixture api;
  private final String apiKey;
  private final String tenantId;
  private final SideEffectLedger ledger;
  private final Clock clock;
  private final String executorId;
  private final AtomicInteger jwksFetches = new AtomicInteger();
  private volatile Response lastConsume;

  public ReferenceExecutor(BypassFixture api, String apiKey, String tenantId, SideEffectLedger ledger, Clock clock) {
    this.api = api;
    this.apiKey = apiKey;
    this.tenantId = tenantId;
    this.ledger = ledger;
    this.clock = clock;
    this.executorId = "bypass-ref@" + tenantId;
  }

  public String executorId() {
    return executorId;
  }

  /** 本执行方拉取 JWKS 的次数（未知 kid 时应恰好刷新一次）。 */
  public int jwksFetches() {
    return jwksFetches.get();
  }

  /** 最近一次 consume 的原始响应（本地拒绝时不更新）。 */
  public Response lastConsume() {
    return lastConsume;
  }

  /**
   * 诚实执行：本地验签全部通过才 consume；200 才写账本并回写 {@code executed}。
   *
   * @param decisionResponseJson 决策端点响应原文（执行方收到的就是它）
   * @param requestBody          执行方「将要执行」的请求体原文，{@code actionHash} 由它自算
   * @param effect               副作用描述（只用于账本）
   */
  public Outcome run(String decisionResponseJson, String requestBody, String effect) {
    JsonNode decision = ExecutionReceiptAssert.tree(decisionResponseJson);
    Optional<String> local = verifyLocally(decision, requestBody);
    if (local.isPresent()) {
      return new Refused("local", local.get());
    }
    JsonNode payload = decision.get("executionReceipt").get("payload");
    String decisionId = payload.get("decisionId").asText();
    String attemptId = UUID.randomUUID().toString();
    Response consumed = api.consume(apiKey, decisionId, payload.get("actionHash").asText(), attemptId, executorId);
    lastConsume = consumed;
    if (consumed.statusCode() != 200) {
      return new Refused("consume", errorOf(consumed));
    }
    ledger.record(decisionId, attemptId, effect);
    Response result = api.result(apiKey, decisionId, attemptId, executorId, "executed");
    if (result.statusCode() != 200) {
      throw new AssertionError("执行后结果回写失败: " + result.statusCode() + " " + result.asString());
    }
    return new Executed(decisionId, attemptId);
  }

  /**
   * 攻击者原语：跳过一切本地检查，直接以给定哈希 consume；<b>仅当 200</b> 时写账本（模拟「拿到 200 就执行」的
   * 恶意执行方），不回写结果。
   */
  public Outcome attackConsume(String decisionId, String actionHash, String effect) {
    String attemptId = UUID.randomUUID().toString();
    Response consumed = api.consume(apiKey, decisionId, actionHash, attemptId, "bypass-attacker@" + tenantId);
    lastConsume = consumed;
    if (consumed.statusCode() != 200) {
      return new Refused("consume", errorOf(consumed));
    }
    ledger.record(decisionId, attemptId, effect);
    return new Executed(decisionId, attemptId);
  }

  /** 返回第一个失败的本地检查；全部通过时为空。 */
  Optional<String> verifyLocally(JsonNode decision, String requestBody) {
    JsonNode receipt = decision.get("executionReceipt");
    if (receipt == null || !receipt.isObject() || !receipt.path("payload").isObject()) {
      return Optional.of("receipt_missing");
    }
    JsonNode payload = receipt.get("payload");
    String kid = receipt.path("kid").asText("");
    if (!kid.equals(payload.path("kid").asText(null))) {
      return Optional.of("bad_signature");
    }
    Optional<PublicKey> key = findKey(kid).or(() -> findKey(kid));
    if (key.isEmpty()) {
      return Optional.of("kid_unknown");
    }
    if (!signatureValid(key.get(), payload, receipt.path("signature").asText(""))) {
      return Optional.of("bad_signature");
    }
    if (!tenantId.equals(payload.path("tenantId").asText(null))) {
      return Optional.of("tenant_mismatch");
    }
    if (!payload.path("decisionId").asText("").equals(decision.path("decisionId").asText(null))) {
      return Optional.of("decision_mismatch");
    }
    if (!"ALLOW".equals(payload.path("outcome").asText(null)) || payload.path("v").asInt(-1) != 1) {
      return Optional.of("not_allow");
    }
    Optional<String> window = checkWindow(payload);
    if (window.isPresent()) {
      return window;
    }
    if (!ExecutionReceiptAssert.actionHashOf(requestBody).equals(payload.path("actionHash").asText(null))) {
      return Optional.of("action_mismatch");
    }
    return Optional.empty();
  }

  private Optional<String> checkWindow(JsonNode payload) {
    Instant issuedAt;
    Instant expiresAt;
    try {
      issuedAt = Instant.parse(payload.path("issuedAt").asText());
      expiresAt = Instant.parse(payload.path("expiresAt").asText());
    } catch (DateTimeParseException e) {
      return Optional.of("bad_signature");
    }
    Instant now = clock.instant();
    if (now.isBefore(issuedAt.minus(ISSUED_AT_SKEW))) {
      return Optional.of("not_yet_valid");
    }
    if (!now.isBefore(expiresAt)) {
      return Optional.of("expired");
    }
    return Optional.empty();
  }

  /** 每次调用都重新拉取 JWKS（调用方在未命中时再调用一次即「刷新一次」）。 */
  private Optional<PublicKey> findKey(String kid) {
    jwksFetches.incrementAndGet();
    Response jwks = api.keys(apiKey);
    if (jwks.statusCode() != 200) {
      throw new AssertionError("拉取 /guard/keys 失败: " + jwks.statusCode() + " " + jwks.asString());
    }
    for (JsonNode jwk : ExecutionReceiptAssert.tree(jwks.asString()).path("keys")) {
      if (kid.equals(jwk.path("kid").asText()) && "OKP".equals(jwk.path("kty").asText())
          && "Ed25519".equals(jwk.path("crv").asText())) {
        return Optional.of(publicKey(jwk.path("x").asText()));
      }
    }
    return Optional.empty();
  }

  /** JWK {@code x}（base64url 32 字节原始公钥）→ X.509 SubjectPublicKeyInfo → JDK Ed25519 公钥。 */
  static PublicKey publicKey(String x) {
    byte[] raw = Base64.getUrlDecoder().decode(x);
    byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
    System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
    System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
    try {
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("JWK x 不是合法的 Ed25519 公钥", e);
    }
  }

  private static boolean signatureValid(PublicKey key, JsonNode payload, String signature) {
    try {
      byte[] sig = Base64.getUrlDecoder().decode(signature);
      if (sig.length != 64) {
        return false;
      }
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(JcsCanonicalizer.canonicalize(payload));
      return verifier.verify(sig);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      return false;
    }
  }

  private static String errorOf(Response r) {
    String status = "http_" + r.statusCode();
    String body = r.asString();
    String error = null;
    if (body != null && !body.isEmpty()) {
      try {
        error = ExecutionReceiptAssert.tree(body).path("error").asText(null);
      } catch (RuntimeException e) {
        error = null;
      }
    }
    if (r.statusCode() == 409 && error != null) {
      return error;
    }
    return error == null ? status : status + ":" + error;
  }
}
