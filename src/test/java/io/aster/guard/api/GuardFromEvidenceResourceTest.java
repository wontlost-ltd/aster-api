package io.aster.guard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.TestFactSigner;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.evidence.EvaluationEvidence;
import io.aster.policy.evidence.EvaluationMetadataBudget;
import io.aster.policy.governance.RuleMetadata;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
public class GuardFromEvidenceResourceTest {
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
  /** 共用本测试档案的其他测试类（如 EvaluateSourceClassificationTest）签名用。 */
  public static final String HMAC_KEY_FOR_TESTS = HMAC_KEY;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject AuditChainAppender appender;
  @Inject ApiKeyVerifierService verifier;
  @Inject FactIssuerStore factIssuerStore;
  @ConfigProperty(name = "aster.guard.receipt.signing-keys") String signingKeys;

  @BeforeEach
  void setUp() {
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-ge-1", "user-ge", TENANT, "pro", "active", "MEMBER"));
  }

  /**
   * 写一条 POLICY_EVALUATION 评估事件（带 policyVersionId，同 aster-api#459 之后的评估事件）；
   * role 为 null 时不写该键（ESCALATE 或旧事件）。
   */
  private AppendedRecord appendEvaluation(String tenant, String cid, String outcome, String role) {
    Map<String, Object> metadata = new HashMap<>(Map.of("correlationId", cid, "outcome", outcome,
        "ruleId", "CUST-DEL-001", "controls", List.of("GDPR:ART17"), "reason", "删除客户记录需 DPO 复核",
        "policyVersionId", 1730890123456L));
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

  /** ADR 0040 §12 D9：from-evidence 开出的决策经审批转 ALLOW 同样签发执行收据，actionHash 为请求体 action 原文的哈希。 */
  @Test
  void fromEvidenceAllowIssuesReceipt() {
    String cid = newCid();
    Map<String, Object> metadata = new HashMap<>(Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL",
        "role", DPO, "ruleId", "CUST-DEL-001", "policyVersionId", 1730890123456L));
    append(TENANT, metadata);
    String body = "{\"correlationId\":\"" + cid + "\",\"action\":{\"principal\":{\"id\":\"agent-42\",\"type\":null},"
        + "\"action\":{\"name\":\"delete_customer_record\"},\"context\":{\"amount\":250000.0,\"memo\":null}}}";
    Response opened = signedPost(FROM_EVIDENCE, body, TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(opened.asString()).doesNotContain("executionReceipt");
    String decisionId = opened.jsonPath().getString("decisionId");

    Response ok = signedPost("/api/v1/guard/approvals/" + opened.jsonPath().getString("approval.id") + "/approve",
        "{}", TENANT, "u-dpo", List.of(DPO));

    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    var receipt = ExecutionReceiptAssert.assertVerifiable(ok.asString(), signingKeys);
    assertThat(receipt.get("payload").get("actionHash").asText()).isEqualTo(ExecutionReceiptAssert.actionHashOf(body));
    assertThat(receipt.get("payload").get("policyVersionId").asLong()).isEqualTo(1730890123456L);
    Response read = signedGet("/api/v1/guard/decisions/" + decisionId, "", TENANT, "u-req");
    assertThat(ExecutionReceiptAssert.tree(read.asString()).get("executionReceipt")).isEqualTo(receipt);
    UUID id = UUID.fromString(decisionId);
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("decisionId", id))).isEqualTo(1);
  }

  /**
   * aster-cloud {@code evaluateSource} 写出的评估事件形状（{@code PolicyEvaluationResource} 即时源码分支）：
   * 经 {@link EvaluationEvidence#withVersion} 只附内容哈希、<b>没有</b> {@code policyVersionId}，另带即时执行的三个键。
   */
  private AppendedRecord appendEvaluateSourceEvaluation(String tenant, String cid, String outcome, String role) {
    EvaluationEvidence evidence = new EvaluationEvidence(cid, outcome, "CUST-DEL-001", List.of("GDPR:ART17"), null,
        null, role, "删除客户记录需 DPO 复核", null)
        .withVersion(null, PolicyVersion.computeSourceHash("Module guard.customer."), null);
    Map<String, Object> metadata = new HashMap<>(evidence.toMetadata());
    assertThat(metadata).doesNotContainKey(EvaluationEvidence.KEY_POLICY_VERSION_ID)
        .containsKey(EvaluationEvidence.KEY_SOURCE_HASH);
    metadata.put("locale", "en-US");
    metadata.put("dynamicExecution", true);
    metadata.put("namedContext", true);
    return append(tenant, metadata);
  }

  /** 以 HMAC 通道批准，返回批准响应。 */
  private static Response approve(Response opened, String approver, List<String> roles) {
    return signedPost("/api/v1/guard/approvals/" + opened.jsonPath().getString("approval.id") + "/approve",
        "{}", TENANT, approver, roles);
  }

  /**
   * C1 回归：evaluateSource 锚定的决策（有 action_hash、无策略版本）经审批转 ALLOW 照常签发执行收据，
   * 载荷省略 policyVersionId（ADR 0040 §12 D1 / D9）；收据行恰一行。ESCALATE（无所需角色）同样适用。
   */
  @Test
  void evaluateSourceEvidenceWithoutVersionApprovalIssuesReceipt() {
    String cid = newCid();
    appendEvaluateSourceEvaluation(TENANT, cid, "ESCALATE", null);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(opened.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    String decisionId = opened.jsonPath().getString("decisionId");
    UUID id = UUID.fromString(decisionId);
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(id))
        .policyVersionId).isNull();

    Response ok = approve(opened, "u-reviewer", List.of());

    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(ok.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    var receipt = ExecutionReceiptAssert.assertVerifiable(ok.asString(), signingKeys);
    assertThat(receipt.get("payload").has("policyVersionId")).isFalse();
    assertThat(receipt.get("payload").get("actionHash").asText()).isEqualTo(ExecutionReceiptAssert.actionHashOf(body(cid)));
    assertThat(receipt.get("payload").get("decisionId").asText()).isEqualTo(decisionId);
    assertThat(receipt.get("revision").asLong()).isPositive();
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("decisionId", id))).isEqualTo(1);
  }

  /**
   * C1 回归（端到端）：evaluateSource 形状的 REQUIRE_APPROVAL → 批准 → 收据 → HMAC consume 200；
   * 之后决策回读带 actionHash / consumedBy（ADR 0054 §3.3），结果回写后带 result。
   */
  @Test
  void evaluateSourceEvidenceWithoutVersionApproveThenConsume200() {
    String cid = newCid();
    appendEvaluateSourceEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    String decisionId = opened.jsonPath().getString("decisionId");

    Response ok = approve(opened, "u-dpo", List.of(DPO));
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    var receipt = ExecutionReceiptAssert.assertVerifiable(ok.asString(), signingKeys);
    assertThat(receipt.get("payload").has("policyVersionId")).isFalse();

    String actionHash = ExecutionReceiptAssert.actionHashOf(body(cid));
    String attemptId = UUID.randomUUID().toString();
    String consumeBody = "{\"actionHash\":\"" + actionHash + "\",\"attemptId\":\"" + attemptId
        + "\",\"executorId\":\"aster-cloud-exec@cloud-1\"}";
    Response consumed = signedPost("/api/v1/guard/receipts/" + decisionId + "/consume", consumeBody, TENANT, "u-exec",
        List.of());
    assertThat(consumed.statusCode()).as(consumed.asString()).isEqualTo(200);
    assertThat(consumed.jsonPath().getString("decisionId")).isEqualTo(decisionId);
    assertThat(ExecutionReceiptAssert.tree(consumed.asString()).get("executionReceipt")).isEqualTo(receipt);

    Response read = signedGet("/api/v1/guard/decisions/" + decisionId, "", TENANT, "u-tool");
    assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
    assertThat(read.jsonPath().getString("actionHash")).isEqualTo(actionHash);
    assertThat(read.jsonPath().getString("consumedBy.attemptId")).isEqualTo(attemptId);
    assertThat(read.jsonPath().getString("consumedBy.executorId")).isEqualTo("aster-cloud-exec@cloud-1");
    assertThat(read.jsonPath().getString("consumedBy.consumedAt")).isEqualTo(consumed.jsonPath().getString("consumedAt"));
    assertThat(read.jsonPath().getString("result")).isNull();
    assertThat(read.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");

    String resultBody = "{\"attemptId\":\"" + attemptId + "\",\"executorId\":\"aster-cloud-exec@cloud-1\","
        + "\"status\":\"executed\"}";
    Response result = signedPost("/api/v1/guard/receipts/" + decisionId + "/result", resultBody, TENANT, "u-exec",
        List.of());
    assertThat(result.statusCode()).as(result.asString()).isEqualTo(200);
    Response after = signedGet("/api/v1/guard/decisions/" + decisionId, "", TENANT, "u-tool");
    assertThat(after.jsonPath().getString("result.status")).isEqualTo("executed");
    assertThat(after.jsonPath().getString("result.resultAt")).isNotBlank();
  }

  @Test
  void fromEvidenceDuplicateKeyIs400InvalidAction() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    String body = "{\"correlationId\":\"" + cid + "\",\"action\":{\"principal\":{\"id\":\"a\",\"id\":\"b\"},"
        + "\"action\":{\"name\":\"delete_customer_record\"}}}";

    Response r = signedPost(FROM_EVIDENCE, body, TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("invalid_action");
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

  /**
   * aster-api#493：超长 applicantId、脱敏变长的 reason、需转义的 agent 字段把评估事件撑过 4096。经预算预检降级后
   * 写入的是合法 JSON，from-evidence 照常开出待审批，所需角色与结论不受影响（此前截断成非法 JSON → 409）。
   */
  @Test
  void 降级后的超大评估事件仍可从证据开出待审批() throws Exception {
    String cid = newCid();
    String esc = "\u0001".repeat(AgentIdentity.MAX_TEXT);
    EvaluationEvidence evidence = new EvaluationEvidence(cid, "REQUIRE_APPROVAL", "CUST-DEL-001",
        List.of("GDPR:ART17"), 1730890123456L, new AgentIdentity(esc, esc, esc, esc), DPO,
        // 用 governed：本例只验证降级后的事件仍可审批；registry 1.3.0 起高风险档案无签名事实即 DENY（另有用例覆盖）
        "1.1.1.1 ".repeat(32).substring(0, AgentIdentity.MAX_TEXT), "governed")
        .withInputRefs(List.of("context.amount"), false);
    Map<String, Object> raw = new HashMap<>(Map.of("contextSize", 1, "applicantId", "a@b.cc ".repeat(2_000)));
    raw.putAll(evidence.toMetadata());
    AppendedRecord rec = append(TENANT, EvaluationMetadataBudget.fit(raw));

    AuditLog row = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>findById(rec.auditId()));
    assertThat(row.metadata.length()).isLessThanOrEqualTo(AuditChainAppender.MAX_METADATA_SIZE);
    assertThat(JSON.readTree(row.metadata).get("metadataDegraded").isArray()).isTrue();

    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(opened.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(opened.jsonPath().getString("approval.requiredRole")).isEqualTo(DPO);
    assertThat(opened.jsonPath().getString("ruleId")).isEqualTo("CUST-DEL-001");
  }

  /**
   * registry 1.3.0（ADR 0046 §11.4 ⑦）：不注入任何元数据或注册表，事件声明 {@code eu-ai-act-high-risk}、
   * 自带已映射的 {@code inputRefs}，且写事件时的要求项快照为 false。from-evidence 按事件档案与内置注册表重算
   * → 要求 verifiedInputs；请求未附事实，被引用的 context.amount 为 declared → DENY unverified_input，不建待审批。
   */
  @Test
  void 默认注册表下高风险档案事件未附事实即拒绝() {
    String cid = newCid();
    EvaluationEvidence evidence = new EvaluationEvidence(cid, "REQUIRE_APPROVAL", "CUST-DEL-001",
        List.of("GDPR:ART17"), null, null, DPO, "删除客户记录需 DPO 复核", "eu-ai-act-high-risk")
        .withVersion(null, PolicyVersion.computeSourceHash("Module guard.customer."), null)
        .withInputRefs(List.of("context.amount"), false);
    append(TENANT, new HashMap<>(evidence.toMetadata()));

    Response resp = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(resp.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(resp.jsonPath().getString("approval")).isNull();
    String decisionId = resp.jsonPath().getString("decisionId");
    UUID id = UUID.fromString(decisionId);
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.count("decisionId", id))).isZero();
    Response receipts = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(receipts.statusCode()).as(receipts.asString()).isEqualTo(200);
    assertThat(receipts.jsonPath().getList("receipts[0].metadata.unverifiedFields")).containsExactly("context.amount");
  }

  /** 结论键本身超预算时只剩最小键集（无 outcome）：from-evidence 按不可审批 409，不会开出缺所需角色的待审批。 */
  @Test
  void 超出最小键集的评估事件按不可审批409() {
    String cid = newCid();
    Map<String, Object> raw = new HashMap<>(Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL",
        "role", "\"".repeat(255), "ruleId", "\u0001".repeat(255),
        "controls", java.util.stream.IntStream.range(0, 16).mapToObj(i -> "\"".repeat(64)).toList()));
    append(TENANT, EvaluationMetadataBudget.fit(raw));

    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
  }

  /** 绕过预检直接写超限 metadata：追加器写合法 JSON 占位（不再是非法截断），同样按不可审批 409。 */
  @Test
  void 未经预检的超限事件写成合法占位_from_evidence409() throws Exception {
    String cid = newCid();
    AppendedRecord rec = append(TENANT, Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL", "role", DPO,
        "applicantId", "x".repeat(5_000)));

    AuditLog row = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>findById(rec.auditId()));
    assertThat(JSON.readTree(row.metadata).get("metadataTruncated").asBoolean()).isTrue();
    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
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

  // ---- ADR 0040 §11 D2 (b)(c) / D4：from-evidence 同样验签并标注来源 ----

  /** 本租户登记一个新 kid 的签发方（每个用例独立 kid，避免与同 JVM 其他用例的公钥冲突）。 */
  private TestFactSigner registeredSigner(String kid) {
    TestFactSigner signer = TestFactSigner.newKey().kid(kid);
    var saved = factIssuerStore.upsert(TENANT, TestFactSigner.ISSUER, kid, signer.publicKey(), true, null, "u-admin",
        Instant.now());
    assertThat(saved).isInstanceOf(FactIssuerStore.UpsertResult.Saved.class);
    return signer;
  }

  private static String newKid() {
    return "k-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static ObjectNode amountFact(TestFactSigner signer) {
    Instant now = Instant.now();
    return signer.fresh().audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.amount\":250000}").sources("{\"context.amount\":\"ledger.amount\"}").sign();
  }

  private static String bodyWithFacts(String cid, String principal, JsonNode... facts) {
    ObjectNode body = (ObjectNode) readTree(body(cid));
    ObjectNode action = (ObjectNode) body.get("action");
    if (principal != null) {
      action.set("principal", readTree(principal));
    }
    var list = action.putArray("facts");
    for (JsonNode f : facts) {
      list.add(f);
    }
    return body.toString();
  }

  private static JsonNode readTree(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(json, e);
    }
  }

  private static long decisionsFor(String cid) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.count("evidenceCorrelationId", cid));
  }

  /** 伪造签名（另一把密钥签的同一 issuer#kid）→ 400 invalid_fact，不开决策；已开决策的同一证据也不能被伪造事实重放。 */
  @Test
  void forgedFactOnFromEvidenceIs400() {
    String kid = newKid();
    registeredSigner(kid);
    ObjectNode forged = amountFact(TestFactSigner.newKey().kid(kid));
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);

    Response rejected = signedPost(FROM_EVIDENCE, bodyWithFacts(cid, null, forged), TENANT, "u-req", List.of());
    Response opened = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    Response replayWithForged = signedPost(FROM_EVIDENCE, bodyWithFacts(cid, null, forged), TENANT, "u-req", List.of());

    assertThat(rejected.statusCode()).as(rejected.asString()).isEqualTo(400);
    assertThat(rejected.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(rejected.jsonPath().getString("reason")).isEqualTo("bad_signature");
    assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);
    assertThat(replayWithForged.statusCode()).isEqualTo(400);
    assertThat(replayWithForged.jsonPath().getString("reason")).isEqualTo("bad_signature");
    assertThat(decisionsFor(cid)).isEqualTo(1);
  }

  @Test
  void validFactOnFromEvidenceMarksFieldVerified() throws Exception {
    String kid = newKid();
    TestFactSigner signer = registeredSigner(kid);
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);

    Response resp = signedPost(FROM_EVIDENCE, bodyWithFacts(cid, null, amountFact(signer)), TENANT, "u-req", List.of());

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
    assertThat(resp.jsonPath().getList("inputSources.factIssuers")).containsExactly(TestFactSigner.ISSUER + "#" + kid);
    GuardDecisionEntity row = QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(
        UUID.fromString(resp.jsonPath().getString("decisionId"))));
    assertThat(JSON.readTree(row.verifiedFields)).isEqualTo(JSON.readTree("[\"context.amount\"]"));
    assertThat(JSON.readTree(row.factIssuers)).isEqualTo(JSON.readTree("[\"" + TestFactSigner.ISSUER + "#" + kid + "\"]"));
    assertThat(row.factExpiresAt).isNotNull();
  }

  /** 路径 (c)：HMAC 签过的 userId 即 principal.id（principal.type=user）→ verified；业务角色取签名内的角色。 */
  @Test
  void signedUserPrincipalIsVerifiedOnFromEvidence() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);

    Response resp = signedPost(FROM_EVIDENCE,
        bodyWithFacts(cid, "{\"id\":\"u-req\",\"type\":\"user\",\"roles\":[\"ops\"]}"), TENANT, "u-req",
        List.of("ops"));

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getList("inputSources.verified")).containsExactly("principal.id", "principal.roles");
  }
  /**
   * ADR 0040 §11 D5：from-evidence 同样执行档案门。规则引用的 context.amount 为 declared（人手填写、未附事实）
   * → DENY unverified_input 而非 PENDING：写决策收据（unverifiedFields）与决策行，不建待审批。
   * 规则元数据经 {@link QuarkusMock} 替换，只作用于当前用例（引擎尚不产出 inputRefs，见 Task 16）。
   */
  @Test
  void fromEvidenceAlsoGated() {
    PolicyEvaluationService evaluation = Mockito.mock(PolicyEvaluationService.class);
    // 评估事件带 policyVersionId：元数据按该版本读取
    Mockito.when(evaluation.ruleMetadata(TENANT, "guard.customer", "decide", "1730890123456")).thenReturn(
        new RuleMetadata("CUST-DEL-001", List.of("GDPR:ART17"), "eu-ai-act-high-risk", List.of("context.amount"), true));
    QuarkusMock.installMockForType(evaluation, PolicyEvaluationService.class);
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);

    Response resp = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());

    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("outcome")).isEqualTo("DENY");
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(resp.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(resp.jsonPath().getString("reason")).isNull();
    assertThat(resp.jsonPath().getString("approval")).isNull();
    String decisionId = resp.jsonPath().getString("decisionId");
    UUID id = UUID.fromString(decisionId);
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.<GuardDecisionEntity>findById(id))
        .reasonCode).isEqualTo("unverified_input");
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.count("decisionId", id))).isZero();
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("decisionId", id))).isZero();
    Response receipts = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(receipts.statusCode()).as(receipts.asString()).isEqualTo(200);
    assertThat(receipts.jsonPath().getString("receipts[0].metadata.reasonCode")).isEqualTo("unverified_input");
    // 版本元数据的 IR 引用无法映射到证据形状，按「*」比对（ADR 0040 §11 D5 实施澄清）
    assertThat(receipts.jsonPath().getList("receipts[0].metadata.unverifiedFields")).containsExactly("*");
    assertThat(receipts.jsonPath().getString("receipts[0].metadata.evidenceCorrelationId")).isEqualTo(cid);
  }

  // ---- ADR 0042 §12 D1 / D2：evaluate-source → from-evidence 的输入绑定与输入快照（真实 HTTP 两条路径） ----

  private static final String EVALUATE_SOURCE = "/api/v1/policies/evaluate-source";

  private static final String PII_SOURCE = String.join("\n",
      "Module guard.cls460.", "",
      "Define Applicant has amount as Int, email as @pii(L2, email) Text.", "",
      "Rule decide given applicant as Applicant, produce Verdict:",
      "  If applicant.amount greater than 10:",
      "    Return Verdict.require_approval(\"Credit Officer\", \"large amount\").",
      "  Return Verdict.allow().", "");

  /**
   * 同一段 context JSON 文本同时用于 evaluate-source 与 from-evidence：含小数 {@code 1.10}、I-JSON 上界整数 2^53−1
   * （更大的整数 guard 请求本就按 invalid_action 拒绝）、长小数、null 叶子、嵌套对象与数组、非 ASCII 键。
   * 两条反序列化路径若得到不同的数字类型或键序，哈希会不同。
   */
  private static final String CONTEXT_JSON = "{\"applicant\":{\"amount\":20,\"email\":\"a@b.cc\",\"rate\":1.10,"
      + "\"big\":9007199254740991,\"neg\":-9007199254740991,\"long\":12345678901234.5678,\"nothing\":null,\"nested\":{\"k\":[1,{\"x\":\"y\"}],\"z\":true},\"名字\":\"张三\"}}";

  private static String evaluateSourceBody(String source, String contextJson) {
    return "{\"source\":" + JSON.valueToTree(source) + ",\"context\":" + contextJson
        + ",\"locale\":\"en-US\",\"functionName\":\"decide\"}";
  }

  private static String fromEvidenceBody(String cid, String contextJson) {
    return "{\"correlationId\":\"" + cid + "\",\"action\":{\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\"},"
        + "\"action\":{\"name\":\"approve_loan\"},\"resource\":{\"type\":\"loan\",\"id\":\"L-1\"},"
        + "\"context\":" + contextJson + "}}";
  }

  /** 经 HMAC 调 evaluate-source，返回证据 correlationId，并等评估事件异步入链。 */
  private static String evaluateSource(String tenant, String source, String contextJson) {
    String body = evaluateSourceBody(source, contextJson);
    Response r = signedPost(EVALUATE_SOURCE, body, tenant, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    String cid = r.jsonPath().getString("evidence.correlationId");
    assertThat(cid).as(r.asString()).isNotBlank();
    long deadline = System.currentTimeMillis() + 15_000;
    while (QuarkusTransaction.requiringNew().call(() -> AuditLog.findByCorrelationIds(tenant, List.of(cid))).isEmpty()) {
      assertThat(System.currentTimeMillis()).as("评估事件未在 15s 内入链").isLessThan(deadline);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    return cid;
  }

  private static io.aster.guard.snapshot.GuardDecisionInputEntity inputRow(String tenant, String decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> io.aster.guard.snapshot.GuardDecisionInputEntity
        .findByDecisionAndTenant(UUID.fromString(decisionId), tenant)).orElse(null);
  }

  @Test
  void bindingHashEqualAcrossHttpPaths() {
    String cid = evaluateSource(TENANT, PII_SOURCE, CONTEXT_JSON);
    var classification = QuarkusTransaction.requiringNew().call(() ->
        io.aster.guard.snapshot.GuardEvaluationClassificationEntity.findByKey(TENANT, cid)).orElseThrow();
    assertThat(classification.inputHash).hasSize(64);

    Response r = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, CONTEXT_JSON), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");

    var input = inputRow(TENANT, r.jsonPath().getString("decisionId"));
    assertThat(input).isNotNull();
    assertThat(input.inputBinding).isEqualTo("evaluated");
    assertThat(input.marksSource).isEqualTo("evaluation");
    assertThat(input.contextHash).isEqualTo(classification.inputHash);
    assertThat(input.classification).contains("context.applicant.email").contains("\"L2\"");
  }

  @Test
  void mismatchedContextIs409AndCreatesNothing() {
    String cid = evaluateSource(TENANT, PII_SOURCE, CONTEXT_JSON);
    String tampered = CONTEXT_JSON.replace("\"amount\":20", "\"amount\":5");

    Response bad = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, tampered), TENANT, "u-req", List.of());
    assertThat(bad.statusCode()).as(bad.asString()).isEqualTo(409);
    assertThat(bad.jsonPath().getString("error")).isEqualTo("evidence_input_mismatch");
    assertThat(QuarkusTransaction.requiringNew().call(() ->
        GuardDecisionEntity.count("tenantId = ?1 and evidenceCorrelationId = ?2", TENANT, cid))).isZero();

    // 证据未被消耗：同一 correlationId 带被评估的输入重试成功
    Response ok = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, CONTEXT_JSON), TENANT, "u-req", List.of());
    assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
    assertThat(inputRow(TENANT, ok.jsonPath().getString("decisionId")).inputBinding).isEqualTo("evaluated");
  }

  @Test
  void missingClassificationRowIsUnboundNone() {
    String cid = newCid();
    appendEvaluation(TENANT, cid, "REQUIRE_APPROVAL", DPO);
    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    var input = inputRow(TENANT, r.jsonPath().getString("decisionId"));
    assertThat(input).isNotNull();
    assertThat(input.inputBinding).isEqualTo("unbound");
    assertThat(input.marksSource).isEqualTo("none");
    assertThat(input.classification).isNull();
    assertThat(input.actionJson).contains("250000").doesNotContain("facts");
  }

  @Test
  void positionalEvaluationIsUnboundNotMismatch() {
    String body = "{\"source\":" + JSON.valueToTree(PII_SOURCE) + ",\"context\":[{\"amount\":20,\"email\":\"a@b.cc\"}]"
        + ",\"locale\":\"en-US\",\"functionName\":\"decide\"}";
    Response ev = signedPost(EVALUATE_SOURCE, body, TENANT, "u-req", List.of());
    assertThat(ev.statusCode()).as(ev.asString()).isEqualTo(200);
    String cid = ev.jsonPath().getString("evidence.correlationId");
    long deadline = System.currentTimeMillis() + 15_000;
    while (QuarkusTransaction.requiringNew().call(() -> AuditLog.findByCorrelationIds(TENANT, List.of(cid))).isEmpty()) {
      assertThat(System.currentTimeMillis()).isLessThan(deadline);
      Thread.onSpinWait();
    }
    var row = QuarkusTransaction.requiringNew().call(() ->
        io.aster.guard.snapshot.GuardEvaluationClassificationEntity.findByKey(TENANT, cid)).orElseThrow();
    assertThat(row.inputHash).isNull();

    Response r = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, "{\"anything\":1}"), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    var input = inputRow(TENANT, r.jsonPath().getString("decisionId"));
    assertThat(input.inputBinding).isEqualTo("unbound");
    assertThat(input.marksSource).isEqualTo("none");
  }

  @Test
  void gateDenyWritesNoInputRow() {
    String cid = newCid();
    Map<String, Object> metadata = new HashMap<>(Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL",
        "role", DPO, "profile", "eu-ai-act-high-risk", "inputRefs", List.of("context.amount")));
    append(TENANT, metadata);
    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("outcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("approval")).isNull();
    assertThat(inputRow(TENANT, r.jsonPath().getString("decisionId"))).isNull();
  }

  // ---- ADR 0046 §11.8（#510 裁定 (a)）：要求 verifiedInputs 的档案下 ALLOW 同样经 from-evidence 过来源门 ----

  /** 高风险档案模块：金额 ≥ 50000 需审批，否则直接 ALLOW；规则只读 applicant.amount。 */
  private static final String HIGH_RISK_SOURCE = String.join("\n",
      "Module guard.hr510.", "",
      "Profile \"eu-ai-act-high-risk\".", "",
      "Define Applicant has amount as Int.", "",
      "@id(\"HR510-001\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule decide given applicant as Applicant, produce Verdict:",
      "  If applicant.amount at least 50000:",
      "    Return Verdict.require_approval(\"Credit Officer\", \"large amount\").",
      "  Otherwise:",
      "    Return Verdict.allow().", "");
  private static final String SMALL_AMOUNT = "{\"applicant\":{\"amount\":100}}";

  private static ObjectNode smallAmountFact(TestFactSigner signer) {
    Instant now = Instant.now();
    return signer.fresh().audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.applicant.amount\":100}").sources("{\"context.applicant.amount\":\"ledger.amount\"}")
        .sign();
  }

  private static String withFacts(String body, JsonNode... facts) {
    ObjectNode root = (ObjectNode) readTree(body);
    var list = ((ObjectNode) root.get("action")).putArray("facts");
    for (JsonNode f : facts) {
      list.add(f);
    }
    return root.toString();
  }

  private static long receiptRows(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.count("decisionId", id));
  }

  private static long approvalRows(String decisionId) {
    UUID id = UUID.fromString(decisionId);
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.count("decisionId", id));
  }

  /**
   * C7b 的服务端形态：高风险模块评估得 ALLOW（自报金额填小），未附事实即登记 → DENY unverified_input，
   * 不建审批、不签执行收据、不写输入快照；证据被消耗，补附事实重放仍是这条 DENY。
   */
  @Test
  void highRiskAllowEvidenceWithoutFactIsDenyUnverifiedInput() throws Exception {
    String cid = evaluateSource(TENANT, HIGH_RISK_SOURCE, SMALL_AMOUNT);
    // 要求 verifiedInputs 的 ALLOW 同样写分类行：输入绑定靠它
    var classification = QuarkusTransaction.requiringNew().call(() ->
        io.aster.guard.snapshot.GuardEvaluationClassificationEntity.findByKey(TENANT, cid)).orElseThrow();
    assertThat(classification.inputHash).hasSize(64);

    Response r = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, SMALL_AMOUNT), TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("outcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(r.jsonPath().getString("approval")).isNull();
    assertThat(r.asString()).doesNotContain("executionReceipt");
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(receiptRows(decisionId)).isZero();
    assertThat(approvalRows(decisionId)).isZero();
    assertThat(inputRow(TENANT, decisionId)).isNull();
    Response receipts = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(receipts.jsonPath().getList("receipts[0].metadata.unverifiedFields"))
        .containsExactly("context.applicant.amount");

    TestFactSigner signer = registeredSigner(newKid());
    Response replay = signedPost(FROM_EVIDENCE, withFacts(fromEvidenceBody(cid, SMALL_AMOUNT), smallAmountFact(signer)),
        TENANT, "u-req", List.of());
    assertThat(replay.statusCode()).as(replay.asString()).isEqualTo(200);
    assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(decisionId);
    assertThat(replay.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
  }

  /** 事实覆盖被引用的输入 → ALLOW：签发执行收据（actionHash 为请求体 action 的哈希），不建审批、不写输入快照。 */
  @Test
  void highRiskAllowEvidenceWithCoveringFactIsAllowWithExecutionReceipt() {
    TestFactSigner signer = registeredSigner(newKid());
    String cid = evaluateSource(TENANT, HIGH_RISK_SOURCE, SMALL_AMOUNT);
    String body = withFacts(fromEvidenceBody(cid, SMALL_AMOUNT), smallAmountFact(signer));

    Response r = signedPost(FROM_EVIDENCE, body, TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("outcome")).isEqualTo("ALLOW");
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(r.jsonPath().getString("reasonCode")).isNull();
    assertThat(r.jsonPath().getString("approval")).isNull();
    assertThat(r.jsonPath().getList("inputSources.verified")).contains("context.applicant.amount");
    var receipt = ExecutionReceiptAssert.assertVerifiable(r.asString(), signingKeys);
    assertThat(receipt.get("payload").get("actionHash").asText()).isEqualTo(ExecutionReceiptAssert.actionHashOf(body));
    assertThat(receipt.get("payload").has("policyVersionId")).isFalse();
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(receiptRows(decisionId)).isEqualTo(1);
    assertThat(approvalRows(decisionId)).isZero();
    assertThat(inputRow(TENANT, decisionId)).isNull();
    Response receipts = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(receipts.jsonPath().getString("receipts[0].metadata.evidenceCorrelationId")).isEqualTo(cid);
  }

  /**
   * 没有分类行（无从核对请求 context 就是被评估的输入）时，即使事实覆盖了请求里的引用也 DENY，
   * unverifiedFields 为「*」：ALLOW 没有审批人兜底，不能接受 unbound。
   */
  @Test
  void highRiskAllowWithoutClassificationRowIsDenyEvenWithFact() {
    TestFactSigner signer = registeredSigner(newKid());
    String cid = newCid();
    append(TENANT, new HashMap<>(Map.of("correlationId", cid, "outcome", "ALLOW", "ruleId", "CUST-DEL-001",
        "profile", "eu-ai-act-high-risk", "inputRefs", List.of("context.amount"))));

    Response r = signedPost(FROM_EVIDENCE, bodyWithFacts(cid, null, amountFact(signer)), TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(r.jsonPath().getList("inputSources.verified")).contains("context.amount");
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(receiptRows(decisionId)).isZero();
    Response receipts = signedGet(RECEIPTS, "decisionIds=" + decisionId, TENANT, "u-export");
    assertThat(receipts.jsonPath().getList("receipts[0].metadata.unverifiedFields")).containsExactly("*");
  }

  /** 过门后执行收据签发失败 → DENY receipt_unavailable：不留收据行、不建审批（与 POST /decisions 同一失败语义）。 */
  @Test
  void highRiskAllowReceiptSigningFailureIsDenyReceiptUnavailable() {
    io.aster.guard.receipt.ExecutionReceiptIssuer issuer = Mockito.mock(io.aster.guard.receipt.ExecutionReceiptIssuer.class);
    Mockito.when(issuer.issueNow(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new io.aster.guard.receipt.ExecutionReceiptUnavailableException("signer down", null));
    QuarkusMock.installMockForType(issuer, io.aster.guard.receipt.ExecutionReceiptIssuer.class);
    TestFactSigner signer = registeredSigner(newKid());
    String cid = evaluateSource(TENANT, HIGH_RISK_SOURCE, SMALL_AMOUNT);

    Response r = signedPost(FROM_EVIDENCE, withFacts(fromEvidenceBody(cid, SMALL_AMOUNT), smallAmountFact(signer)),
        TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo("receipt_unavailable");
    assertThat(r.asString()).doesNotContain("executionReceipt");
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(receiptRows(decisionId)).isZero();
    assertThat(approvalRows(decisionId)).isZero();
  }

  /** 档案不要求 verifiedInputs 的 ALLOW 不受影响：仍 409 evidence_not_approvable，不开决策。 */
  @Test
  void allowEvidenceUnderNonRequiringProfileStill409() {
    String cid = newCid();
    append(TENANT, new HashMap<>(Map.of("correlationId", cid, "outcome", "ALLOW", "ruleId", "CUST-DEL-001",
        "profile", "governed", "inputRefs", List.of("context.amount"))));

    Response r = signedPost(FROM_EVIDENCE, body(cid), TENANT, "u-req", List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
    assertThat(r.jsonPath().getString("outcome")).isEqualTo("ALLOW");
    assertThat(decisionsFor(cid)).isZero();
  }

  /** 高风险 ALLOW 的 context 与被评估输入不一致 → 409 evidence_input_mismatch，不消耗证据。 */
  @Test
  void highRiskAllowMismatchedContextIs409NotConsumed() {
    String cid = evaluateSource(TENANT, HIGH_RISK_SOURCE, SMALL_AMOUNT);

    Response r = signedPost(FROM_EVIDENCE, fromEvidenceBody(cid, "{\"applicant\":{\"amount\":99}}"), TENANT, "u-req",
        List.of());

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_input_mismatch");
    assertThat(decisionsFor(cid)).isZero();
  }
}
