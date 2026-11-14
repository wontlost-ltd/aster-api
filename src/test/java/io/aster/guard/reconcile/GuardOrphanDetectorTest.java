package io.aster.guard.reconcile;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.reconcile.GuardOrphanDetector.Detected;
import io.aster.guard.reconcile.GuardOrphanDetector.SideEffectBatch;
import io.aster.guard.reconcile.GuardOrphanDetector.Window;
import io.aster.guard.reconcile.ReconcileFixture.Decision;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.orphans;
import static io.aster.guard.reconcile.ReconcileFixture.report;
import static io.aster.guard.reconcile.ReconcileFixture.sideEffects;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单租户孤儿检测（ADR 0054 §10.3）：真实端点造决策 / consume / 结果 / 上报，篡改类直接改行；每个用例新建租户，
 * {@code now} 显式取「宽限之后」的未来时刻。
 */
@QuarkusTest
class GuardOrphanDetectorTest {
  private static final long RUN_ID = 1L;
  private static final int BATCH = 5000;
  private static final String MCP = "aster-guard-mcp@h1";

  @Inject GuardOrphanDetector detector;
  @Inject ExecutorLabels labels;
  @Inject AuditChainAppender appender;
  @Inject EntityManager em;

  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;
  @ConfigProperty(name = "aster.guard.reconciliation.lookback") Duration lookback;

  private String tenant(String scenario) {
    String t = ReconcileFixture.tenant(scenario);
    ReconcileFixture.deploy(t);
    return t;
  }

  private Window future() {
    return Window.of(Instant.now().plus(grace).plusSeconds(60), lookback, grace);
  }

  private List<Detected> ledger(String t, Window w, int batch) {
    return QuarkusTransaction.requiringNew().call(() -> detector.detectLedger(t, w, RUN_ID, batch));
  }

  private SideEffectBatch sides(String t, Window w, int batch) {
    return QuarkusTransaction.requiringNew().call(() -> detector.detectSideEffects(t, w, RUN_ID, batch));
  }

  private int autoResolve(String t, Window w, Duration ttl) {
    return QuarkusTransaction.requiringNew().call(() -> detector.autoResolve(t, w, ttl));
  }

  private void sql(String statement, Object... params) {
    QuarkusTransaction.requiringNew().run(() -> {
      var q = em.createNativeQuery(statement);
      for (int i = 0; i < params.length; i++) {
        q.setParameter(i + 1, params[i]);
      }
      q.executeUpdate();
    });
  }

  private static List<String> kinds(List<GuardOrphanActionEntity> rows) {
    return rows.stream().map(o -> o.kind).toList();
  }

  @Test
  void consumedWithoutResultPastGrace() {
    String t = tenant("cwr");
    Decision d = ReconcileFixture.allow(t, 1);
    ReconcileFixture.consume(t, d, MCP);
    GuardReceiptEntity row = ReconcileFixture.receipt(d.id());

    Window early = Window.of(row.consumedAt.plus(grace).minusSeconds(1), lookback, grace);
    assertThat(ledger(t, early, BATCH)).isEmpty();
    // 宽限边界：cutoff 排他，consumed_at == cutoff 不算
    Window edge = Window.of(row.consumedAt.plus(grace), lookback, grace);
    assertThat(ledger(t, edge, BATCH)).isEmpty();

    List<Detected> found = ledger(t, future(), BATCH);
    assertThat(found).extracting(Detected::kind).containsExactly(OrphanKind.CONSUMED_WITHOUT_RESULT);
    GuardOrphanActionEntity o = orphans(t).get(0);
    assertThat(o.executorLabel).isEqualTo("aster-guard-mcp");
    assertThat(o.executorId).isEqualTo(MCP);
    assertThat(o.severity).isEqualTo("warning");
    assertThat(o.subjectKey).isEqualTo(d.id());
    assertThat(o.decisionId).isEqualTo(UUID.fromString(d.id()));
    assertThat(o.firstRunId).isEqualTo(RUN_ID);
    assertThat(o.detail).contains("\"decisionId\": \"" + d.id() + "\"").contains("consumedAt");
  }

  @Test
  void allowUnconsumedAfterExpiry() {
    String t = tenant("au");
    Decision d = ReconcileFixture.allow(t, 1);
    GuardReceiptEntity row = ReconcileFixture.receipt(d.id());
    assertThat(ledger(t, Window.of(row.expiresAt.plus(grace).minusSeconds(1), lookback, grace), BATCH)).isEmpty();

    List<Detected> found = ledger(t, Window.of(row.expiresAt.plus(grace).plusSeconds(1), lookback, grace), BATCH);

    assertThat(found).extracting(Detected::kind).containsExactly(OrphanKind.ALLOW_UNCONSUMED);
    assertThat(found.get(0).executorLabel()).isEqualTo("unknown");
    assertThat(orphans(t).get(0).severity).isEqualTo("info");
  }

  @Test
  void consumeOnDenyIsCritical() {
    String t = tenant("deny");
    Decision d = ReconcileFixture.deny(t);
    insertReceipt(t, d.id(), 99L, null);

    List<Detected> found = ledger(t, future(), BATCH);

    assertThat(found).extracting(Detected::kind).contains(OrphanKind.CONSUME_ON_NON_ALLOW);
    GuardOrphanActionEntity o = orphans(t).stream().filter(x -> x.kind.equals("consume_on_non_allow")).findFirst()
        .orElseThrow();
    assertThat(o.severity).isEqualTo("critical");
    assertThat(o.detail).contains("\"outcome\": \"DENY\"");
  }

  /** 收据行指向他租户的决策同样是 consume_on_non_allow（收据与决策租户不同）。 */
  @Test
  void receiptTenantDifferentFromDecisionIsNonAllow() {
    String a = tenant("xt-a");
    String b = tenant("xt-b");
    Decision d = ReconcileFixture.allow(a, 1);
    // 把 A 的收据行改记到 B 名下：B 侧的行指向 A 的决策
    sql("UPDATE guard_receipts SET tenant_id = ?1, consumed_at = now(), consume_audit_id = 7 WHERE decision_id = ?2",
        b, UUID.fromString(d.id()));

    assertThat(ledger(b, future(), BATCH)).extracting(Detected::kind).contains(OrphanKind.CONSUME_ON_NON_ALLOW);
    assertThat(orphans(a)).isEmpty();
  }

  @Test
  void approvedDecisionConsumeIsNotNonAllow() {
    String t = tenant("approved");
    Decision d = ReconcileFixture.pending(t);
    ReconcileFixture.approve(t, d);
    String attempt = ReconcileFixture.consume(t, d, MCP);
    ReconcileFixture.result(t, d.id(), attempt, MCP);

    assertThat(ledger(t, future(), BATCH)).isEmpty();
  }

  @Test
  void resultWithoutConsume() {
    String t = tenant("rwc");
    Decision d = ReconcileFixture.allow(t, 1);
    sql("UPDATE guard_receipts SET result_status = 'executed', result_at = now() WHERE decision_id = ?1",
        UUID.fromString(d.id()));

    List<Detected> found = ledger(t, future(), BATCH);

    assertThat(found).extracting(Detected::kind).contains(OrphanKind.RESULT_WITHOUT_CONSUME);
    assertThat(found).extracting(Detected::kind).doesNotContain(OrphanKind.CONSUMED_WITHOUT_RESULT);
  }

  @Test
  void pointerIsAuthoritativeNotConsumedAt() {
    String t = tenant("pointer");
    Decision d = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, d, MCP);
    sql("UPDATE guard_receipts SET consume_audit_id = NULL WHERE decision_id = ?1", UUID.fromString(d.id()));
    // 另一条：consume 被拒（action_mismatch），链上有 ATTEMPT 与 rejected OUTCOME，行无指针
    Decision rejected = ReconcileFixture.allow(t, 2);
    Response r = ReconcileFixture.consumeRaw(t, rejected.id(), "00".repeat(32), UUID.randomUUID().toString(), MCP);
    assertThat(r.statusCode()).isEqualTo(409);
    report(t, effect("e-1", "crm", d.id(), attempt, d.actionHash()),
        effect("e-2", "crm", rejected.id(), null, rejected.actionHash()));

    Window w = future();
    assertThat(ledger(t, w, BATCH)).extracting(Detected::kind).doesNotContain(OrphanKind.CONSUMED_WITHOUT_RESULT);
    SideEffectBatch batch = sides(t, w, BATCH);

    assertThat(batch.detected()).extracting(Detected::kind)
        .containsExactly(OrphanKind.SIDE_EFFECT_UNCONSUMED, OrphanKind.SIDE_EFFECT_UNCONSUMED);
    assertThat(sideEffects(t)).extracting(s -> s.reconcileOutcome)
        .containsExactly("side_effect_unconsumed", "side_effect_unconsumed");
    assertThat(orphans(t).stream().filter(o -> o.kind.equals("side_effect_unconsumed")))
        .allSatisfy(o -> assertThat(o.detail).contains("\"reason\": \"not_consumed\""));
  }

  @Test
  void sideEffectsClassifiedAndMarked() {
    String t = tenant("classify");
    Decision deny = ReconcileFixture.deny(t);
    Decision ok = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, ok, MCP);
    Decision wrongHash = ReconcileFixture.allow(t, 2);
    String attempt2 = ReconcileFixture.consume(t, wrongHash, MCP);
    report(t,
        effect("e-1", "aster-guard-mcp", null, null, null),
        effect("e-2", "aster-guard-mcp", deny.id(), null, null),
        effect("e-3", "aster-guard-mcp", ok.id(), attempt, ok.actionHash()),
        effect("e-4", "aster-guard-mcp", ok.id(), attempt, ok.actionHash()),
        effect("e-5", "aster-guard-mcp", wrongHash.id(), attempt2, "cd".repeat(32)),
        // 不同上报方观测同一动作不是重放
        effect("e-6", "crm-db", ok.id(), attempt, null));

    SideEffectBatch batch = sides(t, future(), BATCH);

    assertThat(batch.checked()).isEqualTo(6);
    assertThat(sideEffects(t)).extracting(s -> s.reconcileOutcome).containsExactly("side_effect_without_decision",
        "side_effect_unconsumed", "matched", "side_effect_mismatch", "side_effect_mismatch", "matched");
    assertThat(sideEffects(t)).allSatisfy(s -> assertThat(s.reconciledAt).isNotNull());
    List<GuardOrphanActionEntity> rows = orphans(t);
    assertThat(kinds(rows)).containsExactly("side_effect_without_decision", "side_effect_unconsumed",
        "side_effect_mismatch", "side_effect_mismatch");
    assertThat(rows.get(2).detail).contains("\"reason\": \"replayed\"").contains("\"effectId\": \"e-4\"");
    assertThat(rows.get(3).detail).contains("\"reason\": \"action_mismatch\"");
    assertThat(rows).allSatisfy(o -> {
      assertThat(o.sideEffectId).isNotNull();
      assertThat(o.subjectKey).isEqualTo("se:" + o.sideEffectId);
      assertThat(o.executorLabel).isEqualTo("aster-guard-mcp");
      assertThat(o.severity).isEqualTo("critical");
    });
  }

  /** 后到一轮的同源上报看到前一轮已匹配的那条，判重放。 */
  @Test
  void replayAcrossRunsDetected() {
    String t = tenant("replay-runs");
    Decision ok = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, ok, MCP);
    report(t, effect("e-1", "crm", ok.id(), attempt, null));
    Window w = future();
    assertThat(sides(t, w, BATCH).detected()).isEmpty();
    report(t, effect("e-2", "crm", ok.id(), attempt, null));

    assertThat(sides(t, w, BATCH).detected()).extracting(Detected::kind)
        .containsExactly(OrphanKind.SIDE_EFFECT_MISMATCH);
  }

  @Test
  void foreignTenantDecisionIdIsWithoutDecision() {
    String a = tenant("foreign-a");
    String b = tenant("foreign-b");
    Decision d = ReconcileFixture.allow(a, 1);
    String attempt = ReconcileFixture.consume(a, d, MCP);
    report(b, effect("e-1", "crm", d.id(), attempt, d.actionHash()));

    sides(b, future(), BATCH);

    assertThat(kinds(orphans(b))).containsExactly("side_effect_without_decision");
    assertThat(orphans(b).get(0).detail).contains("\"reason\": \"unknown_decision\"");
    assertThat(orphans(a)).isEmpty();
  }

  @Test
  void ledgerBatchLimitProgresses() {
    String t = tenant("batch");
    for (int i = 0; i < 5; i++) {
      ReconcileFixture.consume(t, ReconcileFixture.allow(t, 100 + i), MCP);
      report(t, effect("e-" + i, "crm", null, null, null));
    }
    Window w = future();
    List<Integer> ledgerSizes = new ArrayList<>();
    List<Integer> checked = new ArrayList<>();
    for (int round = 0; round < 4; round++) {
      ledgerSizes.add(ledger(t, w, 2).size());
      checked.add(sides(t, w, 2).checked());
    }
    assertThat(ledgerSizes).containsExactly(2, 2, 1, 0);
    assertThat(checked).containsExactly(2, 2, 1, 0);
  }

  @Test
  void detectionIsIdempotent() {
    String t = tenant("idem");
    ReconcileFixture.consume(t, ReconcileFixture.allow(t, 1), MCP);
    report(t, effect("e-1", "crm", null, null, null));
    Window w = future();
    assertThat(ledger(t, w, BATCH)).hasSize(1);
    assertThat(sides(t, w, BATCH).checked()).isEqualTo(1);

    assertThat(ledger(t, w, BATCH)).isEmpty();
    assertThat(sides(t, w, BATCH).checked()).isZero();
    assertThat(orphans(t)).hasSize(2);
  }

  @Test
  void lateResultAutoResolves() {
    String t = tenant("late");
    Decision d = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, d, MCP);
    Decision unconsumed = ReconcileFixture.allow(t, 2);
    report(t, effect("e-1", "crm", null, null, null));
    Window w = Window.of(ReconcileFixture.receipt(unconsumed.id()).expiresAt.plus(grace).plusSeconds(1), lookback,
        grace);
    assertThat(ledger(t, w, BATCH)).extracting(Detected::kind)
        .containsExactlyInAnyOrder(OrphanKind.CONSUMED_WITHOUT_RESULT, OrphanKind.ALLOW_UNCONSUMED);
    sides(t, w, BATCH);
    Duration ttl = Duration.ofDays(7);
    assertThat(autoResolve(t, w, ttl)).isZero();

    ReconcileFixture.result(t, d.id(), attempt, MCP);
    assertThat(autoResolve(t, w, ttl)).isEqualTo(1);
    GuardOrphanActionEntity late = orphans(t).stream().filter(o -> o.kind.equals("consumed_without_result"))
        .findFirst().orElseThrow();
    assertThat(late.status).isEqualTo("RESOLVED");
    assertThat(late.resolution).isEqualTo("late_result");
    assertThat(late.resolvedAt).isEqualTo(w.now().truncatedTo(ChronoUnit.MICROS));

    Window aged = new Window(w.now().plus(ttl).plusSeconds(1), w.since(), w.cutoff());
    assertThat(autoResolve(t, aged, ttl)).isEqualTo(1);
    assertThat(orphans(t)).filteredOn(o -> o.kind.equals("allow_unconsumed"))
        .singleElement().satisfies(o -> assertThat(o.resolution).isEqualTo("aged_out"));
    // critical 类永不自动解除
    Window much = new Window(w.now().plus(Duration.ofDays(400)), w.since(), w.cutoff());
    assertThat(autoResolve(t, much, ttl)).isZero();
    assertThat(orphans(t)).filteredOn(o -> o.kind.equals("side_effect_without_decision"))
        .singleElement().satisfies(o -> assertThat(o.status).isEqualTo("OPEN"));
  }

  @Test
  void executorLabelSqlMatchesJava() {
    String t = tenant("labels");
    List<String> ids = Arrays.asList("aster-guard-mcp@h", "ASTER-GUARD-SDK@x:1", "my-bot@p", "", null,
        "aster-cloud-exec", " \t", "@host");
    List<String> decisions = new ArrayList<>();
    for (String executor : ids) {
      Decision d = ReconcileFixture.allow(t, 1000 + decisions.size());
      sql("UPDATE guard_receipts SET consumed_at = now(), consume_audit_id = 5, executor_id = ?1 WHERE decision_id = ?2",
          executor, UUID.fromString(d.id()));
      decisions.add(d.id());
    }

    ledger(t, future(), BATCH);

    Map<String, String> bySubject = new java.util.HashMap<>();
    orphans(t).forEach(o -> bySubject.put(o.subjectKey, o.executorLabel));
    for (int i = 0; i < ids.size(); i++) {
      assertThat(bySubject.get(decisions.get(i))).as(String.valueOf(ids.get(i))).isEqualTo(labels.of(ids.get(i)));
    }
  }

  /** #522：链上有决策事件而决策行未落库 → orphan_receipt（info），只记一行，不混入 critical 类别。 */
  @Test
  void decisionEventWithoutRowIsOrphanReceipt() {
    String t = tenant("orphan-receipt");
    String lost = UUID.randomUUID().toString();
    appender.appendSync(new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), t, "guard.probe", "decide",
        null, null, null, "user-race-loser", true, null, null, Map.of("decisionId", lost, "outcome", "ALLOW"),
        null, null, null, null));
    // 正常决策：有事件也有行，不报
    Decision ok = ReconcileFixture.allow(t, 1);
    ReconcileFixture.result(t, ok.id(), ReconcileFixture.consume(t, ok, MCP), MCP);
    report(t, effect("e-1", "crm", lost, null, null));

    // 宽限之内不报：事件可能仍在「先入链、后落库」之间
    assertThat(ledger(t, Window.of(Instant.now(), lookback, grace), BATCH)).isEmpty();
    Window w = future();
    List<Detected> found = ledger(t, w, BATCH);
    sides(t, w, BATCH);

    assertThat(found).extracting(Detected::kind).containsExactly(OrphanKind.ORPHAN_RECEIPT);
    assertThat(ledger(t, w, BATCH)).isEmpty();
    List<GuardOrphanActionEntity> rows = orphans(t);
    assertThat(kinds(rows)).containsExactlyInAnyOrder("orphan_receipt", "side_effect_without_decision");
    GuardOrphanActionEntity o = rows.stream().filter(x -> x.kind.equals("orphan_receipt")).findFirst().orElseThrow();
    assertThat(o.severity).isEqualTo("info");
    assertThat(o.subjectKey).isEqualTo(lost);
    assertThat(o.decisionId).isEqualTo(UUID.fromString(lost));
    assertThat(o.executorLabel).isEqualTo("unknown");
    assertThat(o.detail).contains("auditId");
  }

  @Test
  void malformedDecisionIdInEventDoesNotBreakScan() {
    String t = tenant("orphan-receipt-bad");
    appender.appendSync(new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), t, "m", "f", null, null, null,
        "u", true, null, null, Map.of("decisionId", "not-a-uuid"), null, null, null, null));

    List<Detected> found = ledger(t, future(), BATCH);

    assertThat(found).extracting(Detected::kind).containsExactly(OrphanKind.ORPHAN_RECEIPT);
    assertThat(orphans(t).get(0).decisionId).isNull();
    assertThat(orphans(t).get(0).subjectKey).isEqualTo("not-a-uuid");
  }

  private void insertReceipt(String tenant, String decisionId, Long consumeAuditId, String resultStatus) {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity r = new GuardReceiptEntity();
      r.decisionId = UUID.fromString(decisionId);
      r.tenantId = tenant;
      r.actionHash = "ab".repeat(32);
      r.kid = "test-a";
      r.issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      r.expiresAt = r.issuedAt.plusSeconds(300);
      r.signature = "sig";
      r.consumedAt = consumeAuditId == null ? null : r.issuedAt.plusSeconds(1);
      r.consumeAuditId = consumeAuditId;
      r.resultStatus = resultStatus;
      r.persist();
    });
  }
}
