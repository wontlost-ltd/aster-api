package io.aster.guard.reconcile;

import io.aster.guard.bypass.BypassFixture;
import io.aster.guard.bypass.BypassProfile;
import io.aster.test.PostgresTestResource;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.aster.guard.bypass.BypassFixture.as;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账证据不可被执行方抹掉（#465 × #466）：被攻陷的 Agent key（MEMBER）不能确认孤儿来隐藏绕过，无凭证不能上报或读取，
 * 他租户的孤儿不可见。走 {@link BypassProfile}（apikey + RBAC + HMAC 全开），身份只来自 Bearer key。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class GuardReconciliationAuthBypassTest {
  private static final String TENANT = BypassFixture.tenant("reconcile");
  private static final String OTHER = BypassFixture.tenant("reconcile-other");
  private static final String ADMIN_KEY = "ak_test_bypass_reconcile_admin";

  @Inject BypassFixture api;
  @Inject ApiKeyVerifierService verifier;

  private long orphanId;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    verifier.seedCacheForTest(ADMIN_KEY, ApiKeyVerifyResult.valid("ak-bypass-reconcile-admin", "bypass-admin", TENANT,
        "enterprise", "active", "ADMIN", null, List.of()));
    QuarkusTransaction.requiringNew().run(() -> {
      GuardOrphanActionEntity.delete("tenantId in ?1", List.of(TENANT, OTHER));
      GuardOrphanActionEntity o = GuardReconcilePersistenceTest.orphan(TENANT, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION,
          "se:" + UUID.randomUUID(), null);
      o.persist();
      orphanId = o.id;
    });
  }

  private static Response ack(String key, long id) {
    return as(key).contentType(ContentType.JSON).body(Map.of("comment", "隐藏证据"))
        .when().post("/api/v1/guard/reconciliation/orphans/" + id + "/ack");
  }

  private String status() {
    return QuarkusTransaction.requiringNew()
        .call(() -> GuardOrphanActionEntity.<GuardOrphanActionEntity>findById(orphanId).status);
  }

  @Test
  void memberCannotAcknowledgeOrphan() {
    Response r = ack(BypassFixture.MEMBER_KEY, orphanId);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
    assertThat(status()).isEqualTo("OPEN");
  }

  @Test
  void otherTenantCannotAcknowledge() {
    Response r = ack(BypassFixture.OTHER_TENANT_KEY, orphanId);
    assertThat(r.statusCode()).isIn(403, 404);
    assertThat(status()).isEqualTo("OPEN");
  }

  @Test
  void adminAcknowledges() {
    Response r = ack(ADMIN_KEY, orphanId);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("acknowledgedBy")).isEqualTo("bypass-admin");
    assertThat(status()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void anonymousIs401Everywhere() {
    assertThat(ack(null, orphanId).statusCode()).isEqualTo(401);
    assertThat(as(null).when().get("/api/v1/guard/reconciliation").statusCode()).isEqualTo(401);
    assertThat(as(null).when().get("/api/v1/guard/reconciliation/orphans").statusCode()).isEqualTo(401);
    assertThat(as(null).contentType(ContentType.JSON)
        .body("{\"effects\":[{\"effectId\":\"e\",\"source\":\"s\",\"occurredAt\":\"2026-11-14T00:00:00Z\","
            + "\"action\":{\"name\":\"x\"}}]}")
        .when().post("/api/v1/guard/side-effects").statusCode()).isEqualTo(401);
    assertThat(status()).isEqualTo("OPEN");
  }

  @Test
  void memberReadsOwnTenantOnly() {
    Response own = as(BypassFixture.MEMBER_KEY).when().get("/api/v1/guard/reconciliation/orphans");
    assertThat(own.statusCode()).as(own.asString()).isEqualTo(200);
    assertThat(own.jsonPath().getList("items.id", Long.class)).containsExactly(orphanId);
    Response other = as(BypassFixture.OTHER_TENANT_KEY).when().get("/api/v1/guard/reconciliation/orphans");
    assertThat(other.statusCode()).isEqualTo(200);
    assertThat(other.jsonPath().getList("items")).isEmpty();
  }

  @Test
  void selfReportedTenantHeaderIgnored() {
    Response r = as(BypassFixture.OTHER_TENANT_KEY).header("X-Tenant-Id", TENANT).when()
        .get("/api/v1/guard/reconciliation/orphans");
    // 自报租户与 key 租户不一致即拒绝，绝不按自报头读出 TENANT 的孤儿
    assertThat(r.statusCode()).isEqualTo(403);
  }
}
