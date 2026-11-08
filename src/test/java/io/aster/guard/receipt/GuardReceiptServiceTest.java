package io.aster.guard.receipt;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.GuardDecisionService;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.GuardDecisionReadResponse;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.receipt.GuardReceiptStore.ConsumeVerdict;
import io.aster.guard.receipt.GuardReceiptStore.ResultVerdict;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR 0040 §12 D5：consume 编排（入参校验 → 尝试事件 → 加锁判定 → 结果事件），纯 JUnit + Mockito。 */
class GuardReceiptServiceTest {
  private static final String TENANT = "tenant-a";
  private static final UUID DECISION = UUID.fromString("3f1c2a4e-0000-4000-8000-000000000001");
  private static final String HASH = "a".repeat(64);
  private static final String ATTEMPT = "6d0e2b1a-1111-4000-8000-000000000002";
  private static final String DECISION_RECEIPT_HASH = "d".repeat(64);
  private static final String ATTEMPT_HASH = "e".repeat(64);
  private static final String KEYS = "{\"revision\":7,\"keys\":["
      + "{\"kid\":\"k1\",\"privateKey\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE\"},"
      + "{\"kid\":\"k0\",\"privateKey\":\"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\",\"revoked\":true}]}";

  private GuardReceiptStore store;
  private AuditChainAppender appender;
  private GuardDecisionService decisions;
  private GuardReceiptService service;

  @BeforeEach
  void setUp() {
    store = mock(GuardReceiptStore.class);
    appender = mock(AuditChainAppender.class);
    decisions = mock(GuardDecisionService.class);
    ReceiptSigningKeys keys = ReceiptSigningKeys.parse(KEYS, "k1",
        Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
    service = new GuardReceiptService(store, appender, new GuardReceiptBuilder(), decisions, keys);
    when(appender.appendSync(any())).thenReturn(new AppendedRecord(501L, ATTEMPT_HASH, DECISION_RECEIPT_HASH));
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(row()));
    when(decisions.get(TENANT, DECISION)).thenReturn(Optional.of(response("ALLOW")));
  }

  private static GuardReceiptEntity row() {
    GuardReceiptEntity r = new GuardReceiptEntity();
    r.decisionId = DECISION;
    r.tenantId = TENANT;
    r.actionHash = HASH;
    r.kid = "k1";
    r.issuedAt = Instant.parse("2026-11-08T10:00:00Z");
    r.expiresAt = Instant.parse("2026-11-08T10:05:00Z");
    r.signature = "sig";
    return r;
  }

  private static GuardDecisionResponse response(String effectiveOutcome) {
    return new GuardDecisionResponse(null, List.of(), DECISION.toString(), effectiveOutcome, null, effectiveOutcome,
        null, null, null, new GuardDecisionResponse.ReceiptView(400L, DECISION_RECEIPT_HASH, null), null, null);
  }

  private static ConsumeRequest req() {
    return new ConsumeRequest(HASH, ATTEMPT, "exec-1");
  }

  private List<AuditEvent> appended(int count) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(appender, times(count)).appendSync(captor.capture());
    return captor.getAllValues();
  }

  @Test
  void consumedAppendsAttemptThenOutcomeOnTenantChain() {
    Instant at = Instant.parse("2026-11-08T10:01:00Z");
    when(store.consume(eq(TENANT), eq(DECISION), any(), eq(501L), any()))
        .thenReturn(new ConsumeVerdict.Consumed(at, row()));

    GuardReceiptService.Result result = service.consume(TENANT, "user-1", DECISION, req()).orElseThrow();

    assertThat(result.verdict()).isInstanceOf(ConsumeVerdict.Consumed.class);
    List<AuditEvent> events = appended(2);
    AuditEvent attempt = events.get(0);
    AuditEvent outcome = events.get(1);
    assertThat(attempt.eventType()).isEqualTo(EventType.ACTION_GUARD_CONSUME_ATTEMPT);
    assertThat(attempt.tenantId()).isEqualTo(TENANT);
    assertThat(attempt.performedBy()).isEqualTo("user-1");
    assertThat(attempt.metadata()).containsEntry("decisionReceiptHash", DECISION_RECEIPT_HASH)
        .containsEntry("actionHash", HASH).containsEntry("attemptId", ATTEMPT).containsEntry("kid", "k1");
    assertThat(outcome.eventType()).isEqualTo(EventType.ACTION_GUARD_CONSUME_OUTCOME);
    assertThat(outcome.tenantId()).isEqualTo(TENANT);
    assertThat(outcome.metadata()).containsEntry("outcome", "consumed").containsEntry("attemptReceiptHash", ATTEMPT_HASH);
  }

  @Test
  void attemptAppendFailureIs503AndRowUntouched() {
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", null));

    assertThatThrownBy(() -> service.consume(TENANT, "user-1", DECISION, req()))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
    verify(store, never()).consume(any(), any(), any(), anyLong(), any());
  }

  /** 行已提交后结果事件追加失败：只记日志，判定照常返回（链上只剩尝试事件，属预期的崩溃窗口）。 */
  @Test
  void outcomeAppendFailureStillReturnsVerdict() {
    when(appender.appendSync(any()))
        .thenReturn(new AppendedRecord(501L, ATTEMPT_HASH, DECISION_RECEIPT_HASH))
        .thenThrow(new AuditChainAppender.AuditAppendException("down", null));
    when(store.consume(any(), any(), any(), anyLong(), any()))
        .thenReturn(new ConsumeVerdict.Consumed(Instant.parse("2026-11-08T10:01:00Z"), row()));

    assertThat(service.consume(TENANT, "user-1", DECISION, req()).orElseThrow().verdict())
        .isInstanceOf(ConsumeVerdict.Consumed.class);
  }

  @Test
  void unknownDecisionIsEmptyWithoutEvents() {
    when(decisions.get(TENANT, DECISION)).thenReturn(Optional.empty());

    assertThat(service.consume(TENANT, "user-1", DECISION, req())).isEmpty();
    verifyNoInteractions(appender);
    verify(store, never()).consume(any(), any(), any(), anyLong(), any());
  }

  @Test
  void invalidFieldsRejectedBeforeAnyEvent() {
    Map<ConsumeRequest, String> cases = Map.of(
        new ConsumeRequest(null, ATTEMPT, "e"), "actionHash",
        new ConsumeRequest("A".repeat(64), ATTEMPT, "e"), "actionHash",
        new ConsumeRequest(HASH, "6d0e2b1a1111400080000000000000002", "e"), "attemptId",
        new ConsumeRequest(HASH, ATTEMPT, "tab\there"), "executorId",
        new ConsumeRequest(HASH, ATTEMPT, " "), "executorId",
        new ConsumeRequest(HASH, ATTEMPT, "x".repeat(256)), "executorId");
    cases.forEach((req, field) -> assertThatThrownBy(() -> service.consume(TENANT, "u", DECISION, req))
        .isInstanceOfSatisfying(InvalidConsumeException.class, e -> assertThat(e.field()).isEqualTo(field)));
    assertThatThrownBy(() -> service.consume(TENANT, "u", DECISION, null))
        .isInstanceOfSatisfying(InvalidConsumeException.class, e -> assertThat(e.field()).isEqualTo("actionHash"));
    verifyNoInteractions(appender);
  }

  @Test
  void notAllowedCarriesEffectiveOutcomeAndLegacyOnlyForAllow() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.empty());
    when(store.consume(any(), any(), any(), anyLong(), any()))
        .thenReturn(new ConsumeVerdict.Rejected("not_allowed", Map.of()));

    ConsumeVerdict legacy = service.consume(TENANT, "u", DECISION, req()).orElseThrow().verdict();
    when(decisions.get(TENANT, DECISION)).thenReturn(Optional.of(response("PENDING")));
    ConsumeVerdict pending = service.consume(TENANT, "u", DECISION, req()).orElseThrow().verdict();

    assertThat(((ConsumeVerdict.Rejected) legacy).extra())
        .containsExactly(Map.entry("effectiveOutcome", "ALLOW"), Map.entry("legacy", true));
    assertThat(((ConsumeVerdict.Rejected) pending).extra()).containsExactly(Map.entry("effectiveOutcome", "PENDING"));
    // 无收据行时尝试事件仍入链（只有决策与租户），结果事件为 rejected:not_allowed
    List<AuditEvent> events = appended(4);
    assertThat(events.get(0).metadata()).containsEntry("decisionId", DECISION.toString()).doesNotContainKey("kid");
    assertThat(events.get(1).metadata()).containsEntry("outcome", "rejected:not_allowed");
  }

  @Test
  void kidRevokedCarriesCurrentRevisionAndPredicateReadsConfig() {
    when(store.consume(any(), any(), any(), anyLong(), any())).thenAnswer(inv -> {
      java.util.function.Predicate<String> revoked = inv.getArgument(4);
      assertThat(revoked.test("k0")).isTrue();
      assertThat(revoked.test("k1")).isFalse();
      assertThat(revoked.test("retired-and-removed")).isFalse();
      return new ConsumeVerdict.Rejected("kid_revoked", Map.of("kid", "k0"));
    });

    ConsumeVerdict v = service.consume(TENANT, "u", DECISION, req()).orElseThrow().verdict();

    assertThat(((ConsumeVerdict.Rejected) v).extra())
        .containsExactly(Map.entry("kid", "k0"), Map.entry("revision", 7L));
  }

  // ---- 结果回写（ADR 0040 §12 D7）----

  private static final String CONSUME_ATTEMPT_HASH = "c".repeat(64);
  private static final String RESULT_HASH = "f".repeat(64);

  private static GuardReceiptEntity consumedRow() {
    GuardReceiptEntity r = row();
    r.consumedAt = Instant.parse("2026-11-08T10:01:00Z");
    r.attemptId = ATTEMPT;
    r.executorId = "exec-1";
    r.consumeAuditId = 501L;
    return r;
  }

  private static ResultRequest resultReq(String status) {
    return new ResultRequest(ATTEMPT, "exec-1", status, "2026-11-08T10:02:00Z", RESULT_HASH, null);
  }

  @Test
  void resultAppendsEventLinkedToAuthoritativeAttemptThenRecords() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(consumedRow()));
    when(store.auditCurrentHash(TENANT, 501L)).thenReturn(Optional.of(CONSUME_ATTEMPT_HASH));
    when(appender.appendSync(any())).thenReturn(new AppendedRecord(900L, "9".repeat(64), CONSUME_ATTEMPT_HASH));
    Instant at = Instant.parse("2026-11-08T10:02:01Z");
    when(store.recordResult(eq(TENANT), eq(DECISION), any(), eq(900L)))
        .thenReturn(new ResultVerdict.Recorded(at, "executed"));

    ResultVerdict v = service.recordResult(TENANT, "user-1", DECISION, resultReq("executed")).orElseThrow();

    assertThat(v).isEqualTo(new ResultVerdict.Recorded(at, "executed"));
    AuditEvent event = appended(1).get(0);
    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_RESULT);
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.performedBy()).isEqualTo("user-1");
    assertThat(event.success()).isTrue();
    assertThat(event.metadata()).containsEntry("consumeReceiptHash", CONSUME_ATTEMPT_HASH)
        .containsEntry("resultHash", RESULT_HASH).containsEntry("attemptId", ATTEMPT)
        .containsEntry("status", "executed");
  }

  /** 预判失败的三种情况都不入链、不进入加锁写。 */
  @Test
  void resultPrecheckRejectionsAppendNothing() {
    GuardReceiptEntity unconsumed = row();
    GuardReceiptEntity recorded = consumedRow();
    recorded.resultStatus = "executed";
    Map<Optional<GuardReceiptEntity>, String> cases = new java.util.LinkedHashMap<>();
    cases.put(Optional.empty(), "not_consumed");
    cases.put(Optional.of(unconsumed), "not_consumed");
    cases.put(Optional.of(recorded), "result_recorded");
    cases.forEach((snapshot, code) -> {
      when(store.findReceipt(TENANT, DECISION)).thenReturn(snapshot);
      assertThat(service.recordResult(TENANT, "u", DECISION, resultReq("executed")).orElseThrow())
          .isEqualTo(new ResultVerdict.Rejected(code));
    });
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(consumedRow()));
    ResultRequest otherExecutor = new ResultRequest(ATTEMPT, "exec-2", "executed", null, null, null);
    assertThat(service.recordResult(TENANT, "u", DECISION, otherExecutor).orElseThrow())
        .isEqualTo(new ResultVerdict.Rejected("attempt_mismatch"));
    verifyNoInteractions(appender);
    verify(store, never()).recordResult(any(), any(), any(), anyLong());
  }

  @Test
  void resultAppendFailureIs503AndRowUntouched() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(consumedRow()));
    when(store.auditCurrentHash(TENANT, 501L)).thenReturn(Optional.of(CONSUME_ATTEMPT_HASH));
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", null));

    assertThatThrownBy(() -> service.recordResult(TENANT, "u", DECISION, resultReq("failed")))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
    verify(store, never()).recordResult(any(), any(), any(), anyLong());
  }

  /** 权威尝试事件查不到（状态不一致）：不入链，按 503 处理，避免写出没有链接的结果事件。 */
  @Test
  void missingAuthoritativeAttemptIs503WithoutEvent() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(consumedRow()));
    when(store.auditCurrentHash(TENANT, 501L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.recordResult(TENANT, "u", DECISION, resultReq("executed")))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
    verifyNoInteractions(appender);
  }

  /** 已消耗却缺权威指针（不应出现的状态）：503 而不是拆箱 NPE，不入链。 */
  @Test
  void nullConsumeAuditIdIs503WithoutEvent() {
    GuardReceiptEntity r = consumedRow();
    r.consumeAuditId = null;
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(r));

    assertThatThrownBy(() -> service.recordResult(TENANT, "u", DECISION, resultReq("executed")))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
    verifyNoInteractions(appender);
    verify(store, never()).auditCurrentHash(any(), anyLong());
  }

  // ---- 决策回读的执行状态（ADR 0054 §3.3）----

  @Test
  void decisionViewWithoutConsumeHasNoExecutionFields() {
    GuardDecisionReadResponse view = service.decisionView(TENANT, DECISION).orElseThrow();

    assertThat(view.decision()).isEqualTo(response("ALLOW"));
    assertThat(view.actionHash()).isNull();
    assertThat(view.consumedBy()).isNull();
    assertThat(view.result()).isNull();
  }

  @Test
  void decisionViewWithoutReceiptRowHasNoExecutionFields() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.empty());

    GuardDecisionReadResponse view = service.decisionView(TENANT, DECISION).orElseThrow();

    assertThat(view).isEqualTo(GuardDecisionReadResponse.of(response("ALLOW")));
  }

  @Test
  void decisionViewConsumedCarriesActionHashConsumedByAndResult() {
    GuardReceiptEntity r = consumedRow();
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(r));

    GuardDecisionReadResponse consumed = service.decisionView(TENANT, DECISION).orElseThrow();
    assertThat(consumed.actionHash()).isEqualTo(HASH);
    assertThat(consumed.consumedBy()).isEqualTo(
        new GuardDecisionReadResponse.ConsumedBy(ATTEMPT, "2026-11-08T10:01:00Z", "exec-1"));
    assertThat(consumed.result()).isNull();

    r.resultStatus = "aborted";
    r.resultAt = Instant.parse("2026-11-08T10:03:00.250Z");
    GuardDecisionReadResponse withResult = service.decisionView(TENANT, DECISION).orElseThrow();
    assertThat(withResult.result()).isEqualTo(
        new GuardDecisionReadResponse.ResultView("2026-11-08T10:03:00.250Z", "aborted"));
  }

  @Test
  void decisionViewUnknownDecisionIsEmptyWithoutReceiptRead() {
    when(decisions.get(TENANT, DECISION)).thenReturn(Optional.empty());

    assertThat(service.decisionView(TENANT, DECISION)).isEmpty();
    verify(store, never()).findReceipt(any(), any());
  }

  /** 加锁复核失败（并发回写抢先）：同码 409，事件已入链但行不引用。 */
  @Test
  void recheckFailureReturnsSameCodeAfterEvent() {
    when(store.findReceipt(TENANT, DECISION)).thenReturn(Optional.of(consumedRow()));
    when(store.auditCurrentHash(TENANT, 501L)).thenReturn(Optional.of(CONSUME_ATTEMPT_HASH));
    when(store.recordResult(any(), any(), any(), anyLong())).thenReturn(new ResultVerdict.Rejected("result_recorded"));

    assertThat(service.recordResult(TENANT, "u", DECISION, resultReq("executed")).orElseThrow())
        .isEqualTo(new ResultVerdict.Rejected("result_recorded"));
    assertThat(appended(1).get(0).eventType()).isEqualTo(EventType.ACTION_GUARD_RESULT);
  }

  @Test
  void unknownDecisionResultIsEmptyWithoutEvents() {
    when(decisions.get(TENANT, DECISION)).thenReturn(Optional.empty());

    assertThat(service.recordResult(TENANT, "u", DECISION, resultReq("executed"))).isEmpty();
    verifyNoInteractions(appender);
    verify(store, never()).recordResult(any(), any(), any(), anyLong());
  }

  @Test
  void invalidResultFieldsRejectedBeforeAnyEvent() {
    Map<ResultRequest, String> cases = new java.util.LinkedHashMap<>();
    cases.put(new ResultRequest(null, "e", "executed", null, null, null), "attemptId");
    cases.put(new ResultRequest("x", "e", "executed", null, null, null), "attemptId");
    cases.put(new ResultRequest(ATTEMPT, " ", "executed", null, null, null), "executorId");
    cases.put(new ResultRequest(ATTEMPT, "e", null, null, null, null), "status");
    cases.put(new ResultRequest(ATTEMPT, "e", "Aborted", null, null, null), "status");
    cases.put(new ResultRequest(ATTEMPT, "e", "executed", "2026-11-08", null, null), "finishedAt");
    cases.put(new ResultRequest(ATTEMPT, "e", "executed", null, "g".repeat(64), null), "resultHash");
    cases.put(new ResultRequest(ATTEMPT, "e", "executed", null, "a".repeat(65), null), "resultHash");
    cases.put(new ResultRequest(ATTEMPT, "e", "failed", null, null, "x".repeat(65)), "errorCode");
    cases.put(new ResultRequest(ATTEMPT, "e", "failed", null, null, "tab\there"), "errorCode");
    cases.put(new ResultRequest(ATTEMPT, "e", "failed", null, null, "  "), "errorCode");
    cases.forEach((req, field) -> assertThatThrownBy(() -> service.recordResult(TENANT, "u", DECISION, req))
        .as(req.toString())
        .isInstanceOfSatisfying(InvalidResultException.class, e -> assertThat(e.field()).isEqualTo(field)));
    assertThatThrownBy(() -> service.recordResult(TENANT, "u", DECISION, null))
        .isInstanceOfSatisfying(InvalidResultException.class, e -> assertThat(e.field()).isEqualTo("attemptId"));
    // 可选字段齐全且合法；finishedAt 接受带偏移的 ISO-8601
    GuardReceiptService.validate(new ResultRequest(ATTEMPT, "e", "aborted", "2026-11-08T18:02:00+08:00",
        "a".repeat(64), "x".repeat(64)));
    verifyNoInteractions(appender);
  }
}
