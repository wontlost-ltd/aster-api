package io.aster.guard.reconcile;

import io.aster.audit.chain.AuditChainVerifier;
import io.aster.guard.reconcile.GuardReconciliationService.TenantOutcome;
import io.aster.guard.reconcile.GuardReconciliationService.TenantRun;
import io.aster.guard.reconcile.ReconcileFixture.Decision;
import io.aster.policy.entity.AuditLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.aster.guard.reconcile.ReconcileFixture.chainEvents;
import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.orphans;
import static io.aster.guard.reconcile.ReconcileFixture.report;
import static io.aster.guard.reconcile.ReconcileFixture.sideEffects;
import static org.assertj.core.api.Assertions.assertThat;

/** 对账服务（ADR 0054 §10）：按租户加锁事务、run 行、链事件与多副本并发。 */
@QuarkusTest
class GuardReconciliationServiceTest {
  static final String RECONCILIATION = "GUARD_RECONCILIATION";
  private static final String MCP = "aster-guard-mcp@h1";

  @Inject GuardReconciliationService service;
  @Inject GuardOrphanDetector detector;
  @Inject AuditChainVerifier verifier;
  @Inject EntityManager em;
  @Inject MeterRegistry registry;

  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;
  @ConfigProperty(name = "aster.guard.reconciliation.min-interval") Duration minInterval;

  private static String tenant(String scenario) {
    String t = ReconcileFixture.tenant(scenario);
    ReconcileFixture.deploy(t);
    return t;
  }

  private Instant future() {
    return Instant.now().plus(grace).plusSeconds(60);
  }

  private static GuardReconciliationRunEntity run(long id) {
    return QuarkusTransaction.requiringNew().call(() -> GuardReconciliationRunEntity.findById(id));
  }

  private static List<GuardReconciliationRunEntity> runs(String tenant) {
    return QuarkusTransaction.requiringNew().call(() -> GuardReconciliationRunEntity
        .<GuardReconciliationRunEntity>list("tenantId = ?1 order by id", tenant));
  }

  @Test
  void normalPathReconcilesToZero() {
    String t = tenant("svc-normal");
    Decision d = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, d, MCP);
    ReconcileFixture.result(t, d.id(), attempt, MCP);
    report(t, effect("e-1", "aster-guard-mcp", d.id(), attempt, d.actionHash()));

    TenantRun run = service.reconcileTenant(t, future());

    assertThat(run.outcome()).isEqualTo(TenantOutcome.OK);
    assertThat(run.newOrphans()).isZero();
    assertThat(run.open()).isZero();
    assertThat(run.auditId()).isNull();
    GuardReconciliationRunEntity row = run(run.runId());
    assertThat(row.sideEffectsChecked).isEqualTo(1);
    assertThat(row.newByKind).isEqualTo("{}");
    assertThat(row.auditId).isNull();
    assertThat(chainEvents(t, RECONCILIATION)).isEmpty();
    assertThat(sideEffects(t).get(0).reconcileOutcome).isEqualTo("matched");
  }

  @Test
  void newOrphanWritesOneChainEvent() {
    String t = tenant("svc-new");
    Instant start = Instant.now().minusSeconds(5);
    report(t, effect("e-1", "crm", null, null, null));

    TenantRun run = service.reconcileTenant(t, future());

    assertThat(run.outcome()).isEqualTo(TenantOutcome.OK);
    assertThat(run.newOrphans()).isEqualTo(1);
    assertThat(run.open()).isEqualTo(1);
    List<AuditLog> events = chainEvents(t, RECONCILIATION);
    assertThat(events).hasSize(1);
    AuditLog e = events.get(0);
    assertThat(e.id).isEqualTo(run.auditId());
    assertThat(e.performedBy).isEqualTo("SYSTEM");
    assertThat(e.success).isTrue();
    long orphanId = orphans(t).get(0).id;
    assertThat(e.metadata).contains("\"newByKind\":{\"side_effect_without_decision\":1}")
        .contains("\"newOrphanIds\":[" + orphanId + "]").contains("\"runId\":" + run.runId());
    GuardReconciliationRunEntity row = run(run.runId());
    assertThat(row.auditId).isEqualTo(e.id);
    assertThat(row.newOrphans).isEqualTo(1);
    assertThat(row.newByKind).contains("side_effect_without_decision");
    assertThat(orphans(t).get(0).firstRunId).isEqualTo(run.runId());
    assertThat(verifier.verifyChain(t, start, Instant.now().plusSeconds(5)).isValid()).isTrue();
  }

  @Test
  void rerunIsIdempotentAndLateResultResolves() {
    String t = tenant("svc-rerun");
    Decision d = ReconcileFixture.allow(t, 1);
    String attempt = ReconcileFixture.consume(t, d, MCP);
    Instant now = future();
    TenantRun first = service.reconcileTenant(t, now);
    assertThat(first.newOrphans()).isEqualTo(1);

    // min-interval 内再跑：跳过
    assertThat(service.reconcileTenant(t, now.plusSeconds(1)).outcome()).isEqualTo(TenantOutcome.SKIPPED_RECENT);
    Instant second = now.plus(minInterval).plusSeconds(1);
    TenantRun again = service.reconcileTenant(t, second);
    assertThat(again.outcome()).isEqualTo(TenantOutcome.OK);
    assertThat(again.newOrphans()).isZero();
    assertThat(chainEvents(t, RECONCILIATION)).hasSize(1);
    assertThat(orphans(t)).hasSize(1);

    ReconcileFixture.result(t, d.id(), attempt, MCP);
    TenantRun third = service.reconcileTenant(t, second.plus(minInterval).plusSeconds(1));
    assertThat(third.resolved()).isEqualTo(1);
    assertThat(third.open()).isZero();
    List<AuditLog> events = chainEvents(t, RECONCILIATION);
    assertThat(events).hasSize(2);
    assertThat(events.get(1).metadata).contains("\"resolvedOrphans\":1").contains("\"newOrphans\":0");
    assertThat(orphans(t).get(0).status).isEqualTo("RESOLVED");
  }

  @Test
  void concurrentReplicasReconcileTenantOnce() throws Exception {
    String t = tenant("svc-concurrent");
    report(t, effect("e-1", "crm", null, null, null));
    Instant now = future();
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<TenantRun>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(pool.submit(() -> {
          go.await();
          return service.reconcileTenant(t, now);
        }));
      }
      go.countDown();
      List<TenantOutcome> outcomes = new ArrayList<>();
      for (Future<TenantRun> f : futures) {
        outcomes.add(f.get().outcome());
      }
      assertThat(outcomes).containsOnlyOnce(TenantOutcome.OK);
      assertThat(outcomes).filteredOn(o -> o != TenantOutcome.OK).singleElement()
          .isIn(TenantOutcome.SKIPPED_LOCKED, TenantOutcome.SKIPPED_RECENT);
    } finally {
      pool.shutdownNow();
    }
    assertThat(orphans(t)).hasSize(1);
    assertThat(chainEvents(t, RECONCILIATION)).hasSize(1);
    assertThat(runs(t)).hasSize(1);
  }

  @Test
  void oneTenantFailureDoesNotStopOthers() {
    String a = tenant("svc-fail-a");
    String b = tenant("svc-fail-b");
    report(a, effect("e-1", "crm", null, null, null));
    report(b, effect("e-1", "crm", null, null, null));
    GuardOrphanDetector real = ClientProxy.unwrap(detector);
    GuardOrphanDetector mock = Mockito.mock(GuardOrphanDetector.class, AdditionalAnswers.delegatesTo(real));
    Mockito.doThrow(new IllegalStateException("模拟检测失败")).when(mock).detectLedger(ArgumentMatchers.eq(a),
        ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt());
    QuarkusMock.installMockForType(mock, GuardOrphanDetector.class);

    List<TenantRun> all = service.reconcileAll(future());

    TenantRun ra = all.stream().filter(r -> r.tenantId().equals(a)).findFirst().orElseThrow();
    TenantRun rb = all.stream().filter(r -> r.tenantId().equals(b)).findFirst().orElseThrow();
    assertThat(ra.outcome()).isEqualTo(TenantOutcome.FAILED);
    assertThat(runs(a)).isEmpty();
    assertThat(sideEffects(a).get(0).reconciledAt).isNull();
    assertThat(rb.outcome()).isEqualTo(TenantOutcome.OK);
    assertThat(rb.newOrphans()).isEqualTo(1);
  }

  @Test
  void candidateTenantsCoverAllSources() {
    String openOnly = tenant("svc-cand-open");
    String pendingOnly = tenant("svc-cand-pending");
    String receiptOnly = tenant("svc-cand-receipt");
    String decisionOnly = tenant("svc-cand-decision");
    String stale = tenant("svc-cand-stale");
    QuarkusTransaction.requiringNew().run(() -> {
      GuardOrphanActionEntity o = GuardReconcilePersistenceTest.orphan(openOnly, OrphanKind.SIDE_EFFECT_MISMATCH,
          "se:1", null);
      o.detectedAt = Instant.now().minus(400, ChronoUnit.DAYS);
      o.persist();
      GuardOrphanActionEntity resolved = GuardReconcilePersistenceTest.orphan(stale, OrphanKind.ALLOW_UNCONSUMED,
          UUID.randomUUID().toString(), null);
      resolved.status = "RESOLVED";
      resolved.persist();
    });
    report(pendingOnly, effect("e-1", "crm", null, null, null));
    ReconcileFixture.allow(receiptOnly, 1);
    ReconcileFixture.pending(decisionOnly);
    // 窗口外：stale 只有一条非 OPEN 孤儿与一条早已对账的上报
    report(stale, effect("e-1", "crm", null, null, null));
    QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
        "UPDATE guard_side_effects SET reconciled_at = now() WHERE tenant_id = ?1").setParameter(1, stale)
        .executeUpdate());

    List<String> candidates = service.candidateTenants(Instant.now().minus(Duration.ofDays(7)));

    assertThat(candidates).contains(openOnly, pendingOnly, receiptOnly, decisionOnly).doesNotContain(stale);
    assertThat(service.candidateTenants(Instant.now().plusSeconds(3600)))
        .contains(openOnly, pendingOnly).doesNotContain(receiptOnly, decisionOnly, stale);
  }

  @Test
  void reconcileAllMarksLoopSucceeded() {
    Instant now = future();
    service.reconcileAll(now);
    // 本副本进程内值：Prometheus 名 guard_reconciliation_last_success_seconds
    assertThat(registry.get("guard.reconciliation.last.success").gauge().value()).isEqualTo(now.getEpochSecond());
  }
}
