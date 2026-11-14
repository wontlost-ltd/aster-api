package io.aster.guard.reconcile;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账测试夹具：经真实 REST 端点造决策 / consume / 结果 / 上报（测试配置下 apikey / rbac 关闭，身份只靠
 * {@code X-Tenant-Id} 与 {@code X-User-Id}）；篡改类场景由各用例直接改行。每个租户名带本 JVM 的随机后缀——审计链只追加不删除。
 */
final class ReconcileFixture {
  static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  static final String DPO = "Data Protection Officer";
  static final String DENY_MODULE = "guard.reconcile.deny";
  static final String DENY_SOURCE = String.join("\n",
      "Module guard.reconcile.deny.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.",
      "",
      "@id(\"RECONCILE-DENY-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  Return Verdict.deny(\"reconcile_test\").");

  /** 一条决策：id、请求体原文与按原文自算的 actionHash。 */
  record Decision(String id, String body, String actionHash, String approvalId) {}

  private ReconcileFixture() {
  }

  static String tenant(String scenario) {
    return "tenant-reconcile-" + scenario + "-" + RUN + "-" + UUID.randomUUID().toString().substring(0, 4);
  }

  /** 部署探针策略（{@code delete_customer_record} → REQUIRE_APPROVAL，其余 ALLOW）与恒 DENY 策略。 */
  static void deploy(String... tenants) {
    for (String tenant : tenants) {
      DeployedMapParamProbeTest.deploy(tenant, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
          DeployedMapParamProbeTest.SOURCE);
      DeployedMapParamProbeTest.deploy(tenant, DENY_MODULE, "decide", DENY_SOURCE);
    }
  }

  /** 清掉对账三表与 guard 账本、策略三表中该租户的行（链事件不删）。 */
  static void purge(String... tenants) {
    QuarkusTransaction.requiringNew().run(() -> {
      for (String tenant : tenants) {
        GuardOrphanActionEntity.delete("tenantId = ?1", tenant);
        GuardSideEffectEntity.delete("tenantId = ?1", tenant);
        GuardReconciliationRunEntity.delete("tenantId = ?1", tenant);
        GuardDecisionInputEntity.delete("tenantId = ?1", tenant);
        GuardApprovalEntity.delete("tenantId = ?1", tenant);
        GuardReceiptEntity.delete("tenantId = ?1", tenant);
        GuardDecisionEntity.delete("tenantId = ?1", tenant);
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
        PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
        PolicyVersion.delete("tenantId = ?1", tenant);
        PolicyCatalog.delete("tenantId = ?1", tenant);
      }
    });
  }

  static String decisionBody(String module, String actionName, long amount) {
    return "{\"policyModule\":\"" + module + "\",\"policyFunction\":\"decide\",\"action\":{"
        + "\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\",\"roles\":[\"ops-bot\"]},"
        + "\"action\":{\"name\":\"" + actionName + "\"},\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"},"
        + "\"context\":{\"amount\":" + amount + "}}}";
  }

  private static Decision post(String tenant, String body, String expectedEffective) {
    Response r = given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-reconcile-agent")
        .contentType(ContentType.JSON).body(body).when().post("/api/v1/guard/decisions");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).as(r.asString()).isEqualTo(expectedEffective);
    return new Decision(r.jsonPath().getString("decisionId"), body, ExecutionReceiptAssert.actionHashOf(body),
        r.jsonPath().getString("approval.id"));
  }

  static Decision allow(String tenant, long amount) {
    return post(tenant, decisionBody(DeployedMapParamProbeTest.MODULE, "read_customer_record", amount), "ALLOW");
  }

  static Decision deny(String tenant) {
    return post(tenant, decisionBody(DENY_MODULE, "read_customer_record", 1), "DENY");
  }

  static Decision pending(String tenant) {
    return post(tenant, decisionBody(DeployedMapParamProbeTest.MODULE, "delete_customer_record", 1), "PENDING");
  }

  /** 由另一用户（DPO）批准，决策转 ALLOW 并签发收据。 */
  static void approve(String tenant, Decision d) {
    Response r = given().header("X-Tenant-Id", tenant).header("X-User-Id", "dpo-reconcile")
        .header("X-User-Business-Roles", DPO).contentType(ContentType.JSON).body(Map.of())
        .when().post("/api/v1/guard/approvals/" + d.approvalId() + "/approve");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
  }

  static Response consumeRaw(String tenant, String decisionId, String actionHash, String attemptId, String executorId) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-reconcile-executor")
        .contentType(ContentType.JSON)
        .body("{\"actionHash\":\"" + actionHash + "\",\"attemptId\":\"" + attemptId + "\",\"executorId\":\""
            + executorId + "\"}")
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");
  }

  /** consume 成功，返回 attemptId。 */
  static String consume(String tenant, Decision d, String executorId) {
    String attemptId = UUID.randomUUID().toString();
    Response r = consumeRaw(tenant, d.id(), d.actionHash(), attemptId, executorId);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    return attemptId;
  }

  static void result(String tenant, String decisionId, String attemptId, String executorId) {
    Response r = given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-reconcile-executor")
        .contentType(ContentType.JSON)
        .body("{\"attemptId\":\"" + attemptId + "\",\"executorId\":\"" + executorId + "\",\"status\":\"executed\"}")
        .when().post("/api/v1/guard/receipts/" + decisionId + "/result");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
  }

  /** 一条上报的 JSON；null 字段不写。 */
  static String effect(String effectId, String source, String decisionId, String attemptId, String actionHash) {
    StringBuilder b = new StringBuilder("{\"effectId\":\"").append(effectId).append("\",\"source\":\"").append(source)
        .append("\",\"occurredAt\":\"2026-11-14T09:00:00Z\",\"action\":{\"name\":\"read_customer_record\"},")
        .append("\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"}");
    if (decisionId != null) {
      b.append(",\"decisionId\":\"").append(decisionId).append('"');
    }
    if (attemptId != null) {
      b.append(",\"attemptId\":\"").append(attemptId).append('"');
    }
    if (actionHash != null) {
      b.append(",\"actionHash\":\"").append(actionHash).append('"');
    }
    return b.append('}').toString();
  }

  static Response reportRaw(String tenant, String body) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-reconcile-reporter")
        .contentType(ContentType.JSON).body(body).when().post("/api/v1/guard/side-effects");
  }

  /** 上报若干条并断言全部 accepted。 */
  static void report(String tenant, String... effects) {
    Response r = reportRaw(tenant, "{\"effects\":[" + String.join(",", effects) + "]}");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getInt("accepted")).as(r.asString()).isEqualTo(effects.length);
  }

  static List<GuardOrphanActionEntity> orphans(String tenant) {
    return QuarkusTransaction.requiringNew().call(() -> GuardOrphanActionEntity
        .<GuardOrphanActionEntity>list("tenantId = ?1 order by id", tenant));
  }

  static List<GuardSideEffectEntity> sideEffects(String tenant) {
    return QuarkusTransaction.requiringNew().call(() -> GuardSideEffectEntity
        .<GuardSideEffectEntity>list("tenantId = ?1 order by id", tenant));
  }

  static List<AuditLog> chainEvents(String tenant, String eventType) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>list(
        "tenantId = ?1 and eventType = ?2 order by id", tenant, eventType));
  }

  static GuardReceiptEntity receipt(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.<GuardReceiptEntity>findById(id));
  }
}
