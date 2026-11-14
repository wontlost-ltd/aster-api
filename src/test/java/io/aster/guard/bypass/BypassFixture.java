package io.aster.guard.bypass;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * 绕过套件夹具（#466）：租户隔离、策略部署、API key 播种与 HTTP 辅助。
 *
 * <p>所有请求只带 {@code Authorization: Bearer}，身份由 {@code ApiKeyAuthFilter} 从已播种的 key 解析；
 * key 为 {@code null} 表示匿名请求。播种的 key 只在 Caffeine 缓存里（TTL 60 s，系统时钟，不受 guard 的
 * {@code Clock} bean 影响），所以每个用例都在 {@code @BeforeEach} 里重新播种。
 */
@ApplicationScoped
public class BypassFixture {
  /** 每次 JVM 用独立租户后缀：审计链只追加不删除。 */
  public static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  /** 受害租户的普通成员（Agent 的 key）：开决策、诚实执行。 */
  public static final String MEMBER_KEY = "ak_test_bypass_member";
  /** 受害租户的只读成员。 */
  public static final String VIEWER_KEY = "ak_test_bypass_viewer";
  /** 受害租户的 DPO（业务角色 {@value #DPO}），另一个用户。 */
  public static final String DPO_KEY = "ak_test_bypass_dpo";
  /** 另一租户的普通成员。 */
  public static final String OTHER_TENANT_KEY = "ak_test_bypass_other";

  public static final String MEMBER_USER = "bypass-member";
  public static final String DPO_USER = "bypass-dpo";
  public static final String DPO = "Data Protection Officer";

  /** 恒 DENY 的策略模块（规则直接返回 {@code Verdict.deny}）。 */
  public static final String DENY_MODULE = "guard.bypass.deny";
  public static final String DENY_SOURCE = String.join("\n",
      "Module guard.bypass.deny.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.",
      "",
      "@id(\"BYPASS-DENY-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  Return Verdict.deny(\"bypass_test\").");

  @Inject ApiKeyVerifierService verifier;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  public static String tenant(String scenario) {
    return "tenant-bypass-" + scenario + "-" + RUN;
  }

  /**
   * 清空给定租户的审批 / 收据 / 决策 / 签发方与策略三表，再部署探针策略（{@code delete_customer_record} →
   * REQUIRE_APPROVAL，其余 ALLOW）与恒 DENY 策略。
   */
  public void reset(String... tenants) {
    QuarkusTransaction.requiringNew().run(() -> {
      for (String tenant : tenants) {
        GuardApprovalEntity.delete("tenantId = ?1", tenant);
        GuardReceiptEntity.delete("tenantId = ?1", tenant);
        // 待审批决策的输入快照（#460，V6.37.0）以外键引用 guard_decisions，须先删
        GuardDecisionInputEntity.delete("tenantId = ?1", tenant);
        GuardDecisionEntity.delete("tenantId = ?1", tenant);
        GuardFactIssuerEntity.delete("tenantId = ?1", tenant);
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
        PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
        PolicyVersion.delete("tenantId = ?1", tenant);
        PolicyCatalog.delete("tenantId = ?1", tenant);
      }
    });
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    for (String tenant : tenants) {
      deploy(tenant, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.SOURCE);
      deploy(tenant, DENY_MODULE, DENY_SOURCE);
    }
  }

  /** 部署 {@code module.decide} 的活跃版本，返回版本 id。 */
  public Long deploy(String tenant, String module, String source) {
    return DeployedMapParamProbeTest.deploy(tenant, module, "decide", source);
  }

  /**
   * 播种四把 key：受害租户 MEMBER / VIEWER / DPO（业务角色 DPO、另一用户）与他租户 MEMBER。
   * 套餐取 enterprise，避免每秒限流干扰连续开决策的用例。
   */
  public void seedKeys(String tenant, String otherTenant) {
    verifier.seedCacheForTest(MEMBER_KEY, ApiKeyVerifyResult.valid("ak-bypass-member", MEMBER_USER, tenant,
        "enterprise", "active", "MEMBER", null, List.of()));
    verifier.seedCacheForTest(VIEWER_KEY, ApiKeyVerifyResult.valid("ak-bypass-viewer", "bypass-viewer", tenant,
        "enterprise", "active", "VIEWER", null, List.of()));
    verifier.seedCacheForTest(DPO_KEY, ApiKeyVerifyResult.valid("ak-bypass-dpo", DPO_USER, tenant,
        "enterprise", "active", "MEMBER", null, List.of(DPO)));
    verifier.seedCacheForTest(OTHER_TENANT_KEY, ApiKeyVerifyResult.valid("ak-bypass-other", "bypass-other",
        otherTenant, "enterprise", "active", "MEMBER", null, List.of()));
  }

  // ---- 请求体 ----

  /** 决策请求体；{@code context} 为 JSON 对象原文。 */
  public static String decisionBody(String module, String actionName, String resourceId, String contextJson) {
    return "{\"policyModule\":\"" + module + "\",\"policyFunction\":\"decide\",\"action\":{"
        + "\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\",\"roles\":[\"ops-bot\"]},"
        + "\"action\":{\"name\":\"" + actionName + "\"},\"resource\":{\"type\":\"customer\",\"id\":\"" + resourceId
        + "\"},\"context\":" + contextJson + "}}";
  }

  /** 探针策略下的 ALLOW 动作（读客户记录），金额可变。 */
  public static String allowBody(long amount) {
    return allowBody(amount, "C-1001");
  }

  public static String allowBody(long amount, String resourceId) {
    return decisionBody(DeployedMapParamProbeTest.MODULE, "read_customer_record", resourceId,
        "{\"amount\":" + amount + "}");
  }

  /** 探针策略下的待审批动作（删除客户记录，需 DPO）。 */
  public static String pendingBody(long amount) {
    return decisionBody(DeployedMapParamProbeTest.MODULE, "delete_customer_record", "C-1001",
        "{\"amount\":" + amount + "}");
  }

  /** 恒 DENY 策略下的动作。 */
  public static String denyBody(long amount) {
    return decisionBody(DENY_MODULE, "read_customer_record", "C-1001", "{\"amount\":" + amount + "}");
  }

  public static String consumeBody(String actionHash, String attemptId, String executorId) {
    return "{\"actionHash\":\"" + actionHash + "\",\"attemptId\":\"" + attemptId + "\",\"executorId\":\""
        + executorId + "\"}";
  }

  public static String resultBody(String attemptId, String executorId, String status) {
    return "{\"attemptId\":\"" + attemptId + "\",\"executorId\":\"" + executorId + "\",\"status\":\"" + status
        + "\"}";
  }

  // ---- HTTP ----

  /** {@code key == null} 时不带任何凭证。 */
  public static RequestSpecification as(String key) {
    RequestSpecification spec = given();
    return key == null ? spec : spec.header("Authorization", "Bearer " + key);
  }

  public Response decide(String key, String body) {
    return decide(key, body, null);
  }

  public Response decide(String key, String body, String idempotencyKey) {
    RequestSpecification spec = as(key).contentType(ContentType.JSON).body(body);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.when().post("/api/v1/guard/decisions");
  }

  public Response consume(String key, String decisionId, String actionHash, String attemptId, String executorId) {
    return as(key).contentType(ContentType.JSON).body(consumeBody(actionHash, attemptId, executorId))
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");
  }

  public Response result(String key, String decisionId, String attemptId, String executorId, String status) {
    return as(key).contentType(ContentType.JSON).body(resultBody(attemptId, executorId, status))
        .when().post("/api/v1/guard/receipts/" + decisionId + "/result");
  }

  public Response decision(String key, String decisionId) {
    return as(key).when().get("/api/v1/guard/decisions/" + decisionId);
  }

  public Response auditReceipts(String key, String decisionId) {
    return as(key).queryParam("decisionIds", decisionId).when().get("/api/v1/audit/receipts");
  }

  public Response keys(String key) {
    return as(key).when().get("/api/v1/guard/keys");
  }

  public Response approve(String key, String approvalId, String comment) {
    return as(key).contentType(ContentType.JSON).body(comment == null ? Map.of() : Map.of("comment", comment))
        .when().post("/api/v1/guard/approvals/" + approvalId + "/approve");
  }

  public Response reject(String key, String approvalId, String comment) {
    return as(key).contentType(ContentType.JSON).body(Map.of("comment", comment))
        .when().post("/api/v1/guard/approvals/" + approvalId + "/reject");
  }

  // ---- 行与链 ----

  public static GuardReceiptEntity receiptRow(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.<GuardReceiptEntity>findById(id));
  }

  public static long decisionRows(String tenant) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("tenantId", tenant));
  }

  public static long approvalRows(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.count("decisionId", id));
  }

  /** 某决策的指定类型审计事件，按 id 升序。 */
  public static List<AuditLog> events(String decisionId, String type) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>list(
        "decisionId = ?1 and eventType = ?2 order by id", decisionId, type));
  }
}
