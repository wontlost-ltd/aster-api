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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

  /** 与 {@code req} 指纹一致（策略地址、主体、动作、资源、context 摘要）的已存决策行。 */
  private GuardDecisionEntity storedFor(GuardActionRequest req, GuardDecisionResponse response) throws Exception {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.policyModule = req.policyModule();
    e.policyFunction = req.policyFunction();
    e.principalId = req.action().principal().id();
    e.actionName = req.action().action().name();
    e.resourceType = req.action().resource().type();
    e.resourceId = req.action().resource().id();
    e.contextHash = new GuardActionMapper().contextHash(req.action().context());
    e.responseJson = json.writeValueAsString(response);
    return e;
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
    verify(store).saveDecision(captor.capture(), any());
    return captor.getValue();
  }

  /** 与决策同一次调用写入的待审批行（null 表示未创建）。 */
  private GuardApprovalEntity savedApproval() {
    ArgumentCaptor<GuardApprovalEntity> captor = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store).saveDecision(any(), captor.capture());
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

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), "idem-1");

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
    assertThat(savedApproval()).isNull();
  }

  @Test
  void 传入规则的参数是不可变Map() {
    verdict("ALLOW", null, null);
    service.decide(TENANT, "user-1", "ak-1", request(), null);

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

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("REQUIRE_APPROVAL");
    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.role()).isEqualTo("Data Protection Officer");
    assertThat(r.reason()).isEqualTo("customer record deletion");
    assertThat(r.approval().status()).isEqualTo("PENDING");
    assertThat(r.approval().requiredRole()).isEqualTo("Data Protection Officer");
    Instant expiresAt = Instant.parse(r.approval().expiresAt());
    assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));

    InOrder order = inOrder(appender, store);
    order.verify(appender).appendSync(any());
    order.verify(store).saveDecision(any(), any());
    GuardApprovalEntity a = savedApproval();
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

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.approval().requiredRole()).isNull();
    assertThat(savedApproval().outcome).isEqualTo("ESCALATE");
    assertThat(savedApproval().requiredRole).isNull();
  }

  @Test
  void Verdict_deny_DENY无reasonCode() {
    verdict("DENY", null, "blocked");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isNull();
    assertThat(r.reason()).isEqualTo("blocked");
    assertThat(savedApproval()).isNull();
  }

  @Test
  void 非Verdict结果_DENY_not_a_verdict() {
    PolicyEvaluationResult result = mock(PolicyEvaluationResult.class);
    when(result.getResult()).thenReturn(Boolean.TRUE);
    when(result.getDecision()).thenReturn(null);
    when(result.getControls()).thenReturn(List.of());
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenReturn(Uni.createFrom().item(result));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.receipt()).isNotNull();
    assertThat(appendedEvent().success()).isFalse();
    assertThat(savedDecision().reasonCode).isEqualTo("not_a_verdict");
    assertThat(savedApproval()).isNull();
  }

  @Test
  void 未知outcome的Verdict按not_a_verdict拒绝() {
    verdict("MAYBE", null, null);
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
  }

  @Test
  void 评估抛异常_DENY_evaluation_failed() {
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("策略执行失败: boom")));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("evaluation_failed");
    assertThat(r.reason()).isNull();
    assertThat(savedDecision().reasonCode).isEqualTo("evaluation_failed");
    assertThat(savedApproval()).isNull();
  }

  @Test
  void 评估同步抛异常同样fail_closed() {
    when(evaluationService.evaluatePolicy(eq(TENANT), eq(MODULE), eq(FUNCTION), any()))
        .thenThrow(new IllegalStateException("boom"));
    assertThat(service.decide(TENANT, "user-1", "ak-1", request(), null).reasonCode()).isEqualTo("evaluation_failed");
  }

  @Test
  void 收据写入失败_DENY_receipt_unavailable且不创建待审批() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", new RuntimeException()));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("receipt_unavailable");
    assertThat(r.receipt()).isNull();
    assertThat(r.approval()).isNull();
    assertThat(r.role()).isNull();
    assertThat(savedApproval()).isNull();
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
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(storedFor(request(), stored)));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), "idem-1");

    assertThat(r).isEqualTo(stored);
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any(), any());
  }

  @Test
  void 同一幂等键用于不同动作报idempotency_mismatch且不评估() throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", "ALLOW",
        null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV), null, "R");
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(storedFor(request(), stored)));
    GuardActionRequest base = request();
    Action a = base.action();
    List<GuardActionRequest> others = List.of(
        new GuardActionRequest(MODULE, FUNCTION, new Action(a.principal(), new ActionName("read_customer_record"),
            a.resource(), a.context(), null), null),
        new GuardActionRequest(MODULE, FUNCTION, new Action(new Principal("agent-43", null, List.of("ops-bot")),
            a.action(), a.resource(), a.context(), null), null),
        new GuardActionRequest(MODULE, FUNCTION, new Action(a.principal(), a.action(),
            new Resource("customer", "C-1002"), a.context(), null), null),
        new GuardActionRequest(MODULE, FUNCTION, new Action(a.principal(), a.action(), a.resource(),
            Map.of("amount", 1), null), null),
        new GuardActionRequest("guard.other", FUNCTION, a, null));

    for (GuardActionRequest other : others) {
      assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", other, "idem-1"))
          .isInstanceOf(GuardIdempotencyMismatchException.class);
    }
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any(), any());
  }

  @Test
  void 指纹不含principal_type_roles与agent_model() throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", "ALLOW",
        null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV), null, "R");
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(storedFor(request(), stored)));
    Action a = request().action();
    GuardActionRequest sameAction = new GuardActionRequest(MODULE, FUNCTION, new Action(
        new Principal("agent-42", "service", List.of("other-role")), a.action(), a.resource(), a.context(),
        new GuardActionRequest.Agent("other-model", "s-2")), null);

    assertThat(service.decide(TENANT, "user-1", "ak-1", sameAction, "idem-1")).isEqualTo(stored);
  }

  @Test
  void 决策行记录请求方用户与API_key() {
    verdict("ALLOW", null, null);

    service.decide(TENANT, "user-1", "ak-1", request(), null);

    GuardDecisionEntity e = savedDecision();
    assertThat(e.requestedBy).isEqualTo("user-1");
    assertThat(e.requestedApiKeyId).isEqualTo("ak-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void 并发同键竞争失败时返回先到者() throws Exception {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    GuardDecisionResponse winner = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "DENY", "DENY",
        null, null, null, null, null, null);
    GuardDecisionEntity e = storedFor(request(), winner);
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.empty(), Optional.of(e));
    doThrow(new GuardIdempotencyRaceException(new RuntimeException("23505"))).when(store).saveDecision(any(), any());

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), "idem-1");

    assertThat(r).isEqualTo(winner);
    // 本请求的待审批行与决策行在同一事务里，冲突时一起回滚，不会留下孤儿审批。
    assertThat(savedApproval()).isNotNull();
  }

  @Test
  void 合并写入失败时异常外抛且审批id不泄露() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    IllegalStateException down = new IllegalStateException("db down");
    doThrow(down).doNothing().when(store).saveDecision(any(), any());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), "idem-1")).isSameAs(down);
    GuardDecisionResponse retry = service.decide(TENANT, "user-1", "ak-1", request(), "idem-1");

    ArgumentCaptor<GuardApprovalEntity> approvals = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store, org.mockito.Mockito.times(2)).saveDecision(any(), approvals.capture());
    UUID failedApprovalId = approvals.getAllValues().get(0).id;
    assertThat(failedApprovalId).isNotNull();
    assertThat(retry.approval().id()).isNotEqualTo(failedApprovalId.toString());
    assertThat(retry.approval().id()).isEqualTo(approvals.getAllValues().get(1).id.toString());
  }

  @Test
  void Verdict的role超长按not_a_verdict拒绝() {
    verdict("REQUIRE_APPROVAL", "r".repeat(256), "customer record deletion");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.approval()).isNull();
    assertThat(r.role()).isNull();
    assertThat(savedApproval()).isNull();
  }

  @Test
  void Verdict的reason超长按not_a_verdict拒绝() {
    verdict("ALLOW", null, "x".repeat(256));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.reason()).isNull();
    assertThat(savedDecision().reason).isNull();
  }

  @Test
  void Verdict的role与reason恰为255可接受() {
    verdict("REQUIRE_APPROVAL", "r".repeat(255), "x".repeat(255));
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);
    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(savedApproval().requiredRole).hasSize(255);
  }

  @Test
  void 空白幂等键视为未提供() {
    verdict("ALLOW", null, null);

    service.decide(TENANT, "user-1", "ak-1", request(), "   ");

    verify(store, never()).findByIdempotencyKey(anyString(), anyString());
    assertThat(savedDecision().idempotencyKey).isNull();
    assertThat(appendedEvent().metadata()).doesNotContainKey("idempotencyKey");
  }

  @Test
  void 幂等键超长或含控制字符报invalid_action且不评估() {
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), "k".repeat(256)))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), "idem\n1"))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), "idem\u00071"))
        .isInstanceOf(GuardInvalidActionException.class);
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).findByIdempotencyKey(anyString(), anyString());
    verify(store, never()).saveDecision(any(), any());
  }

  @Test
  void 幂等键恰为255可用() {
    verdict("ALLOW", null, null);
    service.decide(TENANT, "user-1", "ak-1", request(), "k".repeat(255));
    assertThat(savedDecision().idempotencyKey).hasSize(255);
  }

  @Test
  void 策略不存在_GuardPolicyNotFoundException() {
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), null))
        .isInstanceOf(GuardPolicyNotFoundException.class)
        .extracting(ex -> ((GuardPolicyNotFoundException) ex).reasonCode()).isEqualTo(GuardReasonCode.POLICY_NOT_FOUND);
    verifyNoInteractions(evaluationService, appender);
    verify(store, never()).saveDecision(any(), any());
  }

  @Test
  void 无活跃版本_GuardPolicyNotFoundException() {
    PolicyCatalog catalog = new PolicyCatalog();
    catalog.id = UUID.randomUUID();
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.of(catalog));
    when(repository.findActiveVersion(catalog.id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), null)).isInstanceOf(GuardPolicyNotFoundException.class);
  }

  @Test
  void 请求缺策略地址或action报invalid_action() {
    GuardActionRequest noModule = new GuardActionRequest(" ", FUNCTION, request().action(), null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", noModule, null)).isInstanceOf(GuardInvalidActionException.class);
    GuardActionRequest noAction = new GuardActionRequest(MODULE, FUNCTION, null, null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", noAction, null)).isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", null, null)).isInstanceOf(GuardInvalidActionException.class);
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

  /** 已落库的 PENDING 决策行（含审批视图），并挂到 store 上。 */
  private GuardApprovalEntity storedPending(UUID decisionId, Instant expiresAt, GuardApprovalStatus status) throws Exception {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = TENANT;
    a.decisionId = decisionId;
    a.status = status;
    a.expiresAt = expiresAt;
    GuardDecisionResponse stored = new GuardDecisionResponse(
        new GuardDecisionResponse.ApprovalView(expiresAt.toString(), a.id.toString(), "DPO", "PENDING"),
        List.of(), decisionId.toString(), "PENDING", "REQUIRE_APPROVAL", null, null, null, null, "DPO", null);
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = decisionId;
    e.responseJson = json.writeValueAsString(stored);
    when(store.findDecision(TENANT, decisionId)).thenReturn(Optional.of(e));
    return a;
  }

  @Test
  void get对PENDING且已过期返回DENY且不写库() throws Exception {
    UUID id = UUID.randomUUID();
    GuardApprovalEntity a = storedPending(id, Instant.now().minusSeconds(1), GuardApprovalStatus.PENDING);
    when(store.findApprovalByDecision(id)).thenReturn(Optional.of(a));

    GuardDecisionResponse r = service.get(TENANT, id).orElseThrow();

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.approval().status()).isEqualTo("EXPIRED");
    verify(store, never()).completeApproval(any(), any());
  }

  @Test
  void get对PENDING未过期仍为PENDING() throws Exception {
    UUID id = UUID.randomUUID();
    GuardApprovalEntity a = storedPending(id, Instant.now().plusSeconds(600), GuardApprovalStatus.PENDING);
    when(store.findApprovalByDecision(id)).thenReturn(Optional.of(a));

    GuardDecisionResponse r = service.get(TENANT, id).orElseThrow();

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.approval().status()).isEqualTo("PENDING");
  }

  @Test
  void get对PENDING但无审批行按DENY() throws Exception {
    UUID id = UUID.randomUUID();
    storedPending(id, Instant.now().plusSeconds(600), GuardApprovalStatus.PENDING);
    when(store.findApprovalByDecision(id)).thenReturn(Optional.empty());

    assertThat(service.get(TENANT, id).orElseThrow().effectiveOutcome()).isEqualTo("DENY");
  }

  @Test
  void waitSeconds钳制到0到30() {
    assertThat(GuardDecisionService.clampWaitSeconds(null)).isZero();
    assertThat(GuardDecisionService.clampWaitSeconds(-5)).isZero();
    assertThat(GuardDecisionService.clampWaitSeconds(7)).isEqualTo(7);
    assertThat(GuardDecisionService.clampWaitSeconds(31)).isEqualTo(30);
    assertThat(GuardDecisionService.clampWaitSeconds(Integer.MAX_VALUE)).isEqualTo(30);
  }

  private static GuardActionRequest requestWaiting(int waitSeconds) {
    GuardActionRequest base = request();
    return new GuardActionRequest(base.policyModule(), base.policyFunction(), base.action(), waitSeconds);
  }

  @Test
  void waitSeconds期间被批准则返回ALLOW() throws Exception {
    verdict("REQUIRE_APPROVAL", "DPO", "needs review");
    // 决策落库后，回读到的审批行已被批准
    AtomicReference<GuardDecisionEntity> decision = new AtomicReference<>();
    AtomicReference<GuardApprovalEntity> approval = new AtomicReference<>();
    doAnswer(inv -> {
      decision.set(inv.getArgument(0));
      approval.set(inv.getArgument(1));
      approval.get().status = GuardApprovalStatus.APPROVED;
      return null;
    }).when(store).saveDecision(any(), any());
    when(store.findDecision(eq(TENANT), any())).thenAnswer(inv -> Optional.ofNullable(decision.get()));
    when(store.findApprovalByDecision(any())).thenAnswer(inv -> Optional.ofNullable(approval.get()));
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(5), null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(r.approval().status()).isEqualTo("APPROVED");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void waitSeconds超时仍为PENDING() throws Exception {
    verdict("REQUIRE_APPROVAL", "DPO", "needs review");
    AtomicReference<GuardDecisionEntity> decision = new AtomicReference<>();
    AtomicReference<GuardApprovalEntity> approval = new AtomicReference<>();
    doAnswer(inv -> {
      decision.set(inv.getArgument(0));
      approval.set(inv.getArgument(1));
      return null;
    }).when(store).saveDecision(any(), any());
    when(store.findDecision(eq(TENANT), any())).thenAnswer(inv -> Optional.ofNullable(decision.get()));
    when(store.findApprovalByDecision(any())).thenAnswer(inv -> Optional.ofNullable(approval.get()));
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(1), null);

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
  }

  @Test
  void waitSeconds对非PENDING结果不等待() {
    verdict("ALLOW", null, null);
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(30), null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    verify(store, never()).findDecision(any(), any());
  }

  @Test
  void 响应序列化字母序且省略空字段() throws Exception {
    verdict("ALLOW", null, null);
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), null);

    String body = json.writeValueAsString(r);

    assertThat(body).startsWith("{\"controls\":").doesNotContain("approval").doesNotContain("reasonCode")
        .doesNotContain("null");
    assertThat(body.indexOf("\"decisionId\"")).isLessThan(body.indexOf("\"effectiveOutcome\""));
    assertThat(body.indexOf("\"policyVersion\"")).isLessThan(body.indexOf("\"receipt\""));
    assertThat(body).contains("\"policyVersion\":{\"id\":1730890123456,\"sourceHash\":\"" + "d".repeat(64) + "\",\"version\":3}");
  }
}
