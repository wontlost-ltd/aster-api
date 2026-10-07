package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Agent;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0040 §5.2：决策 / 审批收据事件的字段映射与 metadata 4 KB 上限。 */
class GuardReceiptBuilderTest {
  private static final String HASH = "a".repeat(64);
  private final GuardActionMapper mapper = new GuardActionMapper();
  private final GuardReceiptBuilder builder = new GuardReceiptBuilder();
  private final ObjectMapper json = new ObjectMapper();

  private GuardDecisionDraft draft(String outcome, String reason, List<String> controls, String id, String idemKey) {
    Action a = new Action(new Principal(id, "agent", List.of("ops-bot")), new ActionName("delete_customer_record"),
        new Resource("customer", id), Map.of("amount", 250000), new Agent("claude-fable-5-1", "s-1"));
    Map<String, Object> flat = mapper.flatten(a);
    return new GuardDecisionDraft("t1", "guard.customer", "decide", idemKey, flat, mapper.contextHash(Map.of("amount", 250000)),
        new PolicyVersionView(1730890123456L, HASH, 3L), outcome, "Data Protection Officer", reason,
        "CUST-DEL-001", controls, null);
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
        "principalId", "principalType", "actionName", "resourceType", "resourceId", "contextHash", "agentModel",
        "policyVersionId", "sourceHash", "approvalId", "idempotencyKey", "reason");
    assertThat(event.metadata()).containsEntry("decisionId", decisionId.toString())
        .containsEntry("approvalId", approvalId.toString())
        .containsEntry("outcome", "REQUIRE_APPROVAL")
        .containsEntry("effectiveOutcome", "PENDING")
        .containsEntry("controls", List.of("GDPR:ART17", "SOX:404"))
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

  @Test
  void 最坏情况metadata仍小于4096() throws Exception {
    List<String> controls = IntStream.range(0, 10).mapToObj(i -> "CONTROL-" + i + "-" + "c".repeat(40)).toList();
    GuardDecisionDraft d = draft("REQUIRE_APPROVAL", "r".repeat(255), controls, "p".repeat(255), "k".repeat(255));

    AuditEvent event = builder.decisionEvent(d, UUID.randomUUID(), UUID.randomUUID(), "u".repeat(255));

    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThan(4096);
    assertThat(event.performedBy()).hasSizeLessThanOrEqualTo(100);
  }

  @Test
  void 规则侧超长文本在收据中被截断() throws Exception {
    List<String> controls = IntStream.range(0, 40).mapToObj(i -> "x".repeat(300)).toList();
    GuardDecisionDraft d = new GuardDecisionDraft("t1", "guard.customer", "decide", "k".repeat(255),
        draft("DENY", null, List.of(), "p".repeat(255), null).action(), HASH,
        new PolicyVersionView(Long.MAX_VALUE, HASH, Long.MAX_VALUE), "DENY", "o".repeat(5000), "r".repeat(5000),
        "i".repeat(5000), controls, null);

    AuditEvent event = builder.decisionEvent(d, UUID.randomUUID(), null, "user-1");

    assertThat(json.writeValueAsString(event.metadata()).length()).isLessThan(4096);
    assertThat((String) event.metadata().get("reason")).hasSize(255);
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
