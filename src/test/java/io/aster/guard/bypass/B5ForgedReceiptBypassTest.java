package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.api.GuardApprovalResourceTest;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.guard.receipt.JcsCanonicalizer;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static io.aster.guard.bypass.BypassFixture.OTHER_TENANT_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 5 条：伪造 / 篡改 / 过期 / 未知 kid / 他租户 / 嫁接的收据。
 *
 * <p>服务端没有「提交收据」入口（ADR 0040 §12 D6 末段）：consume 只看收据行与哈希，不验签名。所以伪造签名、篡改载荷、
 * 未知 kid 被拒的<b>判定点是执行方本地验签</b>——本类以参考执行方证明「照 D6 顺序验签的执行方不会执行」，
 * 生产执行方由 aster-guard SDK 的夹具层（#464，ADR 0054 §7）证明同一性质。服务端能独立判定的部分
 * （过期 409 {@code receipt_expired}、DENY 409 {@code not_allowed}、跨租户 404）同时断言。撤销 kid 见
 * {@link B5RevokedKidBypassTest}。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B5ForgedReceiptBypassTest {
  private static final String TENANT = BypassFixture.tenant("b5");
  private static final String OTHER = BypassFixture.tenant("b5-other");

  @Inject BypassFixture api;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
  }

  private Response allow(String key, String body) {
    Response r = api.decide(key, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return r;
  }

  private static ObjectNode tree(Response r) {
    return (ObjectNode) ExecutionReceiptAssert.tree(r.asString());
  }

  private static ObjectNode payloadOf(ObjectNode decision) {
    return (ObjectNode) decision.get("executionReceipt").get("payload");
  }

  /** 攻击者自生成的 Ed25519 key 对收据载荷的 JCS 字节签名，写回外层 signature。 */
  private static void signWith(KeyPair attacker, ObjectNode decision) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(attacker.getPrivate());
      signer.update(JcsCanonicalizer.canonicalize(payloadOf(decision)));
      ((ObjectNode) decision.get("executionReceipt"))
          .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair attackerKey() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 本地拒绝之后：未消耗、无副作用，原收据诚实执行仍成功。 */
  private void assertNotExecutedThenOriginalStillWorks(Response original, String body) {
    String decisionId = original.jsonPath().getString("decisionId");
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    assertThat(executor.run(original.asString(), body, "honest")).isInstanceOf(Executed.class);
  }

  @Test
  void tamperedPayloadByteFailsLocalVerify() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(MEMBER_KEY, body);
    ObjectNode forged = tree(d);
    Instant expiresAt = Instant.parse(payloadOf(forged).get("expiresAt").asText());
    payloadOf(forged).put("expiresAt", expiresAt.plus(Duration.ofDays(1)).toString());

    assertThat(executor.run(forged.toString(), body, "forged")).isEqualTo(new Refused("local", "bad_signature"));
    assertNotExecutedThenOriginalStillWorks(d, body);
  }

  /** 攻击者用自己的 key 对改过的载荷重新签名、kid 仍写 test-a：签名在 JWKS 的 test-a 公钥下不成立。 */
  @Test
  void signatureFromAttackerKeyFailsLocalVerify() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(MEMBER_KEY, body);
    String tamperedBody = BypassFixture.allowBody(1);
    ObjectNode forged = tree(d);
    payloadOf(forged).put("actionHash", ExecutionReceiptAssert.actionHashOf(tamperedBody));
    signWith(attackerKey(), forged);

    assertThat(executor.run(forged.toString(), tamperedBody, "forged")).isEqualTo(new Refused("local", "bad_signature"));
    assertNotExecutedThenOriginalStillWorks(d, body);
  }

  /** kid 改为 JWKS 中没有的 attacker-1（内外两处）：刷新 JWKS 一次后仍未命中 → kid_unknown。 */
  @Test
  void unknownKidFailsLocalVerify() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(MEMBER_KEY, body);
    ObjectNode forged = tree(d);
    ((ObjectNode) forged.get("executionReceipt")).put("kid", "attacker-1");
    payloadOf(forged).put("kid", "attacker-1");
    signWith(attackerKey(), forged);

    assertThat(executor.run(forged.toString(), body, "forged")).isEqualTo(new Refused("local", "kid_unknown"));
    assertThat(executor.jwksFetches()).isEqualTo(2);
    assertNotExecutedThenOriginalStillWorks(d, body);
  }

  /** 他租户的合法收据交给本租户执行方 → tenant_mismatch；本租户身份 consume 该决策 → 404。 */
  @Test
  void otherTenantReceiptFailsLocalVerify() {
    String body = BypassFixture.allowBody(250000);
    Response foreign = allow(OTHER_TENANT_KEY, body);
    String foreignId = foreign.jsonPath().getString("decisionId");

    assertThat(executor.run(foreign.asString(), body, "foreign")).isEqualTo(new Refused("local", "tenant_mismatch"));
    assertThat(executor.attackConsume(foreignId, ExecutionReceiptAssert.actionHashOf(body), "foreign"))
        .isEqualTo(new Refused("consume", "http_404"));
    BypassAssertions.noSideEffect(ledger, foreignId);
    BypassAssertions.notConsumed(api, OTHER_TENANT_KEY, foreignId);
  }

  /** DENY 响应里嫁接某 ALLOW 决策的合法收据 → decision_mismatch；直接 consume 该 DENY → 409 not_allowed。 */
  @Test
  void denyResponseWithGraftedReceiptFailsLocalVerify() {
    String allowBody = BypassFixture.allowBody(1);
    Response allowed = allow(MEMBER_KEY, allowBody);
    String denyBody = BypassFixture.denyBody(1);
    Response deny = api.decide(MEMBER_KEY, denyBody);
    assertThat(deny.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    String denyId = deny.jsonPath().getString("decisionId");
    ObjectNode grafted = tree(deny);
    grafted.set("executionReceipt", tree(allowed).get("executionReceipt"));

    assertThat(executor.run(grafted.toString(), denyBody, "grafted"))
        .isEqualTo(new Refused("local", "decision_mismatch"));
    assertThat(executor.attackConsume(denyId, ExecutionReceiptAssert.actionHashOf(denyBody), "grafted"))
        .isEqualTo(new Refused("consume", "not_allowed"));
    BypassAssertions.noSideEffect(ledger, denyId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, denyId);
    assertNotExecutedThenOriginalStillWorks(allowed, allowBody);
  }

  /**
   * {@code now == expiresAt} 即过期（排他）：本地 expired；服务端 409 receipt_expired 且体中 expiresAt 相等。
   * 推进的只是 guard 的 {@code Clock} bean；key 缓存走 Caffeine 系统时钟，推进前后都先确认不是 401（Review Focus 1）。
   */
  @Test
  void expiredIsRejectedLocallyAndByServer() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    GuardApprovalResourceTest.MutableClock clock = new GuardApprovalResourceTest.MutableClock(t0);
    QuarkusMock.installMockForType(clock, Clock.class);
    String body = BypassFixture.allowBody(250000);
    Response d = allow(MEMBER_KEY, body);
    String decisionId = d.jsonPath().getString("decisionId");
    Instant expiresAt = Instant.parse(d.jsonPath().getString("executionReceipt.payload.expiresAt"));
    assertThat(expiresAt).isEqualTo(t0.plus(Duration.ofMinutes(5)));

    ReferenceExecutor late = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.fixed(expiresAt, ZoneOffset.UTC));
    assertThat(late.run(d.asString(), body, "late")).isEqualTo(new Refused("local", "expired"));

    clock.set(expiresAt);
    api.seedKeys(TENANT, OTHER);
    assertThat(api.decision(MEMBER_KEY, decisionId).statusCode()).isEqualTo(200);
    assertThat(late.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(body), "late"))
        .isEqualTo(new Refused("consume", "receipt_expired"));
    assertThat(late.lastConsume().statusCode()).isEqualTo(409);
    assertThat(Instant.parse(late.lastConsume().jsonPath().getString("expiresAt"))).isEqualTo(expiresAt);
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
  }

  /** 执行方时钟早于 issuedAt 超过 60 s → not_yet_valid（只测本地窗口）；恰好 60 s 是边界内，照常执行。 */
  @Test
  void notYetValidIsRejectedLocally() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(MEMBER_KEY, body);
    Instant issuedAt = Instant.parse(d.jsonPath().getString("executionReceipt.payload.issuedAt"));
    ReferenceExecutor early = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger,
        Clock.fixed(issuedAt.minusSeconds(61), ZoneOffset.UTC));
    ReferenceExecutor edge = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger,
        Clock.fixed(issuedAt.minusSeconds(60), ZoneOffset.UTC));

    assertThat(early.run(d.asString(), body, "early")).isEqualTo(new Refused("local", "not_yet_valid"));
    BypassAssertions.notConsumed(api, MEMBER_KEY, d.jsonPath().getString("decisionId"));
    assertThat(edge.run(d.asString(), body, "edge")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, d.jsonPath().getString("decisionId"), ledger);
  }
}
