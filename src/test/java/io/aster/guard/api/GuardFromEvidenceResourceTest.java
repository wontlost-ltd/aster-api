package io.aster.guard.api;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.GuardDecisionEntity;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0042 §3.1 / §4.2：{@code POST /api/v1/guard/decisions/from-evidence} 端到端（apikey 鉴权与 HMAC 同时打开）。
 * 评估事件直接写入审计链，决策以它为锚开出；审批经 HMAC 通道以签名内的用户与业务角色完成。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class GuardFromEvidenceResourceTest {
  /** 与 AuditReceiptHmacAuthTest 同款：apikey 鉴权与 HMAC 密钥同时打开，signature 关闭以贴近生产。 */
  public static class AuthProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "aster.plan-gate.hmac-key", HMAC_KEY,
          "aster.security.apikey.enabled", "true",
          "aster.security.signature.enabled", "false",
          "aster.security.evaluate-source.public", "false",
          "aster.security.evaluate-source.trial.enabled", "false");
    }
  }

  /** 每次 JVM 用独立租户：审计链只追加不删除，避免与历史运行互相干扰。 */
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-evidence-" + RUN;
  private static final String OTHER = "tenant-guard-evidence-other-" + RUN;
  private static final String FROM_EVIDENCE = "/api/v1/guard/decisions/from-evidence";
  private static final String RECEIPTS = "/api/v1/audit/receipts";
  private static final String DPO = "Data Protection Officer";
  private static final String BEARER_KEY = "ak_test_guard_evidence_member";
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";

  @Inject AuditChainAppender appender;
  @Inject ApiKeyVerifierService verifier;

  @BeforeEach
  void setUp() {
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-ge-1", "user-ge", TENANT, "pro", "active", "MEMBER"));
  }

  /** 写一条 POLICY_EVALUATION 评估事件；role 为 null 时不写该键（ESCALATE 或旧事件）。 */
  private AppendedRecord appendEvaluation(String tenant, String cid, String outcome, String role) {
    Map<String, Object> metadata = new HashMap<>(Map.of("correlationId", cid, "outcome", outcome,
        "ruleId", "CUST-DEL-001", "controls", List.of("GDPR:ART17"), "reason", "删除客户记录需 DPO 复核"));
    if (role != null) {
      metadata.put("role", role);
    }
    return append(tenant, metadata);
  }

  private AppendedRecord append(String tenant, Map<String, Object> metadata) {
    return appender.appendSync(new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), tenant, "guard.customer",
        "decide", null, null, null, "u-req", true, 5L, null, metadata, null, null, null, null));
  }

  private static String newCid() {
    return "ev-" + UUID.randomUUID();
  }

  private static String body(String cid) {
    return "{\"correlationId\":\"" + cid + "\",\"action\":{\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\"},"
        + "\"action\":{\"name\":\"delete_customer_record\"},\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"},"
        + "\"context\":{\"amount\":250000}}}";
  }

  private static Response signedPost(String path, String body, String tenant, String userId, List<String> roles) {
    return given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", body, tenant, "member", userId, roles))
        .contentType("application/json")
        .body(body)
        .post(path);
  }

  private static Response signedGet(String path, String query, String tenant, String userId) {
    return given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "GET", path, query, "", tenant, "member", userId, List.of()))
        .get(query.isEmpty() ? path : path + "?" + query);
  }

  @Test
  void HMAC从证据开决策_响应收据为新决策行_重复调用同一决策() {
    String cid = newCid();
    AppendedRecord event = appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);

    Response r1 = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r1.statusCode()).as(r1.asString()).isEqualTo(200);
    assertThat(r1.jsonPath().getString("outcome")).isEqualTo("REQUIRE_APPROVAL");
    assertThat(r1.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(r1.jsonPath().getString("approval.requiredRole")).isEqualTo(DPO);
    assertThat(r1.jsonPath().getString("approval.status")).isEqualTo("PENDING");
    assertThat(r1.jsonPath().getString("ruleId")).isEqualTo("CUST-DEL-001");
    assertThat(r1.jsonPath().getList("controls")).containsExactly("GDPR:ART17");
    assertThat(r1.jsonPath().getString("policyVersion")).isNull();
    assertThat(r1.jsonPath().getLong("receipt.auditId")).isGreaterThan(event.auditId());
    assertThat(r1.jsonPath().getString("receipt.hash")).isNotEqualTo(event.currentHash());

    Response r2 = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r2.statusCode()).isEqualTo(200);
    assertThat(r2.jsonPath().getString("decisionId")).isEqualTo(r1.jsonPath().getString("decisionId"));

    // 决策回读对 HMAC 通道开放
    Response get = signedGet("/api/v1/guard/decisions/" + r1.jsonPath().getString("decisionId"), "", TENANT, "u-req");
    assertThat(get.statusCode()).as(get.asString()).isEqualTo(200);
    assertThat(get.jsonPath().getLong("receipt.auditId")).isEqualTo(r1.jsonPath().getLong("receipt.auditId"));
  }

  @Test
  void 从证据开决策写决策收据行_批准后按decisionId取回审批() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    String decisionId = opened.jsonPath().getString("decisionId");

    Response before = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(before.statusCode()).as(before.asString()).isEqualTo(200);
    assertThat(before.jsonPath().getString("receipts[0].eventType")).isEqualTo("ACTION_GUARD_DECISION");
    assertThat(before.jsonPath().getString("receipts[0].metadata.evidenceCorrelationId")).isEqualTo(cid);
    assertThat(before.jsonPath().getLong("receipts[0].auditId")).isEqualTo(opened.jsonPath().getLong("receipt.auditId"));
    assertThat(before.jsonPath().getString("receipts[0].currentHash")).isEqualTo(opened.jsonPath().getString("receipt.hash"));

    Response ok = signedPost("/api/v1/guard/approvals/" + opened.jsonPath().getString("approval.id") + "/approve",
        "{}", TENANT, "u-dpo", List.of(DPO));
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);

    Response after = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(after.statusCode()).as(after.asString()).isEqualTo(200);
    assertThat(after.jsonPath().getList("approvals.decidedBy")).containsExactly("u-dpo");
    assertThat(after.jsonPath().getString("approvals[0].decisionReceiptHash"))
        .isEqualTo(opened.jsonPath().getString("receipt.hash"));
    assertThat(after.jsonPath().getList("missing")).isNull();
  }

  @Test
  void 非可审批事件409_不存在404_跨租户404_Bearer403_空userId400() {
    String allow = newCid();
    appendEvaluation(TENANT, allow, "ALLOW", null);
    Response notApprovable = signedPost(FROM_EVIDENCE, body(allow), TENANT, "u-req", List.of());
    assertThat(notApprovable.statusCode()).as(notApprovable.asString()).isEqualTo(409);
    assertThat(notApprovable.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
    assertThat(notApprovable.jsonPath().getString("outcome")).isEqualTo("ALLOW");

    Response missing = signedPost(FROM_EVIDENCE, body("no-such-cid-" + RUN), TENANT, "u-req", List.of());
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.jsonPath().getString("error")).isEqualTo("evidence_not_found");

    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    assertThat(signedPost(FROM_EVIDENCE, body(cid), OTHER, "u-req", List.of()).statusCode()).isEqualTo(404);
    Response bearer = given().header("Authorization", "Bearer " + BEARER_KEY).contentType("application/json")
        .body(body(cid)).post(FROM_EVIDENCE);
    assertThat(bearer.statusCode()).isEqualTo(403);
    assertThat(bearer.jsonPath().getString("reason")).isEqualTo("internal_only");
    Response noUser = signedPost(FROM_EVIDENCE, body(cid), TENANT, "", List.of());
    assertThat(noUser.statusCode()).isEqualTo(400);
    assertThat(noUser.jsonPath().getString("error")).isEqualTo("user_required");
    Response badAction = signedPost(FROM_EVIDENCE, "{\"correlationId\":\"" + cid + "\"}", TENANT, "u-req", List.of());
    assertThat(badAction.statusCode()).isEqualTo(400);
    assertThat(badAction.jsonPath().getString("error")).isEqualTo("invalid_action");
  }

  @Test
  void 开出的待审批可由持角色的HMAC用户批准_发起人自批403() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    String approvalId = opened.jsonPath().getString("approval.id");
    String approve = "/api/v1/guard/approvals/" + approvalId + "/approve";

    Response list = signedGet("/api/v1/guard/approvals", "status=PENDING&size=200", TENANT, "u-dpo");
    assertThat(list.statusCode()).as(list.asString()).isEqualTo(200);
    assertThat(list.jsonPath().getList("items.id", String.class)).contains(approvalId);
    assertThat(list.jsonPath().getString("items.find { it.id == '" + approvalId + "' }.evidenceCorrelationId"))
        .isEqualTo(cid);

    Response self = signedPost(approve, "{}", TENANT, "u-req", List.of(DPO));
    assertThat(self.statusCode()).as(self.asString()).isEqualTo(403);
    assertThat(self.jsonPath().getString("error")).isEqualTo("segregation_of_duties");

    Response ok = signedPost(approve, "{}", TENANT, "u-dpo", List.of(DPO));
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(ok.jsonPath().getString("decisionId")).isEqualTo(opened.jsonPath().getString("decisionId"));
  }

  @Test
  void 无outcome的评估事件409_outcome为字符串null() {
    String cid = newCid();
    // 失败的 /evaluate 事件只写相关键不写 outcome；metadata 截断无法解析时同样取不到 outcome
    append(TENANT, Map.of("correlationId", cid, "error", "evaluation_failed"));
    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
    assertThat(r.jsonPath().getString("outcome")).isEqualTo("null");
  }

  @Test
  void HMAC签空userId批准或拒绝400_user_required_审批仍待处理() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    String base = "/api/v1/guard/approvals/" + opened.jsonPath().getString("approval.id");

    Response approve = signedPost(base + "/approve", "{}", TENANT, "", List.of(DPO));
    assertThat(approve.statusCode()).as(approve.asString()).isEqualTo(400);
    assertThat(approve.jsonPath().getString("error")).isEqualTo("user_required");
    Response reject = signedPost(base + "/reject", "{\"comment\":\"no\"}", TENANT, "", List.of(DPO));
    assertThat(reject.statusCode()).as(reject.asString()).isEqualTo(400);
    assertThat(reject.jsonPath().getString("error")).isEqualTo("user_required");

    Response ok = signedPost(base + "/approve", "{}", TENANT, "u-dpo", List.of(DPO));
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
  }

  @Test
  void 无role键的待审批事件开出任意复核人可批的决策() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", null);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(opened.jsonPath().getString("approval.requiredRole")).isNull();

    Response ok = signedPost("/api/v1/guard/approvals/" + opened.jsonPath().getString("approval.id") + "/approve",
        "{}", TENANT, "u-reviewer", List.of());
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
  }

  @Test
  void 事件带策略版本时落库版本列_非字符串controls被跳过() {
    String cid = newCid();
    append(TENANT, Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL", "role", DPO,
        "ruleId", "CUST-DEL-001", "policyVersionId", 7L,
        "controls", List.of("GDPR:ART17", 42, Map.of("k", "v"), "SOX:404")));
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(opened.jsonPath().getList("controls")).containsExactly("GDPR:ART17", "SOX:404");
    assertThat(opened.jsonPath().getString("policyVersion")).isNull();

    UUID decisionId = UUID.fromString(opened.jsonPath().getString("decisionId"));
    GuardDecisionEntity row = QuarkusTransaction.requiringNew()
        .call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(decisionId));
    assertThat(row.policyVersionId).isEqualTo(7L);
    assertThat(row.evidenceCorrelationId).isEqualTo(cid);
  }
}
