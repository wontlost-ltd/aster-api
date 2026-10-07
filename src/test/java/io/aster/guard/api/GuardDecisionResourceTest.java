package io.aster.guard.api;

import io.aster.audit.chain.AuditChainAnchorService;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §3：{@code POST/GET /api/v1/guard/decisions} 端到端（真实部署的规则 + 审计链）。
 * 测试配置下 apikey / rbac / 签名过滤器关闭，身份只靠 {@code X-Tenant-Id} 与 {@code X-User-Id}。
 */
@QuarkusTest
class GuardDecisionResourceTest {
  /** 每次 JVM 用独立租户：审计链与锚点只追加不删除，避免与历史运行互相干扰。 */
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-rest-" + RUN;
  private static final String OTHER_TENANT = "tenant-guard-rest-other-" + RUN;
  private static final String ALLOW_MODULE = "guard.rest.allow";
  private static final String BOOL_MODULE = "guard.rest.bool";
  private static final String FUNCTION = "decide";
  private static final String DEFINE =
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.";
  private static final String ALLOW_SOURCE = String.join("\n",
      "Module guard.rest.allow.", "", DEFINE, "",
      "@id(\"REST-ALLOW-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  Return Verdict.allow().");
  private static final String BOOL_SOURCE = String.join("\n",
      "Module guard.rest.bool.", "", DEFINE, "",
      "Rule decide given req as GuardRequest, produce Bool:",
      "  Return true.");

  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;
  @Inject AuditChainAnchorService anchorService;

  @BeforeEach
  void setUp() {
    clean();
    for (String tenant : List.of(TENANT, OTHER_TENANT)) {
      DeployedMapParamProbeTest.deploy(tenant, ALLOW_MODULE, FUNCTION, ALLOW_SOURCE);
      DeployedMapParamProbeTest.deploy(tenant, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
          DeployedMapParamProbeTest.SOURCE);
      DeployedMapParamProbeTest.deploy(tenant, BOOL_MODULE, FUNCTION, BOOL_SOURCE);
    }
  }

  private void clean() {
    QuarkusTransaction.requiringNew().run(() -> List.of(TENANT, OTHER_TENANT).forEach(tenant -> {
      GuardApprovalEntity.delete("tenantId = ?1", tenant);
      GuardDecisionEntity.delete("tenantId = ?1", tenant);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
      PolicyVersion.delete("tenantId = ?1", tenant);
      PolicyCatalog.delete("tenantId = ?1", tenant);
    }));
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
  }

  /** 构造请求体；principalId 为 null 时省略该字段（用于 invalid_action 用例）。 */
  private static Map<String, Object> body(String module, String function, String actionName, String principalId) {
    Map<String, Object> principal = principalId == null
        ? Map.of("type", "agent", "roles", List.of("ops-bot"))
        : Map.of("id", principalId, "type", "agent", "roles", List.of("ops-bot"));
    Map<String, Object> action = Map.of(
        "principal", principal,
        "action", Map.of("name", actionName),
        "resource", Map.of("type", "customer", "id", "C-1001"),
        "context", Map.of("amount", 250000),
        "agent", Map.of("model", "claude-fable-5-1", "session", "s-1"));
    return Map.of("policyModule", module, "policyFunction", function, "action", action);
  }

  private static Map<String, Object> allowBody() {
    return body(ALLOW_MODULE, FUNCTION, "read_customer_record", "agent-42");
  }

  private static Response post(String tenant, Map<String, Object> body, String idempotencyKey) {
    var spec = given()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", "user-guard-rest")
        .contentType(ContentType.JSON)
        .body(body);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.when().post("/api/v1/guard/decisions");
  }

  private static Response get(String tenant, String id) {
    return given()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", "user-guard-rest")
        .when().get("/api/v1/guard/decisions/" + id);
  }

  @Test
  void ALLOW响应完整() {
    Response resp = post(TENANT, allowBody(), null);

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> json = resp.jsonPath().getMap("$");
    assertThat(json.get("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(resp.jsonPath().getString("receipt.hash")).matches("[0-9a-f]{64}");
    assertThat(resp.jsonPath().getString("policyVersion.sourceHash")).isNotBlank();
    assertThat(json).doesNotContainKeys("approval", "reasonCode");
  }

  @Test
  void REQUIRE_APPROVAL返回PENDING与approval() {
    Map<String, Object> req = body(DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
        "delete_customer_record", "agent-42");

    Response resp = post(TENANT, req, null);

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(resp.jsonPath().getString("approval.status")).isEqualTo("PENDING");
    assertThat(resp.jsonPath().getString("approval.requiredRole")).isEqualTo("Data Protection Officer");
    Instant expiresAt = Instant.parse(resp.jsonPath().getString("approval.expiresAt"));
    Instant now = Instant.now();
    assertThat(expiresAt).isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(25)));
  }

  @Test
  void Bool规则返回DENY_not_a_verdict() {
    Response resp = post(TENANT, body(BOOL_MODULE, FUNCTION, "read_customer_record", "agent-42"), null);

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(resp.jsonPath().getString("reasonCode")).isEqualTo("not_a_verdict");
  }

  @Test
  void 策略不存在返回404_policy_not_found() {
    Response resp = post(TENANT, body("guard.rest.missing", FUNCTION, "read_customer_record", "agent-42"), null);

    assertThat(resp.statusCode()).isEqualTo(404);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("policy_not_found");
  }

  @Test
  void 缺principal_id返回400_invalid_action() {
    Response resp = post(TENANT, body(ALLOW_MODULE, FUNCTION, "read_customer_record", null), null);

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
    assertThat(resp.jsonPath().getString("message")).isNotBlank();
  }

  @Test
  void 幂等重放响应体字节相同且租户间互不影响() {
    String key = "idem-" + UUID.randomUUID();

    Response first = post(TENANT, allowBody(), key);
    Response replay = post(TENANT, allowBody(), key);
    Response otherTenant = post(OTHER_TENANT, allowBody(), key);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.asString()).isEqualTo(first.asString());
    assertThat(otherTenant.statusCode()).isEqualTo(200);
    assertThat(otherTenant.jsonPath().getString("decisionId"))
        .isNotEqualTo(first.jsonPath().getString("decisionId"));
  }

  @Test
  void 并发同键只产生一个决策() throws Exception {
    String key = "idem-concurrent-" + UUID.randomUUID();
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Response>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return post(TENANT, allowBody(), key);
        }));
      }
      start.countDown();

      List<Integer> statuses = new ArrayList<>();
      Set<String> decisionIds = new HashSet<>();
      for (Future<Response> f : futures) {
        Response r = f.get(60, TimeUnit.SECONDS);
        statuses.add(r.statusCode());
        decisionIds.add(r.jsonPath().getString("decisionId"));
      }
      assertThat(statuses).hasSize(threads).containsOnly(200);
      assertThat(decisionIds).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void GET回读等于POST响应且跨租户404() {
    Response created = post(TENANT, allowBody(), null);
    String id = created.jsonPath().getString("decisionId");

    Response read = get(TENANT, id);

    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.asString()).isEqualTo(created.asString());
    assertThat(get(OTHER_TENANT, id).statusCode()).isEqualTo(404);
    assertThat(get(TENANT, "not-a-uuid").statusCode()).isEqualTo(404);
  }

  @Test
  void verify_chain含guard事件仍valid() {
    Instant start = Instant.now().minus(Duration.ofHours(1));
    assertThat(post(TENANT, allowBody(), null).statusCode()).isEqualTo(200);
    Instant end = Instant.now().plus(Duration.ofMinutes(1));
    // verify-chain 把「有审计记录却零锚点」判为不可信；生产由定时任务锚定，此处显式触发。
    anchorService.anchorAllTenants();

    Response resp = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-rest")
        .header("X-User-Role", "ADMIN")
        .queryParam("start", start.toString())
        .queryParam("end", end.toString())
        .when().get("/api/v1/audit/verify-chain");

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getBoolean("valid")).isTrue();
    assertThat(resp.jsonPath().getInt("recordsVerified")).isGreaterThanOrEqualTo(1);
  }
}
