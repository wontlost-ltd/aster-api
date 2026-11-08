package io.aster.audit.rest;

import io.aster.audit.chain.AuditChainAnchorService;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 收据查询接口（ADR 0041 §2.4）：按相关键 / 决策 ID 返回链收据，租户隔离，参数校验。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class AuditReceiptResourceTest {
  static final String TENANT = "tenant-receipt-api";
  static final String OTHER_TENANT = "tenant-receipt-api-other";
  @Inject AuditChainAppender appender;
  @Inject BlockingDbTestHelper db;
  @Inject GuardReceiptBuilder builder;
  @Inject AuditChainAnchorService anchorService;
  String decisionHash;

  @BeforeEach void setUp() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity.delete("tenantId", TENANT);
      GuardDecisionEntity.delete("tenantId", TENANT);
    });
    db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id IN ('" + TENANT + "','" + OTHER_TENANT + "')");
    appender.appendSync(event(TENANT, EventType.POLICY_EVALUATION, Map.of("correlationId", "c1")));
    decisionHash = appender.appendSync(event(TENANT, EventType.ACTION_GUARD_DECISION, Map.of("decisionId", "d1"))).currentHash();
    Map<String, Object> approval = new LinkedHashMap<>();
    approval.put("decisionId", "d1");
    approval.put("outcome", "APPROVED");
    approval.put("decidedBy", "u3");
    approval.put("requiredRole", "R");
    approval.put("decisionReceiptHash", decisionHash);
    appender.appendSync(event(TENANT, EventType.ACTION_GUARD_APPROVAL, approval));
    appender.appendSync(event(OTHER_TENANT, EventType.POLICY_EVALUATION, Map.of("correlationId", "c1")));
  }

  static AuditEvent event(String tenant, EventType type, Map<String, Object> metadata) {
    return new AuditEvent(type, Instant.now(), tenant, "m", "f", null,
        null, null, "u", true, 5L, null, metadata, null, null, null, null);
  }

  static RequestSpecification as(String tenant) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "u");
  }

  @Test void 按相关键返回收据与missing() {
    JsonPath r = as(TENANT).queryParam("correlationIds", "c1,c-none")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("receipts")).hasSize(1);
    assertThat(r.getString("receipts[0].correlationId")).isEqualTo("c1");
    assertThat(r.getString("receipts[0].currentHash")).hasSize(64);
    assertThat(r.getList("missing")).containsExactly("c-none");
  }

  @Test void 按决策id返回决策收据与审批事件() {
    JsonPath r = as(TENANT).queryParam("decisionIds", "d1,d-none")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("receipts")).hasSize(1);
    assertThat(r.getString("receipts[0].decisionId")).isEqualTo("d1");
    assertThat(r.getString("receipts[0].eventType")).isEqualTo("ACTION_GUARD_DECISION");
    assertThat(r.getList("approvals")).hasSize(1);
    assertThat(r.getString("approvals[0].decidedBy")).isEqualTo("u3");
    assertThat(r.getString("approvals[0].outcome")).isEqualTo("APPROVED");
    assertThat(r.getString("approvals[0].requiredRole")).isEqualTo("R");
    assertThat(Instant.parse(r.getString("approvals[0].decidedAt"))).isBeforeOrEqualTo(Instant.now());
    assertThat(r.getString("approvals[0].decisionReceiptHash")).isEqualTo(r.getString("receipts[0].currentHash"));
    assertThat(r.getList("missing")).containsExactly("d-none");
  }

  @Test void 无决策收据的审批行不返回_id只出现在missing() {
    Map<String, Object> orphan = new LinkedHashMap<>();
    orphan.put("decisionId", "d-orphan");
    orphan.put("outcome", "APPROVED");
    appender.appendSync(event(TENANT, EventType.ACTION_GUARD_APPROVAL, orphan));
    JsonPath r = as(TENANT).queryParam("decisionIds", "d1,d-orphan")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("approvals.decisionId")).containsExactly("d1");
    assertThat(r.getList("missing")).containsExactly("d-orphan");
  }

  @Test void 其他租户的相关键报missing不泄露() {
    JsonPath r = as("tenant-receipt-api-stranger").queryParam("correlationIds", "c1")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("receipts")).isNull();
    assertThat(r.getList("missing")).containsExactly("c1");
  }

  @Test void 两参同时给400_超过200个400() {
    as(TENANT).queryParam("correlationIds", "c1").queryParam("decisionIds", "d1")
        .get("/api/v1/audit/receipts").then().statusCode(400)
        .body("error", org.hamcrest.Matchers.equalTo("exactly_one_of_correlationIds_or_decisionIds"));
    as(TENANT).get("/api/v1/audit/receipts").then().statusCode(400);
    String many = IntStream.range(0, 201).mapToObj(i -> "x" + i).collect(Collectors.joining(","));
    as(TENANT).queryParam("correlationIds", many).get("/api/v1/audit/receipts").then().statusCode(400);
  }

  @Test void 旧截断metadata返回_raw() {
    String raw = "{\"a\":1,\"correlationId\":\"c-old\",\"tru";
    db.executeAsAuditMaintenance("INSERT INTO audit_logs (event_type, timestamp, tenant_id, success, hash_version, correlation_id, metadata) "
        + "VALUES ('POLICY_EVALUATION', now(), '" + TENANT + "', true, 1, 'c-old', '" + raw.replace("'", "''") + "')");
    JsonPath r = as(TENANT).queryParam("correlationIds", "c-old")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getString("receipts[0].metadata._raw")).isEqualTo(raw);
  }

  // ---- 执行收据 consume / result 事件（ADR 0041 §12 D2） ----

  /** 插入决策行 + 收据行；authoritative* 为 null 表示尚未消耗 / 回写。 */
  GuardReceiptEntity receiptRow(UUID id, Long consumeAuditId, Long resultAuditId) {
    return QuarkusTransaction.requiringNew().call(() -> {
      GuardDecisionEntity d = new GuardDecisionEntity();
      d.id = id; d.tenantId = TENANT; d.policyModule = "m"; d.policyFunction = "f";
      d.principalId = "p"; d.actionName = "a"; d.contextHash = "h"; d.outcome = "ALLOW";
      d.responseJson = "{}";
      d.persist();
      GuardReceiptEntity r = new GuardReceiptEntity();
      r.decisionId = id; r.tenantId = TENANT; r.actionHash = "a".repeat(64); r.kid = "k1";
      r.issuedAt = Instant.now(); r.expiresAt = Instant.now().plusSeconds(300); r.signature = "sig";
      r.consumeAuditId = consumeAuditId;
      r.consumedAt = consumeAuditId == null ? null : Instant.now();
      r.resultAuditId = resultAuditId;
      r.resultStatus = resultAuditId == null ? null : "executed";
      r.persist();
      return r;
    });
  }

  void pointRow(UUID id, Long consumeAuditId, Long resultAuditId) {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity r = GuardReceiptEntity.findById(id);
      r.consumeAuditId = consumeAuditId;
      r.consumedAt = Instant.now();
      r.resultAuditId = resultAuditId;
      r.resultStatus = resultAuditId == null ? null : "executed";
    });
  }

  /** 追加尝试事件，返回审计行 id 与哈希。 */
  AuditChainAppender.AppendedRecord attempt(GuardReceiptEntity row, String attemptId, String executor) {
    return appender.appendSync(builder.consumeAttemptEvent(row, row.actionHash, attemptId, executor, "dh", "u"));
  }

  AuditChainAppender.AppendedRecord outcome(UUID id, String attemptId, String executor, String outcome, String attemptHash) {
    return appender.appendSync(builder.consumeOutcomeEvent(TENANT, id, attemptId, executor, outcome, attemptHash, "u"));
  }

  AuditChainAppender.AppendedRecord result(UUID id, String attemptId, String executor, String consumeHash) {
    return appender.appendSync(builder.resultEvent(TENANT, id, new ResultRequest(attemptId, executor, "executed",
        "2026-11-08T10:00:00Z", "b".repeat(64), null), consumeHash, "u"));
  }

  JsonPath query(UUID id) {
    return as(TENANT).queryParam("decisionIds", id.toString())
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
  }

  /** 写一条最小决策事件作为 receipts[] 命中行。 */
  void decisionRow(UUID id) {
    appender.appendSync(event(TENANT, EventType.ACTION_GUARD_DECISION, Map.of("decisionId", id.toString())));
  }

  @Test void consumesMarkAuthoritativeFromRowPointer() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    decisionRow(id);
    var a1 = attempt(row, "att-1", "ex-1");
    var a2 = attempt(row, "att-2", "ex-2");
    outcome(id, "att-1", "ex-1", "consumed", a1.currentHash());
    outcome(id, "att-2", "ex-2", "rejected:already_consumed", a2.currentHash());
    pointRow(id, a1.auditId(), null);

    JsonPath r = query(id);
    assertThat(r.getList("consumes")).hasSize(2);
    assertThat(r.getLong("receipts[0].authoritativeConsumeAuditId")).isEqualTo(a1.auditId());
    assertThat(r.getLong("consumes[0].auditId")).isEqualTo(a1.auditId());
    assertThat(r.getBoolean("consumes[0].authoritative")).isTrue();
    assertThat(r.getString("consumes[0].outcome")).isEqualTo("consumed");
    assertThat(r.getString("consumes[0].attemptId")).isEqualTo("att-1");
    assertThat(r.getString("consumes[0].executorId")).isEqualTo("ex-1");
    assertThat(r.getString("consumes[0].actionHash")).isEqualTo("a".repeat(64));
    assertThat(r.getString("consumes[0].currentHash")).isEqualTo(a1.currentHash());
    assertThat(r.getString("consumes[0].decisionId")).isEqualTo(id.toString());
    assertThat(r.getString("consumes[0].timestamp")).isNotNull();
    assertThat(r.getBoolean("consumes[1].authoritative")).isFalse();
    assertThat(r.getString("consumes[1].outcome")).isEqualTo("rejected:already_consumed");
  }

  @Test void missingOutcomeOnAuthoritativeIsCrashedUnknown() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    decisionRow(id);
    var a1 = attempt(row, "att-1", "ex-1");
    pointRow(id, a1.auditId(), null);
    JsonPath r = query(id);
    assertThat(r.getBoolean("consumes[0].authoritative")).isTrue();
    assertThat(r.getString("consumes[0].outcome")).isEqualTo("crashed-unknown");
  }

  @Test void missingOutcomeOnLoserIsUnknown() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    decisionRow(id);
    var a1 = attempt(row, "att-1", "ex-1");
    var a2 = attempt(row, "att-2", "ex-2");
    outcome(id, "att-1", "ex-1", "consumed", a1.currentHash());
    pointRow(id, a1.auditId(), null);
    JsonPath r = query(id);
    assertThat(r.getBoolean("consumes[1].authoritative")).isFalse();
    assertThat(r.getString("consumes[1].outcome")).isEqualTo("unknown");
  }

  @Test void noConsumeOrResultOmitsFields() {
    UUID id = UUID.randomUUID();
    receiptRow(id, null, null);
    decisionRow(id);
    JsonPath r = query(id);
    assertThat(r.getList("receipts")).hasSize(1);
    assertThat(r.getMap("")).doesNotContainKeys("consumes", "results");
    assertThat(r.getMap("receipts[0]")).doesNotContainKey("authoritativeConsumeAuditId");
  }

  @Test void consumeAuditIdLessThanResultAuditId() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    decisionRow(id);
    var a1 = attempt(row, "att-1", "ex-1");
    outcome(id, "att-1", "ex-1", "consumed", a1.currentHash());
    var res = result(id, "att-1", "ex-1", a1.currentHash());
    var stray = result(id, "att-1", "ex-1", a1.currentHash());
    pointRow(id, a1.auditId(), res.auditId());

    JsonPath r = query(id);
    assertThat(r.getList("results")).hasSize(2);
    assertThat(r.getLong("results[0].auditId")).isEqualTo(res.auditId());
    assertThat(r.getBoolean("results[0].authoritative")).isTrue();
    assertThat(r.getBoolean("results[1].authoritative")).isFalse();
    assertThat(r.getLong("results[1].auditId")).isEqualTo(stray.auditId());
    assertThat(r.getString("results[0].status")).isEqualTo("executed");
    assertThat(r.getString("results[0].resultHash")).isEqualTo("b".repeat(64));
    assertThat(r.getString("results[0].finishedAt")).isEqualTo("2026-11-08T10:00:00Z");
    assertThat(r.getString("results[0].consumeReceiptHash")).isEqualTo(a1.currentHash());
    assertThat(r.getString("results[0].attemptId")).isEqualTo("att-1");
    assertThat(r.getMap("results[0]")).doesNotContainKey("errorCode");
    assertThat(r.getLong("receipts[0].authoritativeResultAuditId")).isEqualTo(res.auditId());
    assertThat(r.getLong("receipts[0].authoritativeConsumeAuditId"))
        .isLessThan(r.getLong("receipts[0].authoritativeResultAuditId"));
  }

  @Test void verifyChainValidOverNewEvents() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    var a1 = attempt(row, "att-1", "ex-1");
    outcome(id, "att-1", "ex-1", "consumed", a1.currentHash());
    result(id, "att-1", "ex-1", a1.currentHash());
    anchorService.anchorAllTenants();
    as(TENANT).queryParam("start", Instant.now().minusSeconds(3600).toString())
        .queryParam("end", Instant.now().plusSeconds(60).toString())
        .get("/api/v1/audit/verify-chain").then().statusCode(200)
        .body("valid", org.hamcrest.Matchers.equalTo(true));
  }

  @Test void 其他租户看不到consume事件() {
    UUID id = UUID.randomUUID();
    GuardReceiptEntity row = receiptRow(id, null, null);
    decisionRow(id);
    attempt(row, "att-1", "ex-1");
    JsonPath r = as(OTHER_TENANT).queryParam("decisionIds", id.toString())
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("consumes")).isNull();
    assertThat(r.getList("missing")).containsExactly(id.toString());
  }
}
