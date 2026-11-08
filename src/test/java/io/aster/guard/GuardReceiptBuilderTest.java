package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.provenance.InputSources;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0040 §5.2：决策 / 审批收据事件的字段映射与 metadata 4 KB 上限。 */
class GuardReceiptBuilderTest {
  private static final String HASH = "a".repeat(64);
  private static final AgentIdentity AGENT = new AgentIdentity("anthropic", "claude-fable-5-1", "2026", "s-1");
  private final GuardActionMapper mapper = new GuardActionMapper();
  private final GuardReceiptBuilder builder = new GuardReceiptBuilder();
  private final ObjectMapper json = new ObjectMapper();

  private GuardDecisionDraft draft(String outcome, String reason, List<String> controls, String id, String idemKey) {
    Action a = new Action(new Principal(id, "agent", List.of("ops-bot")), new ActionName("delete_customer_record"),
        new Resource("customer", id), Map.of("amount", 250000), AGENT);
    Map<String, Object> flat = mapper.flatten(a);
    return new GuardDecisionDraft("t1", "guard.customer", "decide", idemKey, flat, mapper.contextHash(Map.of("amount", 250000)), null,
        new PolicyVersionView(1730890123456L, HASH, 3L), outcome, "Data Protection Officer", reason,
        "CUST-DEL-001", controls, null, AGENT);
  }

  @Test
  void 决策事件字段映射且metadata含全部键() throws Exception {
    UUID decisionId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();
    GuardDecisionDraft d = draft("REQUIRE_APPROVAL", "customer record deletion", List.of("GDPR:ART17", "SOX:404"), "agent-42", "idem-1");

    AuditEvent event = builder.decisionEvent(d, decisionId, approvalId, "user-1");

    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_DECISION);
    assertThat(event.success()).isFalse();
    assertThat(event.tenantId()).isEqualTo("t1");
    assertThat(event.policyModule()).isEqualTo("guard.customer");
    assertThat(event.policyFunction()).isEqualTo("decide");
    assertThat(event.toVersion()).isEqualTo(3L);
    assertThat(event.performedBy()).isEqualTo("user-1");
    assertThat(event.metadata()).containsKeys("decisionId", "outcome", "effectiveOutcome", "ruleId", "controls",
        "principalId", "principalType", "actionName", "resourceType", "resourceId", "contextHash", "agentProvider",
        "agentModel", "agentVersion", "agentSession", "agentSource", "policyVersionId", "sourceHash", "approvalId",
        "idempotencyKey", "reason", "principalRoles");
    assertThat(event.metadata()).containsEntry("decisionId", decisionId.toString())
        .containsEntry("approvalId", approvalId.toString())
        .containsEntry("outcome", "REQUIRE_APPROVAL")
        .containsEntry("effectiveOutcome", "PENDING")
        .containsEntry("controls", List.of("GDPR:ART17", "SOX:404"))
        .containsEntry("principalRoles", List.of("ops-bot"))
        .containsEntry("policyVersionId", 1730890123456L)
        .containsEntry("sourceHash", HASH)
        .containsEntry("contextHash", d.contextHash())
        .doesNotContainKey("context")
        .doesNotContainKey("reasonCode");
    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThan(4096);
  }

  @Test
  void ALLOW时success为true且无approvalId() {
    AuditEvent event = builder.decisionEvent(draft("ALLOW", null, List.of(), "agent-42", null), UUID.randomUUID(), null, "user-1");
    assertThat(event.success()).isTrue();
    assertThat(event.metadata()).containsEntry("effectiveOutcome", "ALLOW")
        .doesNotContainKeys("approvalId", "idempotencyKey", "reason");
  }

  @Test
  void fail_closed草稿带reasonCode() {
    GuardDecisionDraft denied = draft("REQUIRE_APPROVAL", "r", List.of(), "agent-42", null).deny(GuardReasonCode.NOT_A_VERDICT);
    AuditEvent event = builder.decisionEvent(denied, UUID.randomUUID(), null, "user-1");
    assertThat(event.success()).isFalse();
    assertThat(event.metadata()).containsEntry("outcome", "DENY").containsEntry("effectiveOutcome", "DENY")
        .containsEntry("reasonCode", "not_a_verdict");
  }

  /** 255 字符的「最坏」文本：转义后翻倍的 {@code "} 与 {@code \\}，外加一个会被脱敏放大的邮箱。 */
  private static String nasty(int length) {
    return ("a@b.cc " + "\"\\".repeat(length)).substring(0, length);
  }

  /** 真实追加器的纯映射（{@code toAuditLog} 不触库）：只需补上它注入的 ObjectMapper。 */
  private static AuditChainAppender appender() throws ReflectiveOperationException {
    AuditChainAppender appender = new AuditChainAppender();
    Field objectMapper = AuditChainAppender.class.getDeclaredField("objectMapper");
    objectMapper.setAccessible(true);
    objectMapper.set(appender, new ObjectMapper());
    return appender;
  }

  /** 事件 metadata 经真实追加器脱敏、序列化后写入 audit_logs.metadata 的长度。 */
  private static int appenderLength(AuditEvent event) throws ReflectiveOperationException {
    return appender().toAuditLog(event).metadata.length();
  }

  @Test
  void 最坏情况metadata不超过预算且身份字段完好() throws Exception {
    String text = nasty(255);
    List<String> roles = IntStream.range(0, 16).mapToObj(i -> text).toList();
    Action a = new Action(new Principal(text, text, roles), new ActionName(text), new Resource(text, text),
        Map.of("amount", 250000), new AgentIdentity(text, text, text, text));
    List<String> controls = IntStream.range(0, 16).mapToObj(i -> "\"\\".repeat(32)).toList();
    String contextHash = mapper.contextHash(Map.of("amount", 250000));
    GuardDecisionDraft d = new GuardDecisionDraft("t1", "guard.customer", "decide", text, mapper.flatten(a), contextHash, null,
        new PolicyVersionView(Long.MAX_VALUE, HASH, Long.MAX_VALUE), "REQUIRE_APPROVAL", text, text, text, controls,
        null, new AgentIdentity(text, text, text, text));
    UUID decisionId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();

    AuditEvent event = builder.decisionEvent(d, decisionId, approvalId, text);

    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThanOrEqualTo(3800);
    assertThat(appenderLength(event)).isLessThanOrEqualTo(3800);
    assertThat(event.metadata()).containsEntry("decisionId", decisionId.toString())
        .containsEntry("approvalId", approvalId.toString())
        .containsEntry("contextHash", contextHash)
        .containsEntry("ruleId", text)
        .containsEntry("idempotencyKey", text)
        .containsEntry("outcome", "REQUIRE_APPROVAL")
        .containsEntry("effectiveOutcome", "PENDING")
        .containsEntry("sourceHash", HASH);
    assertThat(event.performedBy()).hasSizeLessThanOrEqualTo(100);
    // roles 只会从末尾整项删除，剩下的是原列表的前缀
    @SuppressWarnings("unchecked")
    List<String> keptRoles = (List<String>) event.metadata().get("principalRoles");
    assertThat(roles.subList(0, keptRoles.size())).isEqualTo(keptRoles);
  }

  @Test
  void 身份键为数字形UUID时measure与追加器实际序列化长度一致() throws Exception {
    UUID decisionId = UUID.fromString("12345678-1234-5678-9012-abcdef123456");
    UUID approvalId = UUID.fromString("abcdef12-1234-5678-9012-345678901234");
    String idempotencyKey = "87654321-4321-8765-2109-fedcba654321";
    GuardDecisionDraft d = draft("REQUIRE_APPROVAL", "customer record deletion", List.of("GDPR:ART17"), "agent-42",
        idempotencyKey);

    AuditEvent event = builder.decisionEvent(d, decisionId, approvalId, "user-1");
    String persisted = appender().toAuditLog(event).metadata;

    assertThat(builder.measure(event.metadata())).isEqualTo(persisted.length());
    assertThat(persisted).contains("\"decisionId\":\"" + decisionId + "\"")
        .contains("\"approvalId\":\"" + approvalId + "\"")
        .contains("\"idempotencyKey\":\"" + idempotencyKey + "\"");
  }

  @Test
  void principalRoles缩短时从末尾逐项删除且不截断单项() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    m.put("principalRoles", IntStream.range(0, 16).mapToObj(i -> i + "-" + "r".repeat(250)).toList());

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    @SuppressWarnings("unchecked")
    List<String> kept = (List<String>) fitted.get("principalRoles");
    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(kept).isNotEmpty().hasSizeLessThan(16);
    for (int i = 0; i < kept.size(); i++) {
      assertThat(kept.get(i)).isEqualTo(i + "-" + "r".repeat(250));
    }
    assertThat(fitted).containsEntry("decisionId", "d");
  }

  @Test
  void 缩短按固定优先级先reason后其他() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("reason", "\"".repeat(2000));
    m.put("agentModel", "m".repeat(200));
    m.put("principalId", "p".repeat(200));
    m.put("decisionId", "d");

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat((String) fitted.get("reason")).hasSizeLessThan(2000);
    assertThat(fitted.get("agentModel")).isEqualTo("m".repeat(200));
    assertThat(fitted.get("principalId")).isEqualTo("p".repeat(200));
    assertThat(fitted).containsEntry("decisionId", "d");
  }

  @Test
  void 缩短顺序固定_principalId最后() {
    assertThat(GuardReceiptBuilder.SHRINK_ORDER).containsExactly("reason", "agentSession", "agentVersion",
        "principalRoles", "factIssuers", "verifiedFields", "factSources", "unverifiedFields", "agentModel",
        "agentProvider", "principalType", "resourceType", "resourceId", "actionName", "principalId");
  }

  @Test
  void agent四字段进收据且agentSource为declared() {
    GuardDecisionDraft d = draft("ALLOW", "ok", List.of(), "agent-42", null);

    Map<String, Object> m = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1").metadata();

    assertThat(m).containsEntry("agentProvider", "anthropic").containsEntry("agentModel", "claude-fable-5-1")
        .containsEntry("agentVersion", "2026").containsEntry("agentSession", "s-1")
        .containsEntry("agentSource", "declared");
  }

  /** ADR 0041 §3 / §8.1「顶层 agentModel 保留」：无 agent 时仍写空串，与旧收据形态一致；其余四键不写。 */
  @Test
  void 无agent时收据保留空agentModel且不含其余agent键() {
    GuardDecisionDraft d = draft("ALLOW", "ok", List.of(), "agent-42", null);
    GuardDecisionDraft noAgent = new GuardDecisionDraft(d.tenantId(), d.policyModule(), d.policyFunction(),
        d.idempotencyKey(), d.action(), d.contextHash(), d.actionHash(), d.policyVersion(), d.outcome(), d.role(), d.reason(),
        d.ruleId(), d.controls(), null, null);

    Map<String, Object> m = builder.decisionEvent(noAgent, UUID.randomUUID(), null, "user-1").metadata();

    assertThat(m).containsEntry("agentModel", "")
        .doesNotContainKeys("agentProvider", "agentVersion", "agentSession", "agentSource");
  }

  @Test
  void agentSession先于agentModel被缩短且principalId最后() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    m.put("reason", "r".repeat(100));
    m.put("agentSession", "\"".repeat(1900));
    m.put("agentModel", "m".repeat(200));
    m.put("principalId", "p".repeat(200));

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat((String) fitted.get("agentSession")).hasSizeLessThan(1900);
    assertThat(fitted).containsEntry("agentModel", "m".repeat(200)).containsEntry("principalId", "p".repeat(200));
  }

  @Test
  void 全部自由文本都撑满时principalId最后被缩短() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    for (String key : GuardReceiptBuilder.SHRINK_ORDER) {
      m.put(key, "\"".repeat(600));
    }

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat((String) fitted.get("reason")).isEmpty();
    assertThat((String) fitted.get("principalId")).hasSize(600);
    // 任一键被缩短，则它在 SHRINK_ORDER 中排在前面的键必须已清空。
    List<String> order = GuardReceiptBuilder.SHRINK_ORDER;
    for (int i = 1; i < order.size(); i++) {
      if (((String) fitted.get(order.get(i))).length() < 600) {
        assertThat((String) fitted.get(order.get(i - 1))).isEmpty();
      }
    }
  }

  @Test
  void reason清空后先删principalRoles_agentModel与principalId保持原文() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    m.put("reason", "r".repeat(100));
    m.put("principalRoles", IntStream.range(0, 16).mapToObj(i -> i + "-" + "r".repeat(250)).toList());
    m.put("agentModel", "m".repeat(200));
    m.put("principalId", "p".repeat(200));

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(fitted).containsEntry("reason", "")
        .containsEntry("agentModel", "m".repeat(200))
        .containsEntry("principalId", "p".repeat(200))
        .containsEntry("decisionId", "d");
    assertThat((List<?>) fitted.get("principalRoles")).isNotEmpty().hasSizeLessThan(16);
  }

  @Test
  void 自由文本清空仍超预算时最后删除controls() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    m.put("reason", "r".repeat(100));
    m.put("controls", IntStream.range(0, 100).mapToObj(i -> "c".repeat(64)).toList());

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.SHRINK_ORDER);

    assertThat(fitted).doesNotContainKey("controls").containsEntry("decisionId", "d").containsEntry("reason", "");
    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
  }

  @Test
  void 规则侧超长文本在收据中被截断() throws Exception {
    List<String> controls = IntStream.range(0, 40).mapToObj(i -> "x".repeat(300)).toList();
    GuardDecisionDraft d = new GuardDecisionDraft("t1", "guard.customer", "decide", "k".repeat(255),
        draft("DENY", null, List.of(), "p".repeat(255), null).action(), HASH, null,
        new PolicyVersionView(Long.MAX_VALUE, HASH, Long.MAX_VALUE), "DENY", "o".repeat(5000), "r".repeat(5000),
        "i".repeat(5000), controls, null, AGENT);

    AuditEvent event = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1");

    assertThat(appenderLength(event)).isLessThanOrEqualTo(3800);
    assertThat((String) event.metadata().get("reason")).hasSizeLessThanOrEqualTo(255);
  }

  @Test
  void 审批事件指回决策收据哈希() throws Exception {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = "t1";
    a.decisionId = UUID.randomUUID();
    a.outcome = "REQUIRE_APPROVAL";
    a.requiredRole = "Data Protection Officer";
    a.principalId = "agent-42";
    a.status = GuardApprovalStatus.APPROVED;
    a.decidedBy = "dpo-1";
    a.decidedAt = Instant.now();
    a.comment = "c".repeat(1000);
    a.expiresAt = Instant.now().plusSeconds(3600);

    AuditEvent event = builder.approvalEvent(a, HASH);

    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_APPROVAL);
    assertThat(event.tenantId()).isEqualTo("t1");
    assertThat(event.performedBy()).isEqualTo("dpo-1");
    assertThat(event.success()).isTrue();
    assertThat(event.metadata()).containsEntry("decisionId", a.decisionId.toString())
        .containsEntry("approvalId", a.id.toString())
        .containsEntry("outcome", "APPROVED")
        .containsEntry("decidedBy", "dpo-1")
        .containsEntry("requiredRole", "Data Protection Officer")
        .containsEntry("decisionReceiptHash", HASH)
        .containsKey("comment");
    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThan(4096);
  }

  @Test
  void 审批事件最坏转义仍在预算内且身份键完好() throws Exception {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.fromString("abcdef12-1234-5678-9012-345678901234");
    a.tenantId = "t1";
    a.decisionId = UUID.fromString("12345678-1234-5678-9012-abcdef123456");
    a.outcome = "REQUIRE_APPROVAL";
    a.requiredRole = nasty(255);
    a.principalId = "agent-42";
    a.status = GuardApprovalStatus.REJECTED;
    a.decidedBy = nasty(255);
    a.decidedAt = Instant.now();
    a.comment = "\"".repeat(1000);
    a.expiresAt = Instant.now().plusSeconds(3600);

    AuditEvent event = builder.approvalEvent(a, HASH);

    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(appenderLength(event)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(event.metadata()).containsEntry("decisionId", a.decisionId.toString())
        .containsEntry("approvalId", a.id.toString())
        .containsEntry("outcome", "REJECTED")
        .containsEntry("decidedBy", nasty(255))
        .containsEntry("requiredRole", nasty(255))
        .containsEntry("decisionReceiptHash", HASH)
        .containsEntry("comment", "\"".repeat(1000));
  }

  @Test
  void 审批事件超预算时只缩短comment() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("decisionId", "d");
    m.put("approvalId", "a");
    m.put("outcome", "REJECTED");
    m.put("decidedBy", "u".repeat(255));
    m.put("requiredRole", "r".repeat(255));
    m.put("comment", "\"".repeat(5000));
    m.put("decisionReceiptHash", HASH);

    Map<String, Object> fitted = builder.fitBudget(m, GuardReceiptBuilder.APPROVAL_SHRINK_ORDER);

    assertThat(GuardReceiptBuilder.APPROVAL_SHRINK_ORDER).containsExactly("comment");
    assertThat(builder.measure(fitted)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat((String) fitted.get("comment")).isNotEmpty().hasSizeLessThan(5000);
    assertThat(fitted).containsEntry("decisionId", "d").containsEntry("approvalId", "a")
        .containsEntry("outcome", "REJECTED").containsEntry("decidedBy", "u".repeat(255))
        .containsEntry("requiredRole", "r".repeat(255)).containsEntry("decisionReceiptHash", HASH);
  }

  @Test
  void 过期审批事件由系统发起且success为false() {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = "t1";
    a.decisionId = UUID.randomUUID();
    a.outcome = "ESCALATE";
    a.principalId = "agent-42";
    a.status = GuardApprovalStatus.EXPIRED;
    a.expiresAt = Instant.now();

    AuditEvent event = builder.approvalEvent(a, null);

    assertThat(event.performedBy()).isEqualTo("SYSTEM");
    assertThat(event.success()).isFalse();
    assertThat(event.metadata()).containsEntry("outcome", "EXPIRED")
        .doesNotContainKeys("requiredRole", "decidedBy", "comment", "decisionReceiptHash");
  }

  private GuardReceiptEntity receipt() {
    GuardReceiptEntity r = new GuardReceiptEntity();
    r.decisionId = UUID.randomUUID();
    r.tenantId = "t1";
    r.actionHash = HASH;
    r.kid = "k1";
    r.issuedAt = Instant.parse("2026-11-08T00:00:00Z");
    r.expiresAt = Instant.parse("2026-11-08T00:05:00Z");
    return r;
  }

  @Test
  void consume尝试事件键集合与success() {
    GuardReceiptEntity r = receipt();

    AuditEvent event = builder.consumeAttemptEvent(r, HASH, "att-1", "exec-1", HASH, "svc");

    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_CONSUME_ATTEMPT);
    assertThat(event.tenantId()).isEqualTo("t1");
    assertThat(event.performedBy()).isEqualTo("svc");
    assertThat(event.success()).isTrue();
    assertThat(event.metadata()).containsOnlyKeys("kind", "decisionId", "actionHash", "attemptId", "executorId",
        "kid", "receiptIssuedAt", "receiptExpiresAt", "decisionReceiptHash")
        .containsEntry("kind", "attempt")
        .containsEntry("decisionId", r.decisionId.toString())
        .containsEntry("receiptIssuedAt", "2026-11-08T00:00:00Z")
        .containsEntry("receiptExpiresAt", "2026-11-08T00:05:00Z")
        .doesNotContainKey("correlationId");
  }

  @Test
  void consume结果事件按outcome决定success() {
    UUID id = UUID.randomUUID();

    AuditEvent ok = builder.consumeOutcomeEvent("t1", id, "att-1", "exec-1", "consumed", HASH, "svc");
    AuditEvent rejected = builder.consumeOutcomeEvent("t1", id, "att-1", "exec-1", "rejected:receipt_expired", HASH, "svc");

    assertThat(ok.eventType()).isEqualTo(EventType.ACTION_GUARD_CONSUME_OUTCOME);
    assertThat(ok.tenantId()).isEqualTo("t1");
    assertThat(ok.success()).isTrue();
    assertThat(ok.metadata()).containsOnlyKeys("kind", "decisionId", "attemptId", "executorId", "outcome",
        "attemptReceiptHash").containsEntry("kind", "outcome").containsEntry("outcome", "consumed")
        .containsEntry("decisionId", id.toString());
    assertThat(rejected.success()).isFalse();
    assertThat(rejected.metadata()).containsEntry("outcome", "rejected:receipt_expired");
  }

  @Test
  void 缺租户时拒绝构造() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> builder.consumeOutcomeEvent(null, UUID.randomUUID(), "a", "e", "consumed", HASH, "svc"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void 摘要键超长时截断到64字符() {
    String longText = "x".repeat(500);
    GuardReceiptEntity r = receipt();
    ResultRequest req = new ResultRequest("a", "e", "executed", null, longText, null);

    assertThat(builder.consumeAttemptEvent(r, longText, "a", "e", longText, "svc").metadata())
        .containsEntry("actionHash", "x".repeat(64)).containsEntry("decisionReceiptHash", "x".repeat(64));
    assertThat(builder.consumeOutcomeEvent("t1", r.decisionId, "a", "e", "consumed", longText, "svc").metadata())
        .containsEntry("attemptReceiptHash", "x".repeat(64));
    assertThat(builder.resultEvent("t1", r.decisionId, req, longText, "svc").metadata())
        .containsEntry("resultHash", "x".repeat(64)).containsEntry("consumeReceiptHash", "x".repeat(64));
  }

  private ResultRequest result(String status, String errorCode) {
    return new ResultRequest("att-1", "exec-1", status, "2026-11-08T00:01:00Z", HASH, errorCode);
  }

  @Test
  void 执行结果事件键集合与success() {
    UUID id = UUID.randomUUID();

    AuditEvent ok = builder.resultEvent("t1", id, result("executed", null), HASH, "svc");
    AuditEvent failed = builder.resultEvent("t1", id, result("failed", "E_TIMEOUT"), HASH, "svc");

    assertThat(ok.eventType()).isEqualTo(EventType.ACTION_GUARD_RESULT);
    assertThat(ok.tenantId()).isEqualTo("t1");
    assertThat(ok.success()).isTrue();
    assertThat(ok.metadata()).containsOnlyKeys("decisionId", "attemptId", "executorId", "status", "finishedAt",
        "resultHash", "consumeReceiptHash").containsEntry("status", "executed")
        .doesNotContainKey("correlationId");
    assertThat(failed.success()).isFalse();
    assertThat(failed.metadata()).containsEntry("errorCode", "E_TIMEOUT");
  }

  @Test
  void 执行结果事件超预算时只删errorCode() {
    UUID id = UUID.randomUUID();
    ResultRequest req = new ResultRequest("a".repeat(255), "e".repeat(255), "failed", "2026-11-08T00:01:00Z", HASH,
        "\"".repeat(1900));

    AuditEvent event = builder.resultEvent("t1", id, req, HASH, "svc");

    assertThat(builder.measure(new LinkedHashMap<>(event.metadata()))).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(event.metadata()).containsEntry("attemptId", "a".repeat(255))
        .containsEntry("executorId", "e".repeat(255))
        .containsEntry("resultHash", HASH).containsEntry("consumeReceiptHash", HASH).containsEntry("status", "failed");
    assertThat(String.valueOf(event.metadata().get("errorCode")).length()).isLessThan(1900);
  }

  // ---- ADR 0041 §11 D1：输入来源键 ----

  private static InputSources sources(List<String> verified, List<String> issuers, Map<String, String> factSources,
                                      Boolean identityMatched, Instant expiresAt) {
    return new InputSources(new TreeSet<>(verified), new TreeSet<>(issuers), new TreeMap<>(factSources), identityMatched,
        expiresAt);
  }

  @Test
  void 决策事件写入输入来源键() {
    Instant expires = Instant.parse("2026-11-08T01:10:00Z");
    GuardDecisionDraft d = draft("ALLOW", "ok", List.of(), "agent-42", null).withInputSources(sources(
        List.of("principal.id", "context.amount"), List.of("idp.example.com#2026-11"),
        Map.of("context.amount", "ledger.amount"), true, expires));

    Map<String, Object> m = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1").metadata();

    assertThat(m).containsEntry("verifiedFields", List.of("context.amount", "principal.id"))
        .containsEntry("factIssuers", List.of("idp.example.com#2026-11"))
        .containsEntry("factSources", List.of("context.amount=ledger.amount"))
        .containsEntry("principalIdentityMatched", true)
        .containsEntry("factExpiresAt", "2026-11-08T01:10:00Z")
        .doesNotContainKey("inputSourcesTruncated");
  }

  /** 全 declared（无事实、非 user 主体）：一个新键都不写，与引入来源标记之前的收据键集相同。 */
  @Test
  void 无来源信息时不写任何来源键() {
    Map<String, Object> plain = builder.decisionEvent(draft("ALLOW", "ok", List.of(), "agent-42", null),
        UUID.randomUUID(), null, "user-1").metadata();
    Map<String, Object> unmatchedUser = builder.decisionEvent(draft("ALLOW", "ok", List.of(), "agent-42", null)
        .withInputSources(sources(List.of(), List.of(), Map.of(), false, null)), UUID.randomUUID(), null, "user-1")
        .metadata();

    assertThat(plain).doesNotContainKeys("verifiedFields", "factIssuers", "factSources", "principalIdentityMatched",
        "factExpiresAt", "inputSourcesTruncated");
    assertThat(unmatchedUser).containsEntry("principalIdentityMatched", false)
        .doesNotContainKeys("verifiedFields", "factIssuers", "factSources", "factExpiresAt", "inputSourcesTruncated");
  }

  /**
   * 64 条 128 字符路径（ADR 0041 §11.3 第 1 条）：缩短顺序 factIssuers → verifiedFields → factSources，
   * 列表每轮删末项，首次缩短即置 inputSourcesTruncated；身份与完整性键、principal / action / resource 键不动。
   */
  @Test
  void receiptTruncationOrderAndFlag() throws Exception {
    List<String> paths = IntStream.range(0, 64)
        .mapToObj(i -> "context." + String.format("%03d", i) + "x".repeat(128 - 11)).toList();
    Map<String, String> factSources = new TreeMap<>();
    paths.forEach(p -> factSources.put(p, "s".repeat(64)));
    List<String> issuers = IntStream.range(0, 8).mapToObj(i -> "issuer-" + i + ".example.com#kid-" + i).toList();
    GuardDecisionDraft base = draft("REQUIRE_APPROVAL", "r".repeat(255), List.of("GDPR:ART17"), "agent-42", "idem-1");
    GuardDecisionDraft d = base.withInputSources(sources(paths, issuers, factSources, true,
        Instant.parse("2026-11-08T01:10:00Z")));
    UUID decisionId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();

    AuditEvent event = builder.decisionEvent(d, decisionId, approvalId, "user-1");
    Map<String, Object> m = event.metadata();

    assertThat(paths).allSatisfy(p -> assertThat(p).hasSize(128));
    assertThat(builder.measure(new LinkedHashMap<>(m))).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(appenderLength(event)).isLessThanOrEqualTo(GuardReceiptBuilder.METADATA_BUDGET);
    assertThat(m).containsEntry("inputSourcesTruncated", true)
        .containsEntry("reason", "")
        .containsEntry("factIssuers", List.of())
        .containsEntry("verifiedFields", List.of())
        .containsEntry("principalIdentityMatched", true)
        .containsEntry("factExpiresAt", "2026-11-08T01:10:00Z")
        .containsEntry("decisionId", decisionId.toString())
        .containsEntry("approvalId", approvalId.toString())
        .containsEntry("contextHash", d.contextHash())
        .containsEntry("idempotencyKey", "idem-1")
        .containsEntry("principalId", "agent-42")
        .containsEntry("principalType", "agent")
        .containsEntry("actionName", "delete_customer_record")
        .containsEntry("resourceId", "agent-42")
        .containsEntry("agentModel", "claude-fable-5-1")
        .containsEntry("controls", List.of("GDPR:ART17"));
    @SuppressWarnings("unchecked")
    List<String> keptSources = (List<String>) m.get("factSources");
    List<String> allSources = factSources.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
    assertThat(keptSources).isNotEmpty().hasSizeLessThan(64).isEqualTo(allSources.subList(0, keptSources.size()));
  }

  /** 只缩到 verifiedFields 就达标时：factSources 原样保留，verifiedFields 是原列表的前缀。 */
  @Test
  void verifiedFieldsShrinksBeforeFactSources() {
    List<String> paths = IntStream.range(0, 40)
        .mapToObj(i -> "context." + String.format("%03d", i) + "x".repeat(128 - 11)).toList();
    GuardDecisionDraft d = draft("ALLOW", "ok", List.of(), "agent-42", null).withInputSources(sources(paths,
        List.of("idp.example.com#2026-11"), Map.of(paths.get(0), "ledger"), null, null));

    Map<String, Object> m = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1").metadata();

    @SuppressWarnings("unchecked")
    List<String> kept = (List<String>) m.get("verifiedFields");
    assertThat(m).containsEntry("inputSourcesTruncated", true).containsEntry("factIssuers", List.of())
        .containsEntry("factSources", List.of(paths.get(0) + "=ledger"));
    assertThat(kept).isNotEmpty().hasSizeLessThan(40).isEqualTo(paths.subList(0, kept.size()));
  }

  /** verifiedFields 至多 64 项（ADR 0041 §11 D1）：服务端解析的两项让总数超过 64 时截到 64 并置 truncated。 */
  @Test
  void verifiedFieldsCappedAt64() {
    List<String> paths = new ArrayList<>(IntStream.range(0, 64).mapToObj(i -> "context.k" + i).toList());
    paths.add("principal.id");
    paths.add("principal.roles");
    GuardDecisionDraft d = draft("ALLOW", "ok", List.of(), "agent-42", null)
        .withInputSources(sources(paths, List.of(), Map.of(), true, null));

    Map<String, Object> m = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1").metadata();

    assertThat((List<?>) m.get("verifiedFields")).hasSize(64);
    assertThat(m).containsEntry("inputSourcesTruncated", true);
  }
}
