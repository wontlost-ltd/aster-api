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
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.FactVerifier;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.guard.provenance.InputSourceResolver;
import io.aster.guard.receipt.ExecutionReceipt;
import io.aster.guard.receipt.ExecutionReceiptIssuer;
import io.aster.guard.receipt.ExecutionReceiptSigner;
import io.aster.guard.receipt.ExecutionReceiptUnavailableException;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.receipt.ReceiptSigningKeys;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.governance.RuleMetadata;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.repository.PolicySourceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
import static org.mockito.Mockito.times;
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
  /** 请求体 action 原文的 jcs-sha256/v1（由资源层算好传入，服务只透传与签发）。 */
  private static final String ACTION_HASH = "e".repeat(64);
  /** 活跃版本（setUp 中 version.id）的字符串形式：元数据与评估都按它固定。 */
  private static final String VERSION_ID = "1730890123456";
  private static final Instant NOW = Instant.parse("2026-11-08T01:00:00.987654Z");
  private static final String KEYS = "{\"revision\":1,\"keys\":["
      + "{\"kid\":\"k1\",\"privateKey\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE\"}]}";

  private PolicyEvaluationService evaluationService;
  private PolicySourceRepository repository;
  private AuditChainAppender appender;
  private GuardDecisionStore store;
  private final ObjectMapper json = new ObjectMapper();
  private GuardDecisionService service;
  private ExecutionReceiptSigner signer;
  private final FactIssuerStore issuerStore = mock(FactIssuerStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    evaluationService = mock(PolicyEvaluationService.class);
    repository = mock(PolicySourceRepository.class);
    appender = mock(AuditChainAppender.class);
    store = mock(GuardDecisionStore.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    signer = new ExecutionReceiptSigner(ReceiptSigningKeys.parse(KEYS, "k1", clock), Duration.ofMinutes(5), clock);
    service = serviceWith(new ExecutionReceiptIssuer(signer, clock));
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
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID)).thenReturn(RuleMetadata.NONE);
  }

  private GuardDecisionService serviceWith(ExecutionReceiptIssuer issuer) {
    return serviceWith(issuer, NOW);
  }

  /** {@code readClock}：重放 / 回读判定 factsStale 用的时刻（签发方表以 {@link #issuerStore} 代替）。 */
  private GuardDecisionService serviceWith(ExecutionReceiptIssuer issuer, Instant readClock) {
    return new GuardDecisionService(evaluationService, repository, appender, store, new GuardActionMapper(),
        new GuardReceiptBuilder(), json, issuer, new FactVerifier(issuerStore, Clock.fixed(NOW, ZoneOffset.UTC)),
        new InputSourceResolver(issuerStore, Clock.fixed(readClock, ZoneOffset.UTC)), meters, Duration.ofHours(24),
        Supplier::get);
  }

  /** 与决策同一次调用写入的执行收据行（null 表示未签发）。 */
  private GuardReceiptEntity savedReceipt() {
    ArgumentCaptor<GuardReceiptEntity> captor = ArgumentCaptor.forClass(GuardReceiptEntity.class);
    verify(store).saveDecision(any(), any(), captor.capture());
    return captor.getValue();
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
    when(evaluationService.evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any()))
        .thenReturn(Uni.createFrom().item(result));
  }

  private GuardDecisionEntity savedDecision() {
    ArgumentCaptor<GuardDecisionEntity> captor = ArgumentCaptor.forClass(GuardDecisionEntity.class);
    verify(store).saveDecision(captor.capture(), any(), any());
    return captor.getValue();
  }

  /** 与决策同一次调用写入的待审批行（null 表示未创建）。 */
  private GuardApprovalEntity savedApproval() {
    ArgumentCaptor<GuardApprovalEntity> captor = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store).saveDecision(any(), captor.capture(), any());
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

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

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

  /** ADR 0040 §12 D1 (1)：直接 ALLOW 签发执行收据，收据行与决策行同一次保存，响应与 response_json 携带同一份。 */
  @Test
  void allowIssuesExecutionReceiptSavedWithDecision() throws Exception {
    verdict("ALLOW", null, null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    ExecutionReceipt receipt = r.executionReceipt();
    assertThat(receipt).isNotNull();
    assertThat(receipt.kid()).isEqualTo("k1");
    assertThat(receipt.payload()).isEqualTo(new ExecutionReceipt.Payload(ACTION_HASH, r.decisionId(),
        "2026-11-08T01:05:00.987Z", "2026-11-08T01:00:00.987Z", "k1", "ALLOW", 1730890123456L, TENANT, 1));
    assertThat(signer.verify(receipt)).isTrue();
    GuardDecisionEntity e = savedDecision();
    assertThat(e.actionHash).isEqualTo(ACTION_HASH);
    assertThat(json.readValue(e.responseJson, GuardDecisionResponse.class).executionReceipt()).isEqualTo(receipt);
    GuardReceiptEntity row = savedReceipt();
    assertThat(row.decisionId.toString()).isEqualTo(r.decisionId());
    assertThat(row.tenantId).isEqualTo(TENANT);
    assertThat(row.actionHash).isEqualTo(ACTION_HASH);
    assertThat(row.kid).isEqualTo("k1");
    assertThat(row.signature).isEqualTo(receipt.signature());
    assertThat(row.issuedAt).isEqualTo(Instant.parse("2026-11-08T01:00:00.987Z"));
    assertThat(row.expiresAt).isEqualTo(Instant.parse("2026-11-08T01:05:00.987Z"));
    assertThat(row.createdAt).isEqualTo(NOW);
    assertThat(row.consumedAt).isNull();
  }

  @Test
  void denyCarriesActionHashButNoReceipt() {
    verdict("DENY", null, "no");
    GuardDecisionResponse deny = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);
    assertThat(deny.executionReceipt()).isNull();
    assertThat(savedDecision().actionHash).isEqualTo(ACTION_HASH);
    assertThat(savedReceipt()).isNull();
  }

  @Test
  void pendingCarriesActionHashButNoReceipt() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    GuardDecisionResponse pending = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);
    assertThat(pending.executionReceipt()).isNull();
    assertThat(savedDecision().actionHash).isEqualTo(ACTION_HASH);
    assertThat(savedReceipt()).isNull();
  }

  /** 签发失败按 fail-closed：入链与落库的都是 DENY receipt_unavailable，没有收据行。 */
  @Test
  void signingFailureDeniesReceiptUnavailable() {
    verdict("ALLOW", null, null);
    ExecutionReceiptIssuer broken = mock(ExecutionReceiptIssuer.class);
    when(broken.issueNow(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenThrow(new ExecutionReceiptUnavailableException("down", new IllegalStateException()));
    service = serviceWith(broken);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("receipt_unavailable");
    assertThat(r.executionReceipt()).isNull();
    assertThat(appendedEvent().metadata()).containsEntry("outcome", "DENY").containsEntry("reasonCode", "receipt_unavailable");
    assertThat(savedReceipt()).isNull();
    assertThat(savedDecision().outcome).isEqualTo("DENY");
  }

  /** 链收据写入失败时已签好的执行收据作废：DENY 响应不带收据，也不落收据行。 */
  @Test
  void chainReceiptFailureDropsSignedReceipt() {
    verdict("ALLOW", null, null);
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", new RuntimeException()));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.reasonCode()).isEqualTo("receipt_unavailable");
    assertThat(r.executionReceipt()).isNull();
    assertThat(savedReceipt()).isNull();
  }

  /** 旧 response_json（引入执行收据之前写入，无该字段）照常反序列化，执行收据为 null。 */
  @Test
  void legacyResponseJsonWithoutReceiptStillDeserializes() throws Exception {
    String legacy = "{\"controls\":[\"GDPR:ART17\"],\"decisionId\":\"7c1e0000-0000-4000-8000-000000000001\","
        + "\"effectiveOutcome\":\"ALLOW\",\"outcome\":\"ALLOW\",\"policyVersion\":{\"id\":1,\"sourceHash\":\"s\","
        + "\"version\":2},\"receipt\":{\"auditId\":42,\"hash\":\"h\",\"prevHash\":\"p\"},\"ruleId\":\"R\"}";
    UUID id = UUID.fromString("7c1e0000-0000-4000-8000-000000000001");
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.responseJson = legacy;
    when(store.findDecision(TENANT, id)).thenReturn(Optional.of(e));

    GuardDecisionResponse r = service.get(TENANT, id).orElseThrow();

    assertThat(r.executionReceipt()).isNull();
    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(json.writeValueAsString(r)).isEqualTo(legacy);
  }

  @Test
  void 传入规则的参数是不可变Map() {
    verdict("ALLOW", null, null);
    service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(evaluationService).evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), args.capture());
    assertThat(args.getValue()).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> flat = (Map<String, Object>) args.getValue()[0];
    assertThat(flat).containsEntry("principal_id", "agent-42").containsEntry("action", "delete_customer_record");
    assertThatThrownBy(() -> flat.put("action", "x")).isInstanceOf(UnsupportedOperationException.class);
  }

  /** M6：context 的嵌套 null 叶子经评估原样到达规则（不抛 NPE、键仍存在），规则可据此返回 Verdict。 */
  @Test
  void nestedNullReachesRuleThroughEvaluation() {
    verdict("ALLOW", null, null);
    java.util.HashMap<String, Object> inner = new java.util.HashMap<>();
    inner.put("b", null);
    Action a = new Action(new Principal("agent-42", null, null), new ActionName("send_email"), null, Map.of("a", inner), null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", new GuardActionRequest(MODULE, FUNCTION, a, null), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(evaluationService).evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), args.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> flat = (Map<String, Object>) args.getValue()[0];
    @SuppressWarnings("unchecked")
    Map<String, Object> ctxA = (Map<String, Object>) ((Map<String, Object>) flat.get("context")).get("a");
    assertThat(ctxA).containsKey("b");
    assertThat(ctxA.get("b")).isNull();
    assertThat(new java.util.ArrayList<>(flat.keySet())).containsExactly(
        "principal_id", "principal_type", "principal_roles", "action",
        "resource_type", "resource_id", "context", "agent_model");
    assertThatThrownBy(() -> flat.put("action", "x")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctxA.put("b", "x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void Verdict_require_approval_PENDING且创建待审批() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    Instant before = Instant.now();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

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
    order.verify(store).saveDecision(any(), any(), any());
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

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(r.approval().requiredRole()).isNull();
    assertThat(savedApproval().outcome).isEqualTo("ESCALATE");
    assertThat(savedApproval().requiredRole).isNull();
  }

  @Test
  void Verdict_deny_DENY无reasonCode() {
    verdict("DENY", null, "blocked");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

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
    when(evaluationService.evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any()))
        .thenReturn(Uni.createFrom().item(result));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

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
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
  }

  @Test
  void 评估抛异常_DENY_evaluation_failed() {
    when(evaluationService.evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any()))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("策略执行失败: boom")));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("evaluation_failed");
    assertThat(r.reason()).isNull();
    assertThat(savedDecision().reasonCode).isEqualTo("evaluation_failed");
    assertThat(savedApproval()).isNull();
  }

  @Test
  void 评估同步抛异常同样fail_closed() {
    when(evaluationService.evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any()))
        .thenThrow(new IllegalStateException("boom"));
    assertThat(service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null).reasonCode()).isEqualTo("evaluation_failed");
  }

  /**
   * 调用方可随意触发 evaluation_failed（如传入规则不接受的 context 类型），WARN 只记一行异常类与消息，
   * 堆栈留给 DEBUG。挂一个内存 Handler 到该类的 JUL logger（JBoss LogManager 底层走 JUL）观察。
   */
  @Test
  void 评估失败WARN不带堆栈_堆栈只在DEBUG() {
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    Handler probe = new Handler() {
      @Override public void publish(LogRecord r) { records.add(r); }
      @Override public void flush() { }
      @Override public void close() { }
    };
    Logger logger = Logger.getLogger(GuardDecisionService.class.getName());
    Level original = logger.getLevel();
    logger.addHandler(probe);
    logger.setLevel(Level.ALL);
    try {
      when(evaluationService.evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any()))
          .thenThrow(new IllegalStateException("boom-sentinel"));
      assertThat(service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null).reasonCode()).isEqualTo("evaluation_failed");
    } finally {
      logger.removeHandler(probe);
      logger.setLevel(original);
    }

    LogRecord warn = records.stream().filter(r -> r.getLevel().intValue() == Level.WARNING.intValue()).findFirst()
        .orElseThrow();
    assertThat(warn.getThrown()).isNull();
    assertThat(textOf(warn)).contains("IllegalStateException").contains("boom-sentinel");
    LogRecord debug = records.stream().filter(r -> r.getLevel().intValue() < Level.INFO.intValue()).findFirst()
        .orElseThrow();
    assertThat(debug.getThrown()).isInstanceOf(IllegalStateException.class);
  }

  private static String textOf(LogRecord r) {
    StringBuilder text = new StringBuilder(String.valueOf(r.getMessage()));
    if (r.getParameters() != null) {
      for (Object p : r.getParameters()) {
        text.append(' ').append(p);
      }
    }
    return text.toString();
  }

  @Test
  void 收据写入失败_DENY_receipt_unavailable且不创建待审批() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", new RuntimeException()));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

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
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", null, "ALLOW",
        null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV), null, "R");
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(storedFor(request(), stored)));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

    assertThat(r).isEqualTo(stored);
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  @Test
  void 同一幂等键用于不同动作报idempotency_mismatch且不评估() throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", null, "ALLOW",
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
      assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", other, ACTION_HASH, null, "idem-1"))
          .isInstanceOf(GuardIdempotencyMismatchException.class);
    }
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  @Test
  void 指纹不含principal_type_roles与agent_model() throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW", null, "ALLOW",
        null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV), null, "R");
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(storedFor(request(), stored)));
    Action a = request().action();
    GuardActionRequest sameAction = new GuardActionRequest(MODULE, FUNCTION, new Action(
        new Principal("agent-42", "service", List.of("other-role")), a.action(), a.resource(), a.context(),
        new AgentIdentity("unknown", "other-model", null, "s-2")), null);

    assertThat(service.decide(TENANT, "user-1", "ak-1", sameAction, ACTION_HASH, null, "idem-1")).isEqualTo(stored);
  }

  @Test
  void 决策行记录请求方用户与API_key() {
    verdict("ALLOW", null, null);

    service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    GuardDecisionEntity e = savedDecision();
    assertThat(e.requestedBy).isEqualTo("user-1");
    assertThat(e.requestedApiKeyId).isEqualTo("ak-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void 并发同键竞争失败时返回先到者() throws Exception {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    GuardDecisionResponse winner = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "DENY", null, "DENY",
        null, null, null, null, null, null);
    GuardDecisionEntity e = storedFor(request(), winner);
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.empty(), Optional.of(e));
    doThrow(new GuardIdempotencyRaceException(new RuntimeException("23505"))).when(store).saveDecision(any(), any(), any());

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

    assertThat(r).isEqualTo(winner);
    // 本请求的待审批行与决策行在同一事务里，冲突时一起回滚，不会留下孤儿审批。
    assertThat(savedApproval()).isNotNull();
  }

  @Test
  void 合并写入失败时异常外抛且审批id不泄露() {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    IllegalStateException down = new IllegalStateException("db down");
    doThrow(down).doNothing().when(store).saveDecision(any(), any(), any());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1")).isSameAs(down);
    GuardDecisionResponse retry = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

    ArgumentCaptor<GuardApprovalEntity> approvals = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store, org.mockito.Mockito.times(2)).saveDecision(any(), approvals.capture(), any());
    UUID failedApprovalId = approvals.getAllValues().get(0).id;
    assertThat(failedApprovalId).isNotNull();
    assertThat(retry.approval().id()).isNotEqualTo(failedApprovalId.toString());
    assertThat(retry.approval().id()).isEqualTo(approvals.getAllValues().get(1).id.toString());
  }

  @Test
  void Verdict的role超长按not_a_verdict拒绝() {
    verdict("REQUIRE_APPROVAL", "r".repeat(256), "customer record deletion");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.approval()).isNull();
    assertThat(r.role()).isNull();
    assertThat(savedApproval()).isNull();
  }

  @Test
  void Verdict的reason超长按not_a_verdict拒绝() {
    verdict("ALLOW", null, "x".repeat(256));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("not_a_verdict");
    assertThat(r.reason()).isNull();
    assertThat(savedDecision().reason).isNull();
  }

  @Test
  void Verdict的role与reason恰为255可接受() {
    verdict("REQUIRE_APPROVAL", "r".repeat(255), "x".repeat(255));
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);
    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(savedApproval().requiredRole).hasSize(255);
  }

  @Test
  void 空白幂等键视为未提供() {
    verdict("ALLOW", null, null);

    service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "   ");

    verify(store, never()).findByIdempotencyKey(anyString(), anyString());
    assertThat(savedDecision().idempotencyKey).isNull();
    assertThat(appendedEvent().metadata()).doesNotContainKey("idempotencyKey");
  }

  @Test
  void 幂等键超长或含控制字符报invalid_action且不评估() {
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "k".repeat(256)))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem\n1"))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem\u00071"))
        .isInstanceOf(GuardInvalidActionException.class);
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).findByIdempotencyKey(anyString(), anyString());
    verify(store, never()).saveDecision(any(), any(), any());
  }

  @Test
  void 幂等键恰为255可用() {
    verdict("ALLOW", null, null);
    service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "k".repeat(255));
    assertThat(savedDecision().idempotencyKey).hasSize(255);
  }

  @Test
  void 策略不存在_GuardPolicyNotFoundException() {
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null))
        .isInstanceOf(GuardPolicyNotFoundException.class)
        .extracting(ex -> ((GuardPolicyNotFoundException) ex).reasonCode()).isEqualTo(GuardReasonCode.POLICY_NOT_FOUND);
    verifyNoInteractions(evaluationService, appender);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  @Test
  void 无活跃版本_GuardPolicyNotFoundException() {
    PolicyCatalog catalog = new PolicyCatalog();
    catalog.id = UUID.randomUUID();
    when(repository.findActiveCatalog(TENANT, MODULE, FUNCTION)).thenReturn(Optional.of(catalog));
    when(repository.findActiveVersion(catalog.id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null)).isInstanceOf(GuardPolicyNotFoundException.class);
  }

  @Test
  void 请求缺策略地址或action报invalid_action() {
    GuardActionRequest noModule = new GuardActionRequest(" ", FUNCTION, request().action(), null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", noModule, ACTION_HASH, null, null)).isInstanceOf(GuardInvalidActionException.class);
    GuardActionRequest noAction = new GuardActionRequest(MODULE, FUNCTION, null, null);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", noAction, ACTION_HASH, null, null)).isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> service.decide(TENANT, "user-1", "ak-1", null, ACTION_HASH, null, null)).isInstanceOf(GuardInvalidActionException.class);
    verifyNoInteractions(evaluationService, appender);
  }

  @Test
  void get返回存储响应且跨租户为空() throws Exception {
    UUID id = UUID.randomUUID();
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), id.toString(), "ALLOW", null, "ALLOW",
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
        List.of(), decisionId.toString(), "PENDING", null, "REQUIRE_APPROVAL", null, null, null, null, "DPO", null);
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
    }).when(store).saveDecision(any(), any(), any());
    when(store.findDecision(eq(TENANT), any())).thenAnswer(inv -> Optional.ofNullable(decision.get()));
    when(store.findApprovalByDecision(any())).thenAnswer(inv -> Optional.ofNullable(approval.get()));
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(5), ACTION_HASH, null, null);

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
    }).when(store).saveDecision(any(), any(), any());
    when(store.findDecision(eq(TENANT), any())).thenAnswer(inv -> Optional.ofNullable(decision.get()));
    when(store.findApprovalByDecision(any())).thenAnswer(inv -> Optional.ofNullable(approval.get()));
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(1), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
  }

  @Test
  void waitSeconds期间被中断则保留中断标志并以当前PENDING结束且不再评估() throws Exception {
    verdict("REQUIRE_APPROVAL", "DPO", "needs review");
    // 决策落库时线程被中断：随后的等待立即感知，请求就此结束
    doAnswer(inv -> {
      Thread.currentThread().interrupt();
      return null;
    }).when(store).saveDecision(any(), any(), any());

    try {
      GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(30), ACTION_HASH, null, null);

      assertThat(r.effectiveOutcome()).isEqualTo("PENDING");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(store, never()).findDecision(any(), any());
      verify(evaluationService, times(1)).evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void waitSeconds对非PENDING结果不等待() {
    verdict("ALLOW", null, null);
    long start = System.nanoTime();

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", requestWaiting(30), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    verify(store, never()).findDecision(any(), any());
  }

  @Test
  void 响应序列化字母序且省略空字段() throws Exception {
    verdict("ALLOW", null, null);
    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    String body = json.writeValueAsString(r);

    assertThat(body).startsWith("{\"controls\":").doesNotContain("approval").doesNotContain("reasonCode")
        .doesNotContain("null");
    assertThat(body.indexOf("\"decisionId\"")).isLessThan(body.indexOf("\"effectiveOutcome\""));
    assertThat(body.indexOf("\"policyVersion\"")).isLessThan(body.indexOf("\"receipt\""));
    assertThat(body).contains("\"policyVersion\":{\"id\":1730890123456,\"sourceHash\":\"" + "d".repeat(64) + "\",\"version\":3}");
  }

  // ---- ADR 0040 §11 D4 / D6：输入来源进响应、决策行与收据 ----

  /**
   * 不带事实、主体非 user 的现有调用方：响应与引入来源标记之前逐字节相同（字面量录自改动前的序列化），
   * 决策行三列为 null，收据不含任何来源键。
   */
  @Test
  void noFactsResponseByteIdenticalToToday() throws Exception {
    verdict("DENY", null, "amount too large");

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    String expected = "{\"controls\":[\"GDPR:ART17\"],\"decisionId\":\"" + r.decisionId() + "\","
        + "\"effectiveOutcome\":\"DENY\",\"outcome\":\"DENY\","
        + "\"policyVersion\":{\"id\":1730890123456,\"sourceHash\":\"" + "d".repeat(64) + "\",\"version\":3},"
        + "\"reason\":\"amount too large\",\"receipt\":{\"auditId\":88213,\"hash\":\"" + HASH + "\",\"prevHash\":\""
        + PREV + "\"},\"ruleId\":\"CUST-DEL-001\"}";
    assertThat(json.writeValueAsString(r)).isEqualTo(expected);
    GuardDecisionEntity e = savedDecision();
    assertThat(e.responseJson).isEqualTo(expected);
    assertThat(e.verifiedFields).isNull();
    assertThat(e.factIssuers).isNull();
    assertThat(e.factExpiresAt).isNull();
    assertThat(appendedEvent().metadata()).doesNotContainKeys("verifiedFields", "factIssuers", "factSources",
        "principalIdentityMatched", "factExpiresAt", "inputSourcesTruncated");
  }

  /** principal.type=user 且 id 等于提交者：principal.id / roles verified，进响应、决策行与收据（无事实）。 */
  @Test
  void userPrincipalVerifiedIntoResponseRowAndReceipt() throws Exception {
    verdict("DENY", null, "no");
    Action a = new Action(new Principal("user-1", "user", List.of("ops")), new ActionName("delete_customer_record"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000), null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", Set.of("ops", "finance"),
        new GuardActionRequest(MODULE, FUNCTION, a, null), ACTION_HASH, null, null);

    assertThat(r.inputSources()).isEqualTo(new GuardDecisionResponse.InputSourcesView(List.of(), null,
        List.of("principal.id", "principal.roles")));
    assertThat(json.writeValueAsString(r)).contains("\"inputSources\":{\"verified\":[\"principal.id\",\"principal.roles\"]}");
    GuardDecisionEntity e = savedDecision();
    assertThat(e.verifiedFields).isEqualTo("[\"principal.id\",\"principal.roles\"]");
    assertThat(e.factIssuers).isNull();
    assertThat(e.factExpiresAt).isNull();
    assertThat(appendedEvent().metadata()).containsEntry("verifiedFields", List.of("principal.id", "principal.roles"))
        .containsEntry("principalIdentityMatched", true)
        .doesNotContainKeys("factIssuers", "factSources", "factExpiresAt", "inputSourcesTruncated");
  }

  /** 7 参 decide 不带已验证业务角色：roles 不能被证实，principal.id 仍按提交者判定。 */
  @Test
  void decideWithoutBusinessRolesLeavesRolesDeclared() {
    verdict("DENY", null, "no");
    Action a = new Action(new Principal("user-1", "user", List.of("ops")), new ActionName("delete_customer_record"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000), null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", new GuardActionRequest(MODULE, FUNCTION, a, null),
        ACTION_HASH, null, null);

    assertThat(r.inputSources().verified()).containsExactly("principal.id");
  }

  private GuardDecisionEntity storedWithFacts(Instant factExpiresAt) throws Exception {
    GuardDecisionResponse stored = new GuardDecisionResponse(null, List.of(), UUID.randomUUID().toString(), "ALLOW",
        null, new GuardDecisionResponse.InputSourcesView(List.of("idp.example.com#2026-11"), null,
        List.of("context.amount")), "ALLOW", null, null, null, new GuardDecisionResponse.ReceiptView(1L, HASH, PREV),
        null, "R");
    GuardDecisionEntity e = storedFor(request(), stored);
    e.id = UUID.fromString(stored.decisionId());
    e.tenantId = TENANT;
    e.verifiedFields = "[\"context.amount\"]";
    e.factIssuers = "[\"idp.example.com#2026-11\"]";
    e.factExpiresAt = factExpiresAt;
    return e;
  }

  private void issuerEnabled() {
    GuardFactIssuerEntity row = new GuardFactIssuerEntity();
    row.enabled = true;
    when(issuerStore.find(TENANT, "idp.example.com", "2026-11")).thenReturn(Optional.of(row));
  }

  /**
   * 重放不重验首次的事实（ADR 0040 §11 D4）：now >= fact_expires_at 时只追加 inputSources.factsStale，
   * 其余与存储响应相同；不评估、不写事件、不改写决策行。过期前重放与存储响应完全相同。
   */
  @Test
  void replayAfterFactExpiryMarksStaleWithoutNewEvent() throws Exception {
    Instant expires = NOW.plusSeconds(60);
    GuardDecisionEntity row = storedWithFacts(expires);
    GuardDecisionResponse stored = json.readValue(row.responseJson, GuardDecisionResponse.class);
    when(store.findByIdempotencyKey(TENANT, "idem-1")).thenReturn(Optional.of(row));
    issuerEnabled();
    ExecutionReceiptIssuer issuer = new ExecutionReceiptIssuer(signer, Clock.fixed(NOW, ZoneOffset.UTC));

    GuardDecisionResponse fresh = serviceWith(issuer, expires.minusMillis(1))
        .decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");
    GuardDecisionResponse stale = serviceWith(issuer, expires)
        .decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

    assertThat(fresh).isEqualTo(stored);
    assertThat(stale.inputSources()).isEqualTo(new GuardDecisionResponse.InputSourcesView(
        List.of("idp.example.com#2026-11"), true, List.of("context.amount")));
    assertThat(json.writeValueAsString(stale)).isEqualTo(json.writeValueAsString(stored)
        .replace("\"inputSources\":{\"factIssuers\":[\"idp.example.com#2026-11\"],",
            "\"inputSources\":{\"factIssuers\":[\"idp.example.com#2026-11\"],\"factsStale\":true,"));
    verifyNoInteractions(evaluationService, appender, repository);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  /** 首次记录的签发方现已禁用：GET 回读同样标 factsStale。 */
  @Test
  void getAfterIssuerDisabledMarksStale() throws Exception {
    GuardDecisionEntity row = storedWithFacts(NOW.plusSeconds(600));
    when(store.findDecision(TENANT, row.id)).thenReturn(Optional.of(row));
    GuardFactIssuerEntity disabled = new GuardFactIssuerEntity();
    disabled.enabled = false;
    when(issuerStore.find(TENANT, "idp.example.com", "2026-11")).thenReturn(Optional.of(disabled));

    GuardDecisionResponse r = service.get(TENANT, row.id).orElseThrow();

    assertThat(r.inputSources().factsStale()).isTrue();
  }
  /** 档案要求 verifiedInputs 的规则元数据（ADR 0046 §11 D1）：inputRefs 为 null 表示 IR 未提供。 */
  private void requiresVerifiedInputs(List<String> inputRefs) {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID)).thenReturn(
        new RuleMetadata("CUST-DEL-001", List.of("GDPR:ART17"), "eu-ai-act-high-risk", inputRefs, true));
  }

  /**
   * ADR 0040 §11 D5：被引用的字段为 declared → 不评估、DENY unverified_input（reason 为 null），
   * 写链收据（unverifiedFields）与决策行，但不建待审批、不签执行收据——即便规则本会 ALLOW / REQUIRE_APPROVAL。
   */
  @Test
  void gateDenyCreatesNoApprovalAndNoExecutionReceipt() throws Exception {
    verdict("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion");
    requiresVerifiedInputs(List.of("principal_id", "context.amount"));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, "idem-1");

    assertThat(r.outcome()).isEqualTo("DENY");
    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("unverified_input");
    assertThat(r.reason()).isNull();
    assertThat(r.role()).isNull();
    assertThat(r.approval()).isNull();
    assertThat(r.executionReceipt()).isNull();
    assertThat(r.ruleId()).isEqualTo("CUST-DEL-001");
    assertThat(r.receipt()).isEqualTo(new GuardDecisionResponse.ReceiptView(88213L, HASH, PREV));
    AuditEvent event = appendedEvent();
    assertThat(event.success()).isFalse();
    assertThat(event.metadata()).containsEntry("outcome", "DENY").containsEntry("reasonCode", "unverified_input")
        .containsEntry("unverifiedFields", List.of("context.amount", "principal.id"))
        .doesNotContainKey("reason").doesNotContainKey("approvalId");
    GuardDecisionEntity e = savedDecision();
    assertThat(e.outcome).isEqualTo("DENY");
    assertThat(e.reasonCode).isEqualTo("unverified_input");
    assertThat(json.readValue(e.responseJson, GuardDecisionResponse.class)).isEqualTo(r);
    assertThat(savedApproval()).isNull();
    assertThat(savedReceipt()).isNull();
  }

  /** 门在版本解析之后、评估之前：被拒时规则根本不执行；IR 无 inputRefs → input_refs_unavailable，收据无 unverifiedFields。 */
  @Test
  void gateRunsBeforeEvaluation() {
    verdict("ALLOW", null, null);
    requiresVerifiedInputs(null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("input_refs_unavailable");
    assertThat(r.executionReceipt()).isNull();
    assertThat(appendedEvent().metadata()).containsEntry("reasonCode", "input_refs_unavailable")
        .doesNotContainKey("unverifiedFields");
    verify(evaluationService, never()).evaluatePolicyAtVersion(any(), any(), any(), any(), any());
    InOrder order = inOrder(repository, evaluationService);
    order.verify(repository).findActiveCatalog(TENANT, MODULE, FUNCTION);
    order.verify(evaluationService).ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID);
    assertThat(savedReceipt()).isNull();
  }

  /** 被引用的字段全部 verified（principal.id 由服务端证实为提交者）→ 照常评估，结论与收据与无档案时同形。 */
  @Test
  void verifiedReferencedInputsProceedToEvaluation() {
    verdict("ALLOW", null, null);
    requiresVerifiedInputs(List.of("principal_id"));
    Action a = new Action(new Principal("user-1", "user", null), new ActionName("delete_customer_record"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000), null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", new GuardActionRequest(MODULE, FUNCTION, a, null),
        ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(r.reasonCode()).isNull();
    assertThat(r.executionReceipt()).isNotNull();
    assertThat(appendedEvent().metadata()).doesNotContainKey("unverifiedFields");
    verify(evaluationService).evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any());
  }

  /** 读不到规则元数据（编译失败等）与评估失败同一结论：DENY evaluation_failed，不执行规则。 */
  @Test
  void ruleMetadataFailureDeniesEvaluationFailed() {
    verdict("ALLOW", null, null);
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID)).thenThrow(new RuntimeException("compile failed"));

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.reasonCode()).isEqualTo("evaluation_failed");
    assertThat(r.ruleId()).isNull();
    verify(evaluationService, never()).evaluatePolicyAtVersion(any(), any(), any(), any(), any());
    assertThat(savedApproval()).isNull();
  }

  /**
   * 版本固定（Task 16 carry-over 2）：元数据与评估都使用决策解析出的同一 versionId，二者之间激活版本移动也不漂移；
   * 不再走按活跃版本解析的旧入口。
   */
  @Test
  void gateAndEvaluationUseSameVersion() {
    verdict("ALLOW", null, null);

    GuardDecisionResponse r = service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(r.policyVersion().id()).isEqualTo(Long.parseLong(VERSION_ID));
    InOrder order = inOrder(repository, evaluationService);
    order.verify(repository).findActiveCatalog(TENANT, MODULE, FUNCTION);
    order.verify(evaluationService).ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID);
    order.verify(evaluationService).evaluatePolicyAtVersion(eq(TENANT), eq(MODULE), eq(FUNCTION), eq(VERSION_ID), any());
    verify(evaluationService, never()).ruleMetadata(any(), any(), any());
    verify(evaluationService, never()).evaluatePolicy(any(), any(), any(), any());
  }

  /** 读不到规则元数据时计 counter（path=decisions），结论仍为 evaluation_failed。 */
  @Test
  void ruleMetadataFailureCountsDecisionsPath() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, VERSION_ID)).thenThrow(new RuntimeException("x"));

    service.decide(TENANT, "user-1", "ak-1", request(), ACTION_HASH, null, null);

    assertThat(meters.get("aster.guard.rule.metadata.unavailable").tag("path", "decisions").counter().count())
        .isEqualTo(1.0);
  }
}
