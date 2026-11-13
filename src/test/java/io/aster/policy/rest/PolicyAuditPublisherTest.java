package io.aster.policy.rest;

import io.aster.audit.chain.AuditMetadataSize;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.evidence.EvaluationEvidence;
import io.aster.policy.rest.model.EvaluationRequest;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** ADR 0041 §2.2：证据键并入 POLICY_EVALUATION 事件 metadata；无证据时与旧行为逐字一致。 */
class PolicyAuditPublisherTest {

  private PolicyAuditPublisher publisher;
  private Event<AuditEvent> events;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    publisher = new PolicyAuditPublisher();
    events = mock(Event.class);
    publisher.auditEventPublisher = events;
  }

  private static EvaluationRequest request() {
    return new EvaluationRequest("mod", "fn", new Object[]{Map.of("applicantId", "A")}, null);
  }

  private AuditEvent fired() {
    ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
    verify(events).fireAsync(cap.capture());
    return cap.getValue();
  }

  @Test
  void 证据键并入metadata且覆盖同名键() {
    EvaluationEvidence e = new EvaluationEvidence("c-1", "ALLOW", "R-1", List.of("X"), null,
        new AgentIdentity("p", "m", null, null), null, null);
    publisher.publish("t", request(), "u", true, 5L, null, Map.of("contextSize", 1, "outcome", "stale"), e);

    assertThat(fired().metadata())
        .containsEntry("correlationId", "c-1")
        .containsEntry("outcome", "ALLOW")
        .containsEntry("ruleId", "R-1")
        .containsEntry("controls", List.of("X"))
        .containsEntry("contextSize", 1)
        .containsEntry("agentProvider", "p")
        .containsEntry("agentModel", "m")
        .containsEntry("agentSource", "declared")
        .doesNotContainKey("policyVersionId");
  }

  @Test
  void evidence为null时行为不变() {
    Map<String, Object> md = Map.of("contextSize", 1, "applicantId", "A");
    publisher.publish("t", request(), "u", false, 7L, "boom", md);

    AuditEvent ev = fired();
    assertThat(ev.metadata()).isEqualTo(md);
    assertThat(ev.metadata()).doesNotContainKey("correlationId");
    assertThat(ev.tenantId()).isEqualTo("t");
    assertThat(ev.policyModule()).isEqualTo("mod");
    assertThat(ev.policyFunction()).isEqualTo("fn");
  }

  /** aster-api#493：/evaluate 的 applicantId 来自请求 context、没有长度上限，发布前经预算预检截到 255。 */
  @Test
  void 超长applicantId发布前截到上限且整份metadata在预算内() {
    EvaluationRequest req = new EvaluationRequest("mod", "fn",
        new Object[]{Map.of("applicantId", "1.1.1.1 ".repeat(5_000))}, null);
    Map<String, Object> md = publisher.buildMetadata(req);
    EvaluationEvidence e = EvaluationEvidence.of("REQUIRE_APPROVAL", "R-1", List.of("X"), null, null, "DPO", "r");

    publisher.publish("t", req, "u", true, 5L, null, md, e);

    Map<String, Object> fired = fired().metadata();
    assertThat((String) fired.get("applicantId")).hasSize(255);
    assertThat(fired).containsEntry("role", "DPO").containsEntry("outcome", "REQUIRE_APPROVAL")
        .containsEntry("metadataDegraded", List.of("applicantId"));
    assertThat(AuditMetadataSize.measure(fired)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
  }

  @Test
  void 无证据时超长applicantId同样截断() {
    Map<String, Object> md = Map.of("contextSize", 1, "applicantId", "y".repeat(10_000));
    publisher.publish("t", request(), "u", false, 7L, "boom", md);

    assertThat((String) fired().metadata().get("applicantId")).hasSize(255);
  }

  @Test
  void metadata为null且无证据时发布空map() {
    publisher.publish("t", request(), "u", true, 1L, null, null, null);
    assertThat(fired().metadata()).isEmpty();
  }

  @Test
  void 未注入事件总线时静默返回() {
    publisher.auditEventPublisher = null;
    publisher.publish("t", request(), "u", true, 1L, null, Map.of(),
        EvaluationEvidence.of("ALLOW", null, null, null, null));
  }
}
