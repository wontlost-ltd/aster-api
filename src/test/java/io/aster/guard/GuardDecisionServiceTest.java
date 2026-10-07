package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.repository.PolicySourceRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR 0040 §3.2 fail-closed 映射、幂等、收据与待审批的编排（纯 JUnit + Mockito，不启动 Quarkus）。 */
class GuardDecisionServiceTest {
  private static final String TENANT = "tenant-a";
  private static final String MODULE = "guard.customer";
  private static final String FUNCTION = "decide";
  private static final String HASH = "b".repeat(64);
  private static final String PREV = "c".repeat(64);

  private PolicyEvaluationService evaluationService;
  private PolicySourceRepository repository;
  private AuditChainAppender appender;
  private GuardDecisionStore store;
  private final ObjectMapper json = new ObjectMapper();
  private GuardDecisionService service;

  @BeforeEach
  void setUp() {
    evaluationService = mock(PolicyEvaluationService.class);
    repository = mock(PolicySourceRepository.class);
    appender = mock(AuditChainAppender.class);
    store = mock(GuardDecisionStore.class);
    service = new GuardDecisionService(evaluationService, repository, appender, store, new GuardActionMapper(),
        new GuardReceiptBuilder(), json, Duration.ofHours(24), Supplier::get);
    PolicyCatalog catalog = new PolicyCatalog();
    catalog.id = UUID.randomUUID();
    PolicyVersion version = new PolicyVersion();
    version.id = 1730890123456L;
    version.version = 3L;
    version.sourceHash = "d".repeat(64);
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.of(catalog));
    when(repository.findActiveVersion(catalog.id)).thenReturn(Optional.of(version));
    when(store.findByIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());
    when(appender.appendSync(any())).thenReturn(new AppendedRecord(88213L, HASH, PREV));
  }

  private static GuardActionRequest request() {
    Action a = new Action(new Principal("agent-42", null, List.of("ops-bot")), new ActionName("delete_customer_record"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000), null);
    return new GuardActionRequest(MODULE, FUNCTION, a, null);
  }

  private void verdict(String outcome, String role, String reason) {
    Map<String, Object> raw = Map.of("__type", "Verdict", "outcome", outcome);
    PolicyEvaluationResult result = new PolicyEvaluationResult(raw, 1.0, false,
        new VerdictView(outcome, role, reason), "CUST-DEL-001", List.of("GDPR:ART17"));
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenReturn(Uni.createFrom().item(result));
  }

  private GuardDecisionEntity savedDecision() {
    ArgumentCaptor<GuardDecisionEntity> captor = ArgumentCaptor.forClass(GuardDecisionEntity.class);
    verify(store).saveDecision(captor.capture());
    return captor.getValue();
  }

  private AuditEvent appendedEvent() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(appender).appendSync(captor.capture());
    return captor.getValue();
  }

  @Test
  void Verdict_allow_ALLOW且写收据与决策() throws Exception {
    verdict("ALLOW", null, null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), "idem-1");

    assertThat(r.outcome()).isEqualTo("ALLOW");
    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(r.reasonCode()).isNull();
    assertThat(r.approval()).isNull();
    assertThat(r.ruleId()).isEqualTo("CUST-DEL-001");
    assertThat(r.controls()).containsExactly("GDPR:ART17");
    assertThat(r.receipt()).isEqualTo(new GuardDecisionResponse.ReceiptView(88213L, HASH, PREV));
    assertThat(r.policyVersion()).isEqualTo(new GuardDecisionResponse.PolicyVersionView(1730890123456L, "d".repeat(64), 3L));

    AuditEvent event = appendedEvent();
    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_DECISION);
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.success()).isTrue();
    assertThat(event.metadata()).containsEntry("decisionId", r.decisionId()).containsEntry("idempotencyKey", "idem-1");

    GuardDecisionEntity e = savedDecision();
    assertThat(e.id.toString()).isEqualTo(r.decisionId());
    assertThat(e.tenantId).isEqualTo(TENANT);
    assertThat(e.idempotencyKey).isEqualTo("idem-1");
    assertThat(e.outcome).isEqualTo("ALLOW");
    assertThat(e.principalId).isEqualTo("agent-42");
    assertThat(e.actionName).isEqualTo("delete_customer_record");
    assertThat(e.resourceType).isEqualTo("customer");
    assertThat(e.resourceId).isEqualTo("C-1001");
    assertThat(e.contextHash).isEqualTo(new GuardActionMapper().contextHash(Map.of("amount", 250000)));
    assertThat(e.policyVersionId).isEqualTo(1730890123456L);
    assertThat(e.sourceHash).isEqualTo("d".repeat(64));
    assertThat(e.receiptAuditId).isEqualTo(88213L);
    assertThat(e.receiptHash).isEqualTo(HASH);
    assertThat(json.readValue(e.controls, String[].class)).containsExactly("GDPR:ART17");
    assertThat(json.readValue(e.responseJson, GuardDecisionResponse.class)).isEqualTo(r);
    verify(store, never()).saveApproval(any());
  }

  @Test
  void 传入规则的参数是不可变Map() {
    verdict("ALLOW", null, null);
    service.decide(TENANT, "user-1", request(), null);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(evaluationService).evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), args.capture());
    assertThat(args.getValue()).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> flat = (Map<String, Object>) args.getValue()[0];
    assertThat(flat).containsEntry("principal_id", "agent-42").containsEntry("action", "delete_customer_record");
    assertThatThrownBy(() -> flat.put("action", "x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void Verdict_require_approval_PENDING且创建待审批() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    Instant before = Instant.now();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.outcome()).isEqualTo("REQUIRE_APPROVAL");
    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.role()).isEqualTo("Data Protection Officer");
    assertThat(r.reason()).isEqualTo("customer record deletion");
    assertThat(r.approval().status()).isEqualTo("PENDING");
    assertThat(r.approval().requiredRole()).isEqualTo("Data Protection Officer");
    Instant expiresAt = Instant.parse(r.approval().expiresAt());
    assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));

    ArgumentCaptor<GuardApprovalEntity> captor = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    InOrder order = inOrder(appender, store);
    order.verify(appender).appendSync(any());
    order.verify(store).saveDecision(any());
    order.verify(store).saveApproval(captor.capture());
    GuardApprovalEntity a = captor.getValue();
    assertThat(a.id.toString()).isEqualTo(r.approval().id());
    assertThat(a.decisionId.toString()).isEqualTo(r.decisionId());
    assertThat(a.tenantId).isEqualTo(TENANT);
    assertThat(a.outcome).isEqualTo("REQUIRE_APPROVAL");
    assertThat(a.requiredRole).isEqualTo("Data Protection Officer");
    assertThat(a.principalId).isEqualTo("agent-42");
    assertThat(a.status).isEqualTo(GuardApprovalStatus.PENDING);
    assertThat(a.expiresAt).isEqualTo(expiresAt);
    assertThat(appendedEvent().metadata()).containsEntry("approvalId", r.approval().id());
    assertThat(savedDecision().requiredRole).isEqualTo("Data Protection Officer");
  }

  @Test
  void Verdict_escalate_PENDING且待审批无指定角色() {
    verdict("ESCALATE", null, "unusual amount");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.approval().requiredRole()).isNull();
    ArgumentCaptor<GuardApprovalEntity> captor = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store).saveApproval(captor.capture());
    assertThat(captor.getValue().outcome).isEqualTo("ESCALATE");
    assertThat(captor.getValue().requiredRole).isNull();
  }

  @Test
  void Verdict_deny_DENY无reasonCode() {
    verdict("DENY", null, "blocked");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isNull();
    assertThat(r.reason()).isEqualTo("blocked");
    verify(store, never()).saveApproval(any());
  }

  @Test
  void 非Verdict结果_DENY_not_a_verdict() {
    PolicyEvaluationResult result = mock(PolicyEvaluationResult.class);
    when(result.getResult()).thenReturn(Boolean.TRUE);
    when(result.getDecision()).thenReturn(null);
    when(result.getControls()).thenReturn(List.of());
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenReturn(Uni.createFrom().item(result));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.receipt()).isNotNull();
    assertThat(appendedEvent().success()).isFalse();
    assertThat(savedDecision().reasonCode).isEqualTo("not_a_verdict");
    verify(store, never()).saveApproval(any());
  }

  @Test
  void 未知outcome的Verdict按not_a_verdict拒绝() {
    verdict("MAYBE", null, null);
    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
  }

  @Test
  void 评估抛异常_DENY_evaluation_failed() {
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("策略执行失败: boom")));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("evaluation_failed");
    assertThat(r.reason()).isNull();
    assertThat(savedDecision().reasonCode).isEqualTo("evaluation_failed");
    verify(store, never()).saveApproval(any());
  }

  @Test
  void 评估同步抛异常同样fail_closed() {
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenThrow(new IllegalStateException("boom"));
    assertThat(service.decide(TENANT, "user-1", request(), null).reasonCode()).isEqualTo("evaluation_failed");
  }

  @Test
  void 收据写入失败_DENY_receipt_unavailable且不创建待审批() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", new RuntimeException()));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("receipt_unavailable");
    assertThat(r.receipt()).isNull();
    assertThat(r.approval()).isNull();
    assertThat(r.role()).isNull();
    verify(store, never()).saveApproval(any());
    GuardDecisionEntity e = savedDecision();
    assertThat(e.outcome).isEqualTo("DENY");
    assertThat(e.reasonCode).isEqualTo("receipt_unavailable");
    assertThat(e.receiptAuditId).isNull();
    assertThat(e.receiptHash).isNull();
    assertThat(e.requiredRole).isNull();
  }

  @Test
  void 幂等键命中返回存储响应() throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", "ALLOW",
        null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV), null, "R");
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.responseJson = json.writeValueAsString(stored);
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(e));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), "idem-1");

    assertThat(r).isEqualTo(stored);
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void 并发同键竞争失败时返回先到者() throws Exception {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    GuardDecisionResponse winner = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "DENY", "DENY",
        null, null, null, null, null, null);
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.responseJson = json.writeValueAsString(winner);
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.empty(), Optional.of(e));
    doThrow(new GuardIdempotencyRaceException(new RuntimeException("23505"))).when(store).saveDecision(any());

    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), "idem-1");

    assertThat(r).isEqualTo(winner);
    verify(store, never()).saveApproval(any());
  }

  @Test
  void 策略不存在_GuardPolicyNotFoundException() {
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", request(), null))
        .isInstanceOf(GuardPolicyNotFoundException.class)
        .extracting(ex -> ((GuardPolicyNotFoundException) ex).reasonCode()).isEqualTo(GuardReasonCode.POLICY_NOT_FOUND);
    verifyNoInteractions(evaluationService, appender);
    verify(store, never()).saveDecision(any());
  }

  @Test
  void 无活跃版本_GuardPolicyNotFoundException() {
    PolicyCatalog catalog = new PolicyCatalog();
    catalog.id = UUID.randomUUID();
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.of(catalog));
    when(repository.findActiveVersion(catalog.id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", request(), null)).isInstanceOf(GuardPolicyNotFoundException.class);
  }

  @Test
  void 请求缺策略地址或action报invalid_action() {
    GuardActionRequest noModule = new GuardActionRequest(" ", FUNCTION, request().action(), null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", noModule, null)).isInstanceOf(GuardInvalidActionException.class);
    GuardActionRequest noAction = new GuardActionRequest(MODULE, FUNCTION, null, null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", noAction, null)).isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", null, null)).isInstanceOf(GuardInvalidActionException.class);
    verifyNoInteractions(evaluationService, appender);
  }

  @Test
  void get返回存储响应且跨租户为空() throws Exception {
    UUID id = UUID.randomUUID();
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), id.toString(), "ALLOW", "ALLOW",
        null, null, null, null, null, null);
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.responseJson = json.writeValueAsString(stored);
    when(store.findDecision(TENANT, id)).thenReturn(Optional.of(e));
    when(store.findDecision("other", id)).thenReturn(Optional.empty());

    assertThat(service.get(TENANT, id)).contains(stored);
    assertThat(service.get("other", id)).isEmpty();
  }

  @Test
  void 响应序列化字母序且省略空字段() throws Exception {
    verdict("ALLOW", null, null);
    GuardDecisionResponse r = service.decide(TENANT, "user-1", request(), null);

    String body = json.writeValueAsString(r);

    assertThat(body).startsWith("{\"controls\":").doesNotContain("approval").doesNotContain("reasonCode")
        .doesNotContain("null");
    assertThat(body.indexOf("\"decisionId\"")).isLessThan(body.indexOf("\"effectiveOutcome\""));
    assertThat(body.indexOf("\"policyVersion\"")).isLessThan(body.indexOf("\"receipt\""));
    assertThat(body).contains("\"policyVersion\":{\"id\":1730890123456,\"sourceHash\":\"" + "d".repeat(64) + "\",\"version\":3}");
  }
}
