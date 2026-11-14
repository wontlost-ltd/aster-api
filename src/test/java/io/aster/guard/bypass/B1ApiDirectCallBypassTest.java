package io.aster.guard.bypass;

import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static io.aster.guard.bypass.BypassFixture.OTHER_TENANT_KEY;
import static io.aster.guard.bypass.BypassFixture.VIEWER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 1 条（API 层）：不经 Guard 决策、直接打执行面端点（consume / result / keys / audit）。
 *
 * <p>每例的攻击对象是攻击前用 MEMBER key 开好的 ALLOW 决策（有收据行）；攻击请求只断言状态码与体，
 * 之后一律用 MEMBER key 核对未消耗、无副作用，最后诚实执行仍 {@link Executed}——证明攻击既没消耗、也没「烧掉」收据。
 * 部署层含义（模型触达不到原始工具端点）不在本类，见 {@code B1DirectConnectToolBypassTest}（阶段 C）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B1ApiDirectCallBypassTest {
  private static final String TENANT = BypassFixture.tenant("b1");
  private static final String OTHER = BypassFixture.tenant("b1-other");

  @Inject BypassFixture api;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;
  private String body;
  private String decisionId;
  private String decisionJson;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
    body = BypassFixture.allowBody(250000);
    Response d = api.decide(MEMBER_KEY, body);
    assertThat(d.statusCode()).as(d.asString()).isEqualTo(200);
    assertThat(d.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    decisionId = d.jsonPath().getString("decisionId");
    decisionJson = d.asString();
  }

  /** 攻击之后：未消耗、无副作用，诚实执行仍成功（收据没有被攻击「烧掉」）。 */
  private void assertUntouchedThenHonestExecutes() {
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    BypassAssertions.noSideEffect(ledger, decisionId);
    assertThat(executor.run(decisionJson, body, "read:C-1001")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  private String goodHash() {
    return ExecutionReceiptAssert.actionHashOf(body);
  }

  @Test
  void consumeWithoutCredentialsIs401AndNotConsumed() {
    Response r = api.consume(null, decisionId, goodHash(), UUID.randomUUID().toString(), "anon");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(401);
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_CONSUME_ATTEMPT")).isEmpty();
    assertUntouchedThenHonestExecutes();
  }

  /** 他租户 key → 404 空体，不泄露决策存在；再自报受害租户的 {@code X-Tenant-Id} 头 → 403 tenant_mismatch。 */
  @Test
  void consumeWithOtherTenantKeyIs404AndNotConsumed() {
    Response plain = api.consume(OTHER_TENANT_KEY, decisionId, goodHash(), UUID.randomUUID().toString(), "x");
    Response spoofed = BypassFixture.as(OTHER_TENANT_KEY).header("X-Tenant-Id", TENANT)
        .header("X-User-Id", BypassFixture.MEMBER_USER).contentType(ContentType.JSON)
        .body(BypassFixture.consumeBody(goodHash(), UUID.randomUUID().toString(), "x"))
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");

    assertThat(plain.statusCode()).as(plain.asString()).isEqualTo(404);
    assertThat(plain.asString()).isEmpty();
    // 自报受害租户：鉴权过滤器在进入资源前即以 key 绑定的租户为准拒绝
    assertThat(spoofed.statusCode()).as(spoofed.asString()).isEqualTo(403);
    assertThat(spoofed.jsonPath().getString("reason")).isEqualTo("tenant_mismatch");
    assertThat(api.decision(OTHER_TENANT_KEY, decisionId).statusCode()).isEqualTo(404);
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_CONSUME_ATTEMPT")).isEmpty();
    assertUntouchedThenHonestExecutes();
  }

  /** VIEWER（含自报 {@code X-User-Role: ADMIN}）→ 403：consume 要求 MEMBER。 */
  @Test
  void consumeWithViewerKeyIs403AndNotConsumed() {
    Response plain = api.consume(VIEWER_KEY, decisionId, goodHash(), UUID.randomUUID().toString(), "viewer");
    Response escalated = BypassFixture.as(VIEWER_KEY).header("X-User-Role", "ADMIN").contentType(ContentType.JSON)
        .body(BypassFixture.consumeBody(goodHash(), UUID.randomUUID().toString(), "viewer"))
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");

    for (Response r : List.of(plain, escalated)) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
    }
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_CONSUME_ATTEMPT")).isEmpty();
    assertUntouchedThenHonestExecutes();
  }

  /** 伪造执行记录：未 consume 直接回写 → 409 not_consumed，行 result_status 为空、链上无结果事件。 */
  @Test
  void resultWithoutConsumeIs409NotConsumed() {
    Response r = api.result(MEMBER_KEY, decisionId, UUID.randomUUID().toString(), "forger", "executed");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.asString()).isEqualTo("{\"error\":\"not_consumed\"}");
    assertThat(BypassFixture.receiptRow(decisionId).resultStatus).isNull();
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_RESULT")).isEmpty();
    assertUntouchedThenHonestExecutes();
  }

  /** 执行面的读端点同样要求 Bearer：匿名 401。 */
  @Test
  void keysAndAuditReceiptsRequireBearer() {
    assertThat(api.keys(null).statusCode()).isEqualTo(401);
    assertThat(api.auditReceipts(null, decisionId).statusCode()).isEqualTo(401);
    assertThat(api.decision(null, decisionId).statusCode()).isEqualTo(401);
    assertThat(api.decide(null, body).statusCode()).isEqualTo(401);
    assertThat(api.result(null, decisionId, UUID.randomUUID().toString(), "anon", "executed").statusCode())
        .isEqualTo(401);
    assertUntouchedThenHonestExecutes();
  }
}
