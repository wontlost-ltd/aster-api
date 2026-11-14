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
import io.restassured.response.Response;
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

  /**
   * 锁顺序（计划 D5）：对账（孤儿行 UPDATE → 链锁）、确认（孤儿行 FOR UPDATE → 链锁）、consume（链锁 → 收据行锁）、
   * 结果回写（链锁 → 收据行锁）在孤儿行与租户链锁上重叠，同时跑完且无死锁，链仍完整。
   */
  @Test
  void reconcileConcurrentWithChainWritersNoDeadlock() throws Exception {
    String t = tenant("svc-deadlock");
    Instant start = Instant.now().minusSeconds(5);
    Decision c1 = ReconcileFixture.allow(t, 1);
    String a1 = ReconcileFixture.consume(t, c1, MCP);
    Decision c2 = ReconcileFixture.allow(t, 2);
    String a2 = ReconcileFixture.consume(t, c2, MCP);
    report(t, effect("e-0", "crm", null, null, null));
    Instant now = future();
    assertThat(service.reconcileTenant(t, now).newOrphans()).isEqualTo(3);
    long sideEffectOrphan = orphans(t).stream().filter(o -> o.kind.equals("side_effect_without_decision"))
        .findFirst().orElseThrow().id;
    report(t, effect("e-1", "crm", null, null, null), effect("e-2", "crm", null, null, null));
    List<Decision> fresh = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      fresh.add(ReconcileFixture.allow(t, 100 + i));
    }

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(12);
    List<Future<?>> tasks = new ArrayList<>();
    try {
      for (Decision d : fresh) {
        tasks.add(pool.submit(() -> {
          go.await();
          return ReconcileFixture.consume(t, d, MCP);
        }));
      }
      tasks.add(pool.submit(() -> {
        go.await();
        ReconcileFixture.result(t, c1.id(), a1, MCP);
        return null;
      }));
      tasks.add(pool.submit(() -> {
        go.await();
        ReconcileFixture.result(t, c2.id(), a2, MCP);
        return null;
      }));
      tasks.add(pool.submit(() -> {
        go.await();
        Response r = io.restassured.RestAssured.given().header("X-Tenant-Id", t).header("X-User-Id", "admin-1")
            .contentType(io.restassured.http.ContentType.JSON).body(java.util.Map.of("comment", "并发确认"))
            .when().post("/api/v1/guard/reconciliation/orphans/" + sideEffectOrphan + "/ack");
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        return null;
      }));
      tasks.add(pool.submit(() -> {
        go.await();
        TenantRun run = service.reconcileTenant(t, now.plus(minInterval).plusSeconds(1));
        assertThat(run.outcome()).isEqualTo(TenantOutcome.OK);
        return run;
      }));
      go.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      for (Future<?> f : tasks) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(verifier.verifyChain(t, start, Instant.now().plusSeconds(5)).isValid()).isTrue();
    assertThat(sideEffects(t)).allSatisfy(s -> assertThat(s.reconciledAt).isNotNull());
    assertThat(orphans(t)).filteredOn(o -> o.id == sideEffectOrphan).singleElement()
        .satisfies(o -> assertThat(o.status).isEqualTo("ACKNOWLEDGED"));
  }

  /** 保留期（计划 D9）：OPEN 孤儿永不删除，未对账的上报无论多旧都不删；删上报后孤儿 side_effect_id 置空、detail 仍自描述。 */
  @Test
  void purgeRespectsRetentionAndKeepsOpen() {
    String t = ReconcileFixture.tenant("svc-purge");
    Instant now = Instant.now();
    GuardSideEffectEntity old = GuardReconcilePersistenceTest.sideEffect(t, "crm", "old");
    old.reconciledAt = now.minus(Duration.ofDays(91));
    old.reconcileOutcome = "side_effect_without_decision";
    GuardSideEffectEntity recent = GuardReconcilePersistenceTest.sideEffect(t, "crm", "recent");
    recent.reconciledAt = now.minus(Duration.ofDays(89));
    GuardSideEffectEntity pending = GuardReconcilePersistenceTest.sideEffect(t, "crm", "pending");
    pending.receivedAt = now.minus(Duration.ofDays(400));
    QuarkusTransaction.requiringNew().run(() -> {
      old.persist();
      recent.persist();
      pending.persist();
    });
    GuardOrphanActionEntity openOrphan = GuardReconcilePersistenceTest.orphan(t, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION,
        "se:" + old.id, old.id);
    openOrphan.detectedAt = now.minus(Duration.ofDays(400));
    GuardOrphanActionEntity resolvedOld = GuardReconcilePersistenceTest.orphan(t, OrphanKind.ALLOW_UNCONSUMED,
        UUID.randomUUID().toString(), null);
    resolvedOld.status = "RESOLVED";
    resolvedOld.resolution = "aged_out";
    resolvedOld.detectedAt = now.minus(Duration.ofDays(200));
    resolvedOld.resolvedAt = now.minus(Duration.ofDays(181));
    GuardOrphanActionEntity resolvedRecent = GuardReconcilePersistenceTest.orphan(t, OrphanKind.ALLOW_UNCONSUMED,
        UUID.randomUUID().toString(), null);
    resolvedRecent.status = "RESOLVED";
    resolvedRecent.detectedAt = now.minus(Duration.ofDays(200));
    resolvedRecent.resolvedAt = now.minus(Duration.ofDays(179));
    GuardReconciliationRunEntity oldRun = runRow(t, now.minus(Duration.ofDays(31)));
    GuardReconciliationRunEntity recentRun = runRow(t, now.minus(Duration.ofDays(29)));
    QuarkusTransaction.requiringNew().run(() -> {
      openOrphan.persist();
      resolvedOld.persist();
      resolvedRecent.persist();
      oldRun.persist();
      recentRun.persist();
    });

    GuardReconciliationService.PurgeResult r = service.purgeExpired(now);

    assertThat(r.sideEffects()).isGreaterThanOrEqualTo(1);
    assertThat(r.orphans()).isGreaterThanOrEqualTo(1);
    assertThat(r.runs()).isGreaterThanOrEqualTo(1);
    assertThat(sideEffects(t)).extracting(s -> s.effectId).containsExactlyInAnyOrder("recent", "pending");
    assertThat(orphans(t)).extracting(o -> o.id).containsExactlyInAnyOrder(openOrphan.id, resolvedRecent.id);
    GuardOrphanActionEntity kept = orphans(t).stream().filter(o -> o.id.equals(openOrphan.id)).findFirst()
        .orElseThrow();
    assertThat(kept.sideEffectId).isNull();
    assertThat(kept.detail).contains("effectId");
    assertThat(runs(t)).extracting(x -> x.id).containsExactly(recentRun.id);
  }

  private static GuardReconciliationRunEntity runRow(String tenant, Instant finishedAt) {
    GuardReconciliationRunEntity run = new GuardReconciliationRunEntity();
    run.tenantId = tenant;
    run.windowStart = finishedAt.minus(Duration.ofDays(7));
    run.windowEnd = finishedAt;
    run.startedAt = finishedAt;
    run.finishedAt = finishedAt;
    run.newByKind = "{}";
    return run;
  }
}
