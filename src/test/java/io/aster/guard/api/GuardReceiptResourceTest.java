package io.aster.guard.api;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §12 D5：{@code POST /api/v1/guard/receipts/{decisionId}/consume} 端到端（真实规则、审计链与 Postgres 行锁）。
 * 测试配置下 apikey / rbac 过滤器关闭，身份只靠 {@code X-Tenant-Id} 与 {@code X-User-Id}；
 * ALLOW 决策一律先经 {@code POST /api/v1/guard/decisions} 取得，执行方哈希按请求体原文自算。
 */
@QuarkusTest
class GuardReceiptResourceTest {
  /** 每次 JVM 用独立租户：审计链只追加不删除，避免与历史运行互相干扰。 */
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-consume-" + RUN;
  private static final String OTHER_TENANT = "tenant-guard-consume-other-" + RUN;
  private static final String BOOL_MODULE = "guard.consume.bool";
  private static final String BOOL_SOURCE = String.join("\n",
      "Module guard.consume.bool.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.",
      "", "Rule decide given req as GuardRequest, produce Bool:", "  Return true.");
  private static final String DPO = "Data Protection Officer";
  private static final String ATTEMPT = "ACTION_GUARD_CONSUME_ATTEMPT";
  private static final String OUTCOME = "ACTION_GUARD_CONSUME_OUTCOME";

  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void setUp() {
    QuarkusTransaction.requiringNew().run(() -> List.of(TENANT, OTHER_TENANT).forEach(tenant -> {
      GuardApprovalEntity.delete("tenantId = ?1", tenant);
      GuardReceiptEntity.delete("tenantId = ?1", tenant);
      GuardDecisionEntity.delete("tenantId = ?1", tenant);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
      PolicyVersion.delete("tenantId = ?1", tenant);
      PolicyCatalog.delete("tenantId = ?1", tenant);
    }));
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    for (String tenant : List.of(TENANT, OTHER_TENANT)) {
      DeployedMapParamProbeTest.deploy(tenant, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
          DeployedMapParamProbeTest.SOURCE);
      DeployedMapParamProbeTest.deploy(tenant, BOOL_MODULE, "decide", BOOL_SOURCE);
    }
  }

  // ---- 辅助 ----

  /** 探针策略：delete_customer_record → REQUIRE_APPROVAL，其余动作 → ALLOW。 */
  static String decisionBody(String module, String actionName, long amount) {
    return "{\"policyModule\":\"" + module + "\",\"policyFunction\":\"decide\",\"action\":{"
        + "\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\",\"roles\":[\"ops-bot\"]},"
        + "\"action\":{\"name\":\"" + actionName + "\"},\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"},"
        + "\"context\":{\"amount\":" + amount + "}}}";
  }

  static String allowBody(long amount) {
    return decisionBody(DeployedMapParamProbeTest.MODULE, "read_customer_record", amount);
  }

  private static Response postDecision(String tenant, String body) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-guard-consume")
        .contentType(ContentType.JSON).body(body).when().post("/api/v1/guard/decisions");
  }

  /** 开一条 ALLOW 决策，返回 decisionId；actionHash 由调用方按同一原文自算。 */
  private static String allowDecision(String body) {
    Response r = postDecision(TENANT, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return r.jsonPath().getString("decisionId");
  }

  static String consumeBody(String actionHash, String attemptId, String executorId) {
    return "{\"actionHash\":\"" + actionHash + "\",\"attemptId\":\"" + attemptId + "\",\"executorId\":\""
        + executorId + "\"}";
  }

  private static Response consume(String tenant, String decisionId, String body) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "user-guard-executor")
        .contentType(ContentType.JSON).body(body)
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");
  }

  private static Response consume(String decisionId, String actionHash, String attemptId, String executorId) {
    return consume(TENANT, decisionId, consumeBody(actionHash, attemptId, executorId));
  }

  private static GuardReceiptEntity receiptRow(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.<GuardReceiptEntity>findById(id));
  }

  static List<AuditLog> events(String decisionId, String type) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>list(
        "decisionId = ?1 and eventType = ?2 order by id", decisionId, type));
  }

  // ---- 用例 ----

  @Test
  void firstConsume200WritesRowAndTwoEvents() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    String attemptId = UUID.randomUUID().toString();
    Response decision = given().header("X-Tenant-Id", TENANT).when().get("/api/v1/guard/decisions/" + decisionId);

    Response r = consume(decisionId, ExecutionReceiptAssert.actionHashOf(body), attemptId, "aster-guard-mcp@host:1");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("decisionId")).isEqualTo(decisionId);
    assertThat(ExecutionReceiptAssert.tree(r.asString()).get("executionReceipt"))
        .isEqualTo(ExecutionReceiptAssert.tree(decision.asString()).get("executionReceipt"));
    assertThat(r.asString()).startsWith("{\"consumedAt\":");
    GuardReceiptEntity row = receiptRow(decisionId);
    assertThat(row.consumedAt).isEqualTo(Instant.parse(r.jsonPath().getString("consumedAt")));
    assertThat(row.attemptId).isEqualTo(attemptId);
    assertThat(row.executorId).isEqualTo("aster-guard-mcp@host:1");
    List<AuditLog> attempts = events(decisionId, ATTEMPT);
    List<AuditLog> outcomes = events(decisionId, OUTCOME);
    assertThat(attempts).hasSize(1);
    assertThat(outcomes).hasSize(1);
    assertThat(row.consumeAuditId).isEqualTo(attempts.get(0).id);
    assertThat(attempts.get(0).performedBy).isEqualTo("user-guard-executor");
    assertThat(attempts.get(0).metadata).contains("\"attemptId\":\"" + attemptId + "\"");
    assertThat(outcomes.get(0).id).isGreaterThan(attempts.get(0).id);
    assertThat(outcomes.get(0).metadata).contains("\"outcome\":\"consumed\"")
        .contains("\"attemptReceiptHash\":\"" + attempts.get(0).currentHash + "\"");
    assertThat(outcomes.get(0).success).isTrue();
  }

  @Test
  void changedParameterIs409ActionMismatch() {
    String decisionId = allowDecision(allowBody(250000));

    Response r = consume(decisionId, ExecutionReceiptAssert.actionHashOf(allowBody(250001)),
        UUID.randomUUID().toString(), "exec-1");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.asString()).isEqualTo("{\"error\":\"action_mismatch\"}");
    GuardReceiptEntity row = receiptRow(decisionId);
    assertThat(row.consumedAt).isNull();
    assertThat(row.consumeAuditId).isNull();
    assertThat(events(decisionId, ATTEMPT)).hasSize(1);
    List<AuditLog> outcomes = events(decisionId, OUTCOME);
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0).metadata).contains("\"outcome\":\"rejected:action_mismatch\"");
    assertThat(outcomes.get(0).success).isFalse();
  }

  @Test
  void secondConsumeSameOrNewAttemptIs409Consumed() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    String hash = ExecutionReceiptAssert.actionHashOf(body);
    String attemptId = UUID.randomUUID().toString();
    Response first = consume(decisionId, hash, attemptId, "exec-first");
    assertThat(first.statusCode()).isEqualTo(200);

    for (String retryAttempt : List.of(attemptId, UUID.randomUUID().toString())) {
      Response again = consume(decisionId, hash, retryAttempt, "exec-second");
      assertThat(again.statusCode()).as(again.asString()).isEqualTo(409);
      assertThat(again.jsonPath().getString("error")).isEqualTo("receipt_consumed");
      assertThat(again.jsonPath().getString("consumedAt")).isEqualTo(first.jsonPath().getString("consumedAt"));
      assertThat(again.jsonPath().getString("executorId")).isEqualTo("exec-first");
      assertThat(again.jsonPath().getString("attemptId")).isEqualTo(attemptId);
    }
    List<AuditLog> attempts = events(decisionId, ATTEMPT);
    assertThat(attempts).hasSize(3);
    assertThat(receiptRow(decisionId).consumeAuditId).isEqualTo(attempts.get(0).id);
    assertThat(events(decisionId, OUTCOME)).extracting(e -> e.metadata)
        .anySatisfy(m -> assertThat(m).contains("rejected:receipt_consumed"));
  }

  /** 时间窗排他：now == expiresAt → 409；expiresAt − 1ms → 200（另一决策）。 */
  @Test
  void expiredIs409() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    GuardApprovalResourceTest.MutableClock clock = new GuardApprovalResourceTest.MutableClock(t0);
    QuarkusMock.installMockForType(clock, Clock.class);
    String body = allowBody(250000);
    String hash = ExecutionReceiptAssert.actionHashOf(body);
    String expiredId = allowDecision(body);
    String liveId = allowDecision(body);
    Instant expiresAt = receiptRow(expiredId).expiresAt;
    assertThat(expiresAt).isEqualTo(t0.plus(Duration.ofMinutes(5)));

    clock.set(expiresAt);
    Response expired = consume(expiredId, hash, UUID.randomUUID().toString(), "exec-1");
    assertThat(expired.statusCode()).as(expired.asString()).isEqualTo(409);
    assertThat(expired.jsonPath().getString("error")).isEqualTo("receipt_expired");
    assertThat(Instant.parse(expired.jsonPath().getString("expiresAt"))).isEqualTo(expiresAt);
    assertThat(receiptRow(expiredId).consumedAt).isNull();

    clock.set(receiptRow(liveId).expiresAt.minusMillis(1));
    Response live = consume(liveId, hash, UUID.randomUUID().toString(), "exec-1");
    assertThat(live.statusCode()).as(live.asString()).isEqualTo(200);
  }

  @Test
  void denyPendingRejectedAre409NotAllowed() {
    Response deny = postDecision(TENANT, decisionBody(BOOL_MODULE, "read_customer_record", 1));
    Response pending = postDecision(TENANT,
        decisionBody(DeployedMapParamProbeTest.MODULE, "delete_customer_record", 1));
    Response toReject = postDecision(TENANT,
        decisionBody(DeployedMapParamProbeTest.MODULE, "delete_customer_record", 2));
    Response rejected = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "dpo-1")
        .header("X-User-Business-Roles", DPO).contentType(ContentType.JSON).body(Map.of("comment", "不批"))
        .when().post("/api/v1/guard/approvals/" + toReject.jsonPath().getString("approval.id") + "/reject");
    assertThat(rejected.statusCode()).as(rejected.asString()).isEqualTo(200);

    Map<String, String> expected = Map.of(
        deny.jsonPath().getString("decisionId"), "DENY",
        pending.jsonPath().getString("decisionId"), "PENDING",
        toReject.jsonPath().getString("decisionId"), "DENY");
    expected.forEach((decisionId, effective) -> {
      Response r = consume(decisionId, "a".repeat(64), UUID.randomUUID().toString(), "exec-1");
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
      assertThat(r.asString()).isEqualTo("{\"error\":\"not_allowed\",\"effectiveOutcome\":\"" + effective + "\"}");
      assertThat(events(decisionId, OUTCOME)).singleElement()
          .satisfies(e -> assertThat(e.metadata).contains("\"outcome\":\"rejected:not_allowed\""));
    });
  }

  /** V6.35.0 之前的 ALLOW：决策行无 action_hash、无收据行（直接构造该形态）→ 409 not_allowed + legacy。 */
  @Test
  void legacyAllowWithoutRowIs409NotAllowedLegacy() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    UUID id = UUID.fromString(decisionId);
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity.deleteById(id);
      GuardDecisionEntity.update("actionHash = null where id = ?1", id);
    });

    Response r = consume(decisionId, ExecutionReceiptAssert.actionHashOf(body), UUID.randomUUID().toString(), "e");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.asString()).isEqualTo("{\"error\":\"not_allowed\",\"effectiveOutcome\":\"ALLOW\",\"legacy\":true}");
  }

  /**
   * 退役（retired）与撤销区分（ADR 0040 §12 D6、runbook §4）：收据的 kid 已从配置中删除但仍在 TTL 内 → 照常 200。
   * 配置中不存在的 kid 以改写收据行模拟（consume 不验签，只看行与 kid 是否被撤销）；撤销对照见
   * {@link GuardReceiptResourceAuthTest#revokedKidIs409WithRevision}。
   */
  @Test
  void retiredKidRemovedFromConfigStillConsumable() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    UUID id = UUID.fromString(decisionId);
    QuarkusTransaction.requiringNew().run(() -> GuardReceiptEntity.update("kid = 'test-retired' where decisionId = ?1", id));

    Response r = consume(decisionId, ExecutionReceiptAssert.actionHashOf(body), UUID.randomUUID().toString(), "e");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(receiptRow(decisionId).consumedAt).isNotNull();
  }

  /** 已消耗 + 过期 + 错哈希 → receipt_consumed；未消耗 + 过期 + 错哈希 → receipt_expired。 */
  @Test
  void orderIsConsumedBeforeExpiredBeforeMismatch() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    GuardApprovalResourceTest.MutableClock clock = new GuardApprovalResourceTest.MutableClock(t0);
    QuarkusMock.installMockForType(clock, Clock.class);
    String body = allowBody(250000);
    String hash = ExecutionReceiptAssert.actionHashOf(body);
    String consumedId = allowDecision(body);
    String freshId = allowDecision(body);
    assertThat(consume(consumedId, hash, UUID.randomUUID().toString(), "e").statusCode()).isEqualTo(200);

    clock.set(t0.plus(Duration.ofHours(1)));
    String wrong = "b".repeat(64);
    Response consumed = consume(consumedId, wrong, UUID.randomUUID().toString(), "e");
    Response expired = consume(freshId, wrong, UUID.randomUUID().toString(), "e");

    assertThat(consumed.jsonPath().getString("error")).isEqualTo("receipt_consumed");
    assertThat(expired.jsonPath().getString("error")).isEqualTo("receipt_expired");
  }

  @Test
  void crossTenantAndBadUuidAre404() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    String good = consumeBody(ExecutionReceiptAssert.actionHashOf(body), UUID.randomUUID().toString(), "e");

    for (Response r : List.of(consume(OTHER_TENANT, decisionId, good), consume(TENANT, "not-a-uuid", good),
        consume(TENANT, UUID.randomUUID().toString(), good))) {
      assertThat(r.statusCode()).isEqualTo(404);
      assertThat(r.asString()).isEmpty();
    }
    assertThat(receiptRow(decisionId).consumedAt).isNull();
    assertThat(events(decisionId, ATTEMPT)).isEmpty();
  }

  @Test
  void invalidBodyIs400() {
    String decisionId = allowDecision(allowBody(250000));
    String hash = "c".repeat(64);
    String attempt = UUID.randomUUID().toString();
    Map<String, String> cases = new java.util.LinkedHashMap<>();
    cases.put(consumeBody("c".repeat(63), attempt, "e"), "actionHash");
    cases.put(consumeBody("C".repeat(64), attempt, "e"), "actionHash");
    cases.put("{\"attemptId\":\"" + attempt + "\",\"executorId\":\"e\"}", "actionHash");
    cases.put(consumeBody(hash, "not-a-uuid", "e"), "attemptId");
    cases.put(consumeBody(hash, attempt, "line\\nbreak"), "executorId");
    cases.put(consumeBody(hash, attempt, "x".repeat(256)), "executorId");
    cases.put(consumeBody(hash, attempt, ""), "executorId");
    cases.forEach((body, field) -> {
      Response r = consume(TENANT, decisionId, body);
      assertThat(r.statusCode()).as(body).isEqualTo(400);
      assertThat(r.asString()).as(body).isEqualTo("{\"error\":\"invalid_consume\",\"field\":\"" + field + "\"}");
    });
    // 255 字符恰好合法（边界），且哈希不对 → 进入判定
    assertThat(consume(decisionId, hash, attempt, "x".repeat(255)).jsonPath().getString("error"))
        .isEqualTo("action_mismatch");
    // 重复键与非法 JSON：400 invalid_consume，不进入判定
    for (String body : List.of("{\"actionHash\":\"" + hash + "\",\"actionHash\":\"" + hash + "\",\"attemptId\":\""
        + attempt + "\",\"executorId\":\"e\"}", "{not json", "{\"actionHash\":{\"x\":1}}")) {
      Response r = consume(TENANT, decisionId, body);
      assertThat(r.statusCode()).as(body).isEqualTo(400);
      assertThat(r.jsonPath().getString("error")).as(body).isEqualTo("invalid_consume");
    }
    assertThat(events(decisionId, ATTEMPT)).hasSize(1);
  }

  /** 审批转 ALLOW 签发的收据可 consume（不是 legacy）；第二次 consume 409 receipt_consumed。 */
  @Test
  void approvedDecisionReceiptConsumable() {
    String body = decisionBody(DeployedMapParamProbeTest.MODULE, "delete_customer_record", 250000);
    Response pending = postDecision(TENANT, body);
    assertThat(pending.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    String decisionId = pending.jsonPath().getString("decisionId");
    Response approved = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "dpo-1")
        .header("X-User-Business-Roles", DPO).contentType(ContentType.JSON).body(Map.of())
        .when().post("/api/v1/guard/approvals/" + pending.jsonPath().getString("approval.id") + "/approve");
    assertThat(approved.statusCode()).as(approved.asString()).isEqualTo(200);
    assertThat(approved.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    String hash = ExecutionReceiptAssert.actionHashOf(body);

    Response first = consume(decisionId, hash, UUID.randomUUID().toString(), "exec-approved");

    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    assertThat(first.asString()).doesNotContain("legacy");
    assertThat(ExecutionReceiptAssert.tree(first.asString()).get("executionReceipt"))
        .isEqualTo(ExecutionReceiptAssert.tree(approved.asString()).get("executionReceipt"));
    Response second = consume(decisionId, hash, UUID.randomUUID().toString(), "exec-approved");
    assertThat(second.statusCode()).as(second.asString()).isEqualTo(409);
    assertThat(second.jsonPath().getString("error")).isEqualTo("receipt_consumed");
    assertThat(second.jsonPath().getString("executorId")).isEqualTo("exec-approved");
  }

  /** 尝试事件追加失败 → 503，响应体逐字为 {"error":"receipt_unavailable"}，收据行不动。 */
  @Test
  void attemptAppendFailureIs503ReceiptUnavailable() {
    String body = allowBody(250000);
    String decisionId = allowDecision(body);
    AuditChainAppender failing = Mockito.mock(AuditChainAppender.class);
    Mockito.when(failing.appendSync(Mockito.any()))
        .thenThrow(new AuditChainAppender.AuditAppendException("模拟审计链不可用", null));
    QuarkusMock.installMockForType(failing, AuditChainAppender.class);

    Response r = consume(decisionId, ExecutionReceiptAssert.actionHashOf(body), UUID.randomUUID().toString(), "e");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(503);
    assertThat(r.asString()).isEqualTo("{\"error\":\"receipt_unavailable\"}");
    assertThat(receiptRow(decisionId).consumedAt).isNull();
    assertThat(events(decisionId, ATTEMPT)).isEmpty();
  }

  /** ADR 0040 §12 D5：consume 与 /decisions 同一计量口径与端点标签。 */
  @Test
  void consumeMeteredUnderDecisionsLabel() {
    assertThat(GuardReceiptResource.CONSUME_METER_PATH).isEqualTo("/api/v1/guard/decisions");
  }
}
