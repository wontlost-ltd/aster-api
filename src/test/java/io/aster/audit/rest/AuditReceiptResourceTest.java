package io.aster.audit.rest;

import io.aster.audit.chain.AuditChainAppender;
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
  String decisionHash;

  @BeforeEach void setUp() {
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
    assertThat(r.getString("approvals[0].decidedAt")).isNotBlank();
    assertThat(r.getString("approvals[0].decisionReceiptHash")).isEqualTo(r.getString("receipts[0].currentHash"));
    assertThat(r.getList("missing")).containsExactly("d-none");
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
}
