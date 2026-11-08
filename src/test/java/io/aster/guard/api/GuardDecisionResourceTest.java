package io.aster.guard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.audit.chain.AuditChainAnchorService;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.guard.provenance.TestFactSigner;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.receipt.ReceiptSigningKeys;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
  @Inject FactIssuerStore factIssuerStore;
  @ConfigProperty(name = "aster.guard.receipt.signing-keys") String signingKeys;

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
      GuardReceiptEntity.delete("tenantId = ?1", tenant);
      GuardDecisionEntity.delete("tenantId = ?1", tenant);
      GuardFactIssuerEntity.delete("tenantId = ?1", tenant);
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

  /** I-JSON（jcs-sha256/v1）：context 中整数值超过 2^53−1 → 400 invalid_action，不做舍入；同值以字符串发送 → 200。 */
  @Test
  void unsafeIntegerIs400InvalidAction() {
    String template = "{\"policyModule\":\"" + ALLOW_MODULE + "\",\"policyFunction\":\"" + FUNCTION + "\","
        + "\"action\":{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},"
        + "\"context\":{\"amount\":%s,\"note\":null}}}";

    for (String unsafe : List.of("9007199254740992", "-9007199254740992", "1e20", "123456789012345680000")) {
      Response resp = postRaw(String.format(template, unsafe));
      assertThat(resp.statusCode()).as(unsafe).isEqualTo(400);
      assertThat(resp.jsonPath().getString("error")).as(unsafe).isEqualTo("invalid_action");
    }
    Response asString = postRaw(String.format(template, "\"9007199254740992\""));
    assertThat(asString.statusCode()).isEqualTo(200);
    assertThat(asString.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(postRaw(String.format(template, "9007199254740991")).statusCode()).isEqualTo(200);
  }

  private static Response postRaw(String json) {
    return postRaw(json, null);
  }

  private static Response postRaw(String json, String idempotencyKey) {
    var spec = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-rest")
        .contentType(ContentType.JSON)
        .body(json.getBytes(StandardCharsets.UTF_8));
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.when().post("/api/v1/guard/decisions");
  }

  /** 原文请求体：principal 原样写入（用于显式 null 与缺键的对比）。 */
  private static String rawBody(String module, String principalJson, String contextJson) {
    return "{\"policyModule\":\"" + module + "\",\"policyFunction\":\"" + FUNCTION + "\","
        + "\"action\":{\"principal\":" + principalJson + ",\"action\":{\"name\":\"read_customer_record\"},"
        + "\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"},\"context\":" + contextJson + "}}";
  }

  private static long receiptRows(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("decisionId", id));
  }

  private static String actionHashColumn(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(id).actionHash);
  }

  /** 重复键（顶层与 context 内各一例）→ 400 invalid_action，不评估、不落库。 */
  @Test
  void duplicateKeyInBodyIs400InvalidAction() {
    String top = "{\"policyModule\":\"" + ALLOW_MODULE + "\",\"policyModule\":\"" + ALLOW_MODULE + "\","
        + "\"policyFunction\":\"" + FUNCTION + "\",\"action\":{\"principal\":{\"id\":\"a\"},"
        + "\"action\":{\"name\":\"refund\"}}}";
    String inContext = rawBody(ALLOW_MODULE, "{\"id\":\"a\"}", "{\"amount\":1,\"amount\":2}");

    for (String body : List.of(top, inContext)) {
      Response resp = postRaw(body);
      assertThat(resp.statusCode()).as(body).isEqualTo(400);
      assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
      assertThat(resp.jsonPath().getString("message")).contains("重复键");
    }
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("tenantId", TENANT))).isZero();
  }

  /** I-JSON 拒绝作用于整个 action 子树，不止 context：未知键与 agent 内的不安全整数同样 400。 */
  @Test
  void unsafeIntegerAnywhereInActionIs400() {
    String inPrincipal = rawBody(ALLOW_MODULE, "{\"id\":\"a\",\"n\":9007199254740992}", "{}");
    String inAgent = "{\"policyModule\":\"" + ALLOW_MODULE + "\",\"policyFunction\":\"" + FUNCTION + "\","
        + "\"action\":{\"principal\":{\"id\":\"a\"},\"action\":{\"name\":\"refund\"},"
        + "\"agent\":{\"model\":\"m\",\"seq\":-9007199254740992}}}";

    for (String body : List.of(inPrincipal, inAgent)) {
      Response resp = postRaw(body);
      assertThat(resp.statusCode()).as(body).isEqualTo(400);
      assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
    }
  }

  /** 收据可由执行方复验：签名用配置中的测试公钥验证，actionHash 等于对请求体 action 原文自算。 */
  @Test
  void allowCarriesVerifiableExecutionReceipt() {
    String body = rawBody(ALLOW_MODULE, "{\"id\":\"agent-42\",\"type\":\"agent\"}",
        "{\"amount\":2.5E5,\"note\":\"\\u00e9\",\"flag\":null}");

    Response resp = postRaw(body);

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    JsonNode receipt = ExecutionReceiptAssert.assertVerifiable(resp.asString(), signingKeys);
    JsonNode payload = receipt.get("payload");
    String decisionId = resp.jsonPath().getString("decisionId");
    assertThat(payload.get("actionHash").asText()).isEqualTo(ExecutionReceiptAssert.actionHashOf(body));
    assertThat(payload.get("decisionId").asText()).isEqualTo(decisionId);
    assertThat(payload.get("tenantId").asText()).isEqualTo(TENANT);
    assertThat(payload.get("outcome").asText()).isEqualTo("ALLOW");
    assertThat(payload.get("v").asInt()).isEqualTo(1);
    assertThat(payload.get("policyVersionId").asLong()).isEqualTo(resp.jsonPath().getLong("policyVersion.id"));
    assertThat(Duration.between(Instant.parse(payload.get("issuedAt").asText()),
        Instant.parse(payload.get("expiresAt").asText()))).isEqualTo(Duration.ofMinutes(5));
    assertThat(receipt.get("kid").asText()).isEqualTo("test-a");
    // ADR 0040 §12 D6：外层带签发时的密钥配置 revision（不受签名覆盖）
    assertThat(receipt.get("revision").asLong()).isEqualTo(
        ReceiptSigningKeys.parse(signingKeys, "test-a", Clock.systemUTC()).revision());
    // 字段顺序：executionReceipt 紧随 effectiveOutcome
    assertThat(resp.asString()).contains("\"effectiveOutcome\":\"ALLOW\",\"executionReceipt\":{\"kid\":\"test-a\",\"payload\":");
    assertThat(actionHashColumn(decisionId)).isEqualTo(payload.get("actionHash").asText());
    assertThat(receiptRows(decisionId)).isEqualTo(1);
  }

  @Test
  void denyAndPendingHaveNoExecutionReceipt() {
    Response deny = post(TENANT, body(BOOL_MODULE, FUNCTION, "read_customer_record", "agent-42"), null);
    Response pending = post(TENANT, body(DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
        "delete_customer_record", "agent-42"), null);

    assertThat(deny.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(pending.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    for (Response r : List.of(deny, pending)) {
      String id = r.jsonPath().getString("decisionId");
      assertThat(r.asString()).doesNotContain("executionReceipt");
      assertThat(receiptRows(id)).isZero();
      assertThat(actionHashColumn(id)).matches("[0-9a-f]{64}");
    }
  }

  /** Review Focus 1：显式 null 与缺键是不同文档，actionHash 不同，且各等于对自己发出的原文自算。 */
  @Test
  void actionHashBindsRawJsonNotRecord() {
    String missing = rawBody(ALLOW_MODULE, "{\"id\":\"a\"}", "{}");
    String explicitNull = rawBody(ALLOW_MODULE, "{\"id\":\"a\",\"type\":null}", "{}");

    Response r1 = postRaw(missing, "idem-raw-1-" + UUID.randomUUID());
    Response r2 = postRaw(explicitNull, "idem-raw-2-" + UUID.randomUUID());

    String h1 = r1.jsonPath().getString("executionReceipt.payload.actionHash");
    String h2 = r2.jsonPath().getString("executionReceipt.payload.actionHash");
    assertThat(h1).isEqualTo(ExecutionReceiptAssert.actionHashOf(missing));
    assertThat(h2).isEqualTo(ExecutionReceiptAssert.actionHashOf(explicitNull));
    assertThat(h1).isNotEqualTo(h2);
  }

  /** M5：同键第二次只在指纹外（principal.type 显式 null）不同 → 重放首次决策与收据，actionHash 是首次原文的哈希。 */
  @Test
  void replaySameKeyNullVsMissingPrincipalTypeReturnsFirstReceipt() {
    String key = "idem-m5-" + UUID.randomUUID();
    String first = rawBody(ALLOW_MODULE, "{\"id\":\"a\"}", "{}");
    String second = rawBody(ALLOW_MODULE, "{\"id\":\"a\",\"type\":null}", "{}");

    Response r1 = postRaw(first, key);
    Response r2 = postRaw(second, key);

    assertThat(r2.statusCode()).isEqualTo(200);
    assertThat(r2.jsonPath().getString("decisionId")).isEqualTo(r1.jsonPath().getString("decisionId"));
    assertThat(ExecutionReceiptAssert.tree(r2.asString()).get("executionReceipt"))
        .isEqualTo(ExecutionReceiptAssert.tree(r1.asString()).get("executionReceipt"));
    String hash = r2.jsonPath().getString("executionReceipt.payload.actionHash");
    assertThat(hash).isEqualTo(ExecutionReceiptAssert.actionHashOf(first))
        .isNotEqualTo(ExecutionReceiptAssert.actionHashOf(second));
  }

  /** context 内 null 与缺键改变 contextHash，属指纹差异 → 422。 */
  @Test
  void replaySameKeyNullVsMissingInContextIs422() {
    String key = "idem-ctx-" + UUID.randomUUID();

    Response r1 = postRaw(rawBody(ALLOW_MODULE, "{\"id\":\"a\"}", "{\"cc\":null}"), key);
    Response r2 = postRaw(rawBody(ALLOW_MODULE, "{\"id\":\"a\"}", "{}"), key);

    assertThat(r1.statusCode()).isEqualTo(200);
    assertThat(r2.statusCode()).isEqualTo(422);
    assertThat(r2.jsonPath().getString("error")).isEqualTo("idempotency_mismatch");
  }

  /** 同键重放与 GET 返回已存的同一份收据（不重签），收据行仍只有一行。 */
  @Test
  void replayReturnsSameStoredReceipt() {
    String key = "idem-receipt-" + UUID.randomUUID();

    Response first = post(TENANT, allowBody(), key);
    Response replay = post(TENANT, allowBody(), key);
    String id = first.jsonPath().getString("decisionId");

    JsonNode original = ExecutionReceiptAssert.assertVerifiable(first.asString(), signingKeys);
    assertThat(ExecutionReceiptAssert.tree(replay.asString()).get("executionReceipt")).isEqualTo(original);
    assertThat(ExecutionReceiptAssert.tree(get(TENANT, id).asString()).get("executionReceipt")).isEqualTo(original);
    assertThat(receiptRows(id)).isEqualTo(1);
  }

  @Test
  void 缺principal_id返回400_invalid_action() {
    Response resp = post(TENANT, body(ALLOW_MODULE, FUNCTION, "read_customer_record", null), null);

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
    assertThat(resp.jsonPath().getString("message")).isNotBlank();
  }

  @Test
  void 旧agent体无provider仍200且收据agentProvider为unknown() {
    Response resp = post(TENANT, allowBody(), null);

    assertThat(resp.statusCode()).isEqualTo(200);
    UUID id = UUID.fromString(resp.jsonPath().getString("decisionId"));
    String metadata = QuarkusTransaction.requiringNew().call(() -> {
      GuardDecisionEntity e = GuardDecisionEntity.findById(id);
      AuditLog row = AuditLog.findById(e.receiptAuditId);
      return row.metadata;
    });
    assertThat(metadata).contains("\"agentProvider\":\"unknown\"").contains("\"agentModel\":\"claude-fable-5-1\"")
        .contains("\"agentSession\":\"s-1\"").contains("\"agentSource\":\"declared\"");
  }

  /** 把请求体里的 agent 换成给定对象，其余字段不变。 */
  private static Map<String, Object> withAgent(Map<String, Object> agent) {
    Map<String, Object> req = new HashMap<>(allowBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> action = new HashMap<>((Map<String, Object>) req.get("action"));
    action.put("agent", agent);
    req.put("action", action);
    return req;
  }

  @Test
  void 显式provider返回200且收据写入该provider() {
    Response resp = post(TENANT, withAgent(Map.of("provider", "openai", "model", "gpt-5", "version", "2026-09",
        "session", "s-9")), null);

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    UUID id = UUID.fromString(resp.jsonPath().getString("decisionId"));
    String metadata = QuarkusTransaction.requiringNew().call(() -> {
      GuardDecisionEntity e = GuardDecisionEntity.findById(id);
      AuditLog row = AuditLog.findById(e.receiptAuditId);
      return row.metadata;
    });
    assertThat(metadata).contains("\"agentProvider\":\"openai\"").contains("\"agentModel\":\"gpt-5\"")
        .contains("\"agentVersion\":\"2026-09\"").contains("\"agentSession\":\"s-9\"")
        .contains("\"agentSource\":\"declared\"");
  }

  @Test
  void 有provider但model空白返回400_invalid_action() {
    Response resp = post(TENANT, withAgent(Map.of("provider", "openai", "model", " ")), null);

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
    assertThat(resp.jsonPath().getString("message")).contains("agent.model");
  }

  @Test
  void agent_model含控制字符返回400_invalid_action() {
    Map<String, Object> req = new HashMap<>(allowBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> action = new HashMap<>((Map<String, Object>) req.get("action"));
    action.put("agent", Map.of("model", "bad\u0001model", "session", "s"));
    req.put("action", action);

    Response resp = post(TENANT, req, null);

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_action");
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
  void 同键不同动作返回422_同键同体仍重放() {
    String key = "idem-mismatch-" + UUID.randomUUID();

    Response first = post(TENANT, allowBody(), key);
    Response otherAction = post(TENANT, body(ALLOW_MODULE, FUNCTION, "export_customer_record", "agent-42"), key);
    Response replay = post(TENANT, allowBody(), key);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(otherAction.statusCode()).isEqualTo(422);
    assertThat(otherAction.jsonPath().getString("error")).isEqualTo("idempotency_mismatch");
    assertThat(otherAction.jsonPath().getString("message")).isNotBlank();
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
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

  // ---- ADR 0040 §11 D3 / D4：签名事实验签 ----

  private static final ObjectMapper JSON = new ObjectMapper();

  /** allowBody() 的 action（context.amount = 250000）附上给定事实后的原文请求体。 */
  private static String withFacts(JsonNode... facts) {
    ObjectNode body = JSON.valueToTree(allowBody());
    var list = ((ObjectNode) body.get("action")).putArray("facts");
    for (JsonNode f : facts) {
      list.add(f);
    }
    return body.toString();
  }

  private static TestFactSigner amountFact(TestFactSigner signer) {
    Instant now = Instant.now();
    return signer.audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.amount\":250000}").sources("{\"context.amount\":\"ledger.amount\"}");
  }

  private void register(TestFactSigner signer) {
    var saved = factIssuerStore.upsert(TENANT, TestFactSigner.ISSUER, TestFactSigner.KID, signer.publicKey(), true, null,
        "user-guard-rest", Instant.now());
    assertThat(saved).isInstanceOf(FactIssuerStore.UpsertResult.Saved.class);
  }

  private static long auditRows() {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.count("tenantId", TENANT));
  }

  @Test
  void validFactIsAcceptedEndToEnd() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);

    Response resp = postRaw(withFacts(amountFact(signer).sign()));

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
  }

  /** 未知 kid、签名被篡改 → 400 invalid_fact（带 issuer / kid / reason），不评估、不落决策行、不写链收据。 */
  @Test
  void invalidFactIs400AndWritesNoReceipt() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    ObjectNode tampered = amountFact(signer).sign();
    ((ObjectNode) tampered.get("sources")).put("context.amount", "agent.selfReported");
    long auditBefore = auditRows();

    Response unknownKid = postRaw(withFacts(amountFact(TestFactSigner.newKey().kid("2026-12")).sign()));
    Response badSignature = postRaw(withFacts(tampered));

    assertThat(unknownKid.statusCode()).isEqualTo(400);
    assertThat(unknownKid.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(unknownKid.jsonPath().getString("issuer")).isEqualTo(TestFactSigner.ISSUER);
    assertThat(unknownKid.jsonPath().getString("kid")).isEqualTo("2026-12");
    assertThat(unknownKid.jsonPath().getString("reason")).isEqualTo("unknown_issuer");
    assertThat(badSignature.statusCode()).isEqualTo(400);
    assertThat(badSignature.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(badSignature.jsonPath().getString("reason")).isEqualTo("bad_signature");
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("tenantId", TENANT))).isZero();
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("tenantId", TENANT))).isZero();
    assertThat(auditRows()).isEqualTo(auditBefore);
  }

  /** facts 内的不安全整数报 invalid_fact（reason unsafe_integer），而不是笼统的 invalid_action。 */
  @Test
  void unsafeIntegerInFactIs400InvalidFact() {
    ObjectNode fact = amountFact(TestFactSigner.newKey()).sign();
    ((ObjectNode) fact.get("claims")).put("context.amount", 9_007_199_254_740_992L);

    Response resp = postRaw(withFacts(fact));

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(resp.jsonPath().getString("reason")).isEqualTo("unsafe_integer");
    assertThat(resp.jsonPath().getString("kid")).isEqualTo(TestFactSigner.KID);
  }

  /**
   * 验签先于幂等查键（ADR 0040 §11 D4）：同键首次无事实得 200，再带无效事实 → 400 而非重放首次决策；
   * 之后同键同体仍重放首次决策（无效请求没有占用或改写该键）。
   */
  @Test
  void invalidFactRejectedBeforeIdempotencyReplay() {
    String key = "idem-fact-" + UUID.randomUUID();
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    Response first = postRaw(withFacts(), key);
    ObjectNode expired = amountFact(signer).window(Instant.now().minusSeconds(1200), Instant.now().minusSeconds(600))
        .sign();

    Response withBadFact = postRaw(withFacts(expired), key);
    Response replay = postRaw(withFacts(), key);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(withBadFact.statusCode()).isEqualTo(400);
    assertThat(withBadFact.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(withBadFact.jsonPath().getString("reason")).isEqualTo("expired");
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("tenantId", TENANT))).isEqualTo(1);
  }

  // ---- ADR 0040 §11 D2 / D4、ADR 0041 §11 D1：逐字段来源进响应、决策行与收据 ----

  private static final String ISSUER_KID = TestFactSigner.ISSUER + "#" + TestFactSigner.KID;

  private static GuardDecisionEntity decisionRow(String decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(
        UUID.fromString(decisionId)));
  }

  /** 决策收据（链上 ACTION_GUARD_DECISION 行）的 metadata。 */
  private static JsonNode receiptMetadata(Response resp) throws Exception {
    long auditId = resp.jsonPath().getLong("receipt.auditId");
    AuditLog row = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>findById(auditId));
    return JSON.readTree(row.metadata);
  }

  @Test
  void validFactMarksVerifiedInResponseRowAndReceipt() throws Exception {
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    ObjectNode fact = amountFact(signer).sign();

    Response resp = postRaw(withFacts(fact));

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
    assertThat(resp.jsonPath().getList("inputSources.factIssuers")).containsExactly(ISSUER_KID);
    assertThat(resp.jsonPath().getMap("inputSources")).doesNotContainKey("factsStale");
    GuardDecisionEntity row = decisionRow(resp.jsonPath().getString("decisionId"));
    assertThat(JSON.readTree(row.verifiedFields)).isEqualTo(JSON.readTree("[\"context.amount\"]"));
    assertThat(JSON.readTree(row.factIssuers)).isEqualTo(JSON.readTree("[\"" + ISSUER_KID + "\"]"));
    assertThat(row.factExpiresAt).isEqualTo(Instant.parse(fact.get("expiresAt").asText()));
    JsonNode md = receiptMetadata(resp);
    assertThat(md.get("verifiedFields")).isEqualTo(JSON.readTree("[\"context.amount\"]"));
    assertThat(md.get("factIssuers")).isEqualTo(JSON.readTree("[\"" + ISSUER_KID + "\"]"));
    assertThat(md.get("factSources")).isEqualTo(JSON.readTree("[\"context.amount=ledger.amount\"]"));
    assertThat(md.get("factExpiresAt").asText()).isEqualTo(Instant.parse(fact.get("expiresAt").asText()).toString());
    // principal.type 为 agent：服务端解析不适用，不写 principalIdentityMatched
    assertThat(md.has("principalIdentityMatched")).isFalse();
    assertThat(md.has("inputSourcesTruncated")).isFalse();
  }

  @Test
  void noFactsAgentPrincipalHasNoInputSources() throws Exception {
    Response resp = post(TENANT, allowBody(), null);

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getMap("$")).doesNotContainKey("inputSources");
    GuardDecisionEntity row = decisionRow(resp.jsonPath().getString("decisionId"));
    assertThat(row.verifiedFields).isNull();
    assertThat(row.factIssuers).isNull();
    assertThat(row.factExpiresAt).isNull();
    JsonNode md = receiptMetadata(resp);
    for (String key : List.of("verifiedFields", "factIssuers", "factSources", "principalIdentityMatched", "factExpiresAt",
        "inputSourcesTruncated")) {
      assertThat(md.has(key)).as(key).isFalse();
    }
  }

  /** principal.type=user 且 id 等于提交者（X-User-Id），roles ⊆ 已验证业务角色：两者 verified，收据记 identityMatched。 */
  @Test
  void userPrincipalEqualToPerformedByIsVerifiedEndToEnd() throws Exception {
    ObjectNode body = JSON.valueToTree(allowBody());
    ((ObjectNode) body.get("action")).set("principal",
        JSON.readTree("{\"id\":\"user-guard-rest\",\"type\":\"user\",\"roles\":[\"ops\"]}"));

    Response resp = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-rest")
        .header("X-User-Business-Roles", "ops, finance")
        .contentType(ContentType.JSON)
        .body(body.toString().getBytes(StandardCharsets.UTF_8))
        .when().post("/api/v1/guard/decisions");
    Response otherUser = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "someone-else")
        .contentType(ContentType.JSON)
        .body(body.toString().getBytes(StandardCharsets.UTF_8))
        .when().post("/api/v1/guard/decisions");

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getList("inputSources.verified")).containsExactly("principal.id", "principal.roles");
    assertThat(resp.jsonPath().getMap("inputSources")).doesNotContainKey("factIssuers");
    assertThat(receiptMetadata(resp).get("principalIdentityMatched").asBoolean()).isTrue();
    assertThat(otherUser.statusCode()).isEqualTo(200);
    assertThat(otherUser.jsonPath().getMap("$")).doesNotContainKey("inputSources");
    assertThat(receiptMetadata(otherUser).get("principalIdentityMatched").asBoolean()).isFalse();
  }

  /**
   * 幂等指纹不含 facts（ADR 0040 §11.3 第 3 条）：同键换一组有效事实 → 200 重放首次决策，且报告的是<b>首次</b>
   * 决策存储的 inputSources，而不是本次请求的事实；同键不带事实同样重放。
   */
  @Test
  void sameKeyDifferentFactsIsNot422() {
    String key = "idem-facts-" + UUID.randomUUID();
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    Instant now = Instant.now();
    ObjectNode resourceFact = signer.fresh().audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"resource.id\":\"C-1001\"}").sources("{\"resource.id\":\"crm.customerId\"}").sign();

    Response first = postRaw(withFacts(amountFact(signer).sign()), key);
    Response otherFacts = postRaw(withFacts(resourceFact), key);
    Response noFacts = postRaw(withFacts(), key);

    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    for (Response replay : List.of(otherFacts, noFacts)) {
      assertThat(replay.statusCode()).as(replay.asString()).isEqualTo(200);
      assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
      assertThat(replay.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
      assertThat(replay.asString()).isEqualTo(first.asString());
    }
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("tenantId", TENANT))).isEqualTo(1);
  }

  /**
   * 首次记录的签发方被禁用后：同键重放与 GET 回读都追加 inputSources.factsStale=true，effectiveOutcome 不变；
   * 不写新事件、不改写 response_json。
   */
  @Test
  void replayAfterIssuerDisabledMarksStaleWithoutNewEvent() {
    String key = "idem-stale-" + UUID.randomUUID();
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    Response first = postRaw(withFacts(amountFact(signer).sign()), key);
    String decisionId = first.jsonPath().getString("decisionId");
    String storedJson = decisionRow(decisionId).responseJson;
    assertThat(factIssuerStore.markRevoked(TENANT, TestFactSigner.ISSUER, TestFactSigner.KID, "user-guard-rest", Instant.now())).isTrue();
    long auditBefore = auditRows();

    Response replay = postRaw(withFacts(), key);
    Response read = get(TENANT, decisionId);

    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    for (Response r : List.of(replay, read)) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
      assertThat(r.jsonPath().getString("decisionId")).isEqualTo(decisionId);
      assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
      assertThat(r.jsonPath().getBoolean("inputSources.factsStale")).isTrue();
      assertThat(r.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
    }
    assertThat(first.jsonPath().getMap("inputSources")).doesNotContainKey("factsStale");
    assertThat(auditRows()).isEqualTo(auditBefore);
    assertThat(decisionRow(decisionId).responseJson).isEqualTo(storedJson);
  }

  /** 点路径按嵌套解释：context 里与 claim 路径相撞的含 '.' 字面键 → 400 invalid_fact（ambiguous_path）。 */
  @Test
  void dottedContextKeyUnderClaimedPathIs400() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    ObjectNode body = (ObjectNode) readTree(withFacts(amountFact(signer).sign()));
    ((ObjectNode) body.get("action")).set("context", readTree("{\"amount\":250000,\"amount.currency\":\"EUR\"}"));
    ObjectNode unrelated = (ObjectNode) readTree(withFacts(amountFact(signer).sign()));
    ((ObjectNode) unrelated.get("action")).set("context", readTree("{\"amount\":250000,\"note.text\":\"x\"}"));

    Response resp = postRaw(body.toString());
    Response ok = postRaw(unrelated.toString());

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(resp.jsonPath().getString("reason")).isEqualTo("ambiguous_path");
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
  }

  /** principal.id 写成数字：Jackson 会强转为 "42"，claim 不能为它背书 → 400 invalid_fact（claim_type_mismatch）。 */
  @Test
  void numericClaimOnTypedStringFieldIs400() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(signer);
    Instant now = Instant.now();
    ObjectNode fact = signer.fresh().audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"principal.id\":42}").sources("{\"principal.id\":\"idp.sub\"}").sign();
    ObjectNode body = (ObjectNode) readTree(withFacts(fact));
    ((ObjectNode) ((ObjectNode) body.get("action")).get("principal")).put("id", 42);

    Response resp = postRaw(body.toString());

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(resp.jsonPath().getString("reason")).isEqualTo("claim_type_mismatch");
  }

  private static JsonNode readTree(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(json, e);
    }
  }
}
