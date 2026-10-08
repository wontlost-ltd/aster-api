package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    return new GuardDecisionDraft("t1", "guard.customer", "decide", idemKey, flat, mapper.contextHash(Map.of("amount", 250000)),
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
    GuardDecisionDraft d = new GuardDecisionDraft("t1", "guard.customer", "decide", text, mapper.flatten(a), contextHash,
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
        "principalRoles", "agentModel", "agentProvider", "principalType", "resourceType", "resourceId", "actionName",
        "principalId");
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
        d.idempotencyKey(), d.action(), d.contextHash(), d.policyVersion(), d.outcome(), d.role(), d.reason(),
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
        draft("DENY", null, List.of(), "p".repeat(255), null).action(), HASH,
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
}
