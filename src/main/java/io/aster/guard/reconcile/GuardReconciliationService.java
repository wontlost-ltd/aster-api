package io.aster.guard.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.reconcile.GuardOrphanDetector.Detected;
import io.aster.guard.reconcile.GuardOrphanDetector.SideEffectBatch;
import io.aster.guard.reconcile.GuardOrphanDetector.Window;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Guard 对账（ADR 0054 §10，#465）：按租户比对副作用上报与决策 / consume / result 账本，把对不上的记录为孤儿。
 *
 * <p>每租户一个独立事务（{@link #reconcileTenant}）：先 {@code pg_try_advisory_xact_lock} 取对账锁（拿不到即
 * {@code skipped_locked}），再看最近一次 run 是否在 {@code min-interval} 内（{@code skipped_recent}）；之后检测、解除、
 * 计数，有变化才追加一条 {@code GUARD_RECONCILIATION}，最后回填 run 行。孤儿行、上报结论、run 行与链事件同一事务提交，
 * 任一步失败整租户回滚、下轮重来。
 *
 * <p>锁顺序：对账锁 → 孤儿 / 上报行锁 → 租户链锁（{@code appendInCurrentTransaction} 内部）永远最后取，链追加之后只剩
 * run 行一条 UPDATE。其它链写入方只取链锁、从不取对账锁；确认端点同样「行锁 → 链锁」，因此不存在环。
 * 指标在事务提交之后才计，回滚的 run 不计新孤儿。调用方不得在外层事务中调用。
 */
@ApplicationScoped
public class GuardReconciliationService {
  static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended('guard-reconcile:' || :t, 0))";
  /**
   * 候选租户（计划 D3）：窗口内有收据、有决策事件（orphan_receipt 要扫只有事件而决策行未落库的租户）、有待对账上报、
   * 有 OPEN 孤儿。决策事件先于决策行写入，有决策行的租户必有决策事件，不必另扫 guard_decisions。
   */
  static final String CANDIDATE_TENANTS = "SELECT tenant_id FROM guard_receipts WHERE issued_at >= :since "
      + "OR consumed_at >= :since "
      + "UNION SELECT tenant_id FROM audit_logs WHERE event_type = 'ACTION_GUARD_DECISION' AND timestamp >= :since "
      + "UNION SELECT tenant_id FROM guard_side_effects WHERE reconciled_at IS NULL "
      + "UNION SELECT tenant_id FROM guard_orphan_actions WHERE status = 'OPEN' "
      + "ORDER BY 1";

  /** 保留期清理每批删除的行数（{@code ctid IN (SELECT ctid … LIMIT n)}），循环到 0。 */
  static final int PURGE_BATCH = 10_000;
  static final String PURGE_SIDE_EFFECTS = "DELETE FROM guard_side_effects WHERE ctid IN (SELECT ctid "
      + "FROM guard_side_effects WHERE reconciled_at < :cutoff LIMIT :batch)";
  static final String PURGE_ORPHANS = "DELETE FROM guard_orphan_actions WHERE ctid IN (SELECT ctid "
      + "FROM guard_orphan_actions WHERE status <> 'OPEN' AND COALESCE(resolved_at, detected_at) < :cutoff LIMIT :batch)";
  static final String PURGE_RUNS = "DELETE FROM guard_reconciliation_runs WHERE ctid IN (SELECT ctid "
      + "FROM guard_reconciliation_runs WHERE finished_at < :cutoff LIMIT :batch)";

  /** 三项实际删除数。 */
  public record PurgeResult(int sideEffects, int orphans, int runs) {
    public boolean any() {
      return sideEffects + orphans + runs > 0;
    }
  }

  /** 每租户一次对账的结果；{@code wire()} 是指标 {@code guard_reconciliation_runs_total{outcome}} 的取值。 */
  public enum TenantOutcome {
    OK, FAILED, SKIPPED_LOCKED, SKIPPED_RECENT;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 跳过与失败时 {@code runId / auditId} 为 null、计数为 0。 */
  public record TenantRun(String tenantId, TenantOutcome outcome, Long runId, int newOrphans, int resolved, int open,
                          Long auditId) {
    static TenantRun of(String tenantId, TenantOutcome outcome) {
      return new TenantRun(tenantId, outcome, null, 0, 0, 0, null);
    }
  }

  /** 事务内的结果：提交成功后才据此计指标。 */
  private record Attempt(TenantRun run, List<Detected> detected) {}

  @Inject EntityManager em;
  @Inject GuardOrphanDetector detector;
  @Inject AuditChainAppender appender;
  @Inject GuardReceiptBuilder receiptBuilder;
  @Inject GuardOrphanMetrics metrics;
  @Inject ObjectMapper mapper;

  @ConfigProperty(name = "aster.guard.reconciliation.lookback") Duration lookback;
  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;
  @ConfigProperty(name = "aster.guard.reconciliation.min-interval") Duration minInterval;
  @ConfigProperty(name = "aster.guard.reconciliation.batch-limit") int batchLimit;
  @ConfigProperty(name = "aster.guard.reconciliation.allow-unconsumed-ttl") Duration allowUnconsumedTtl;
  @ConfigProperty(name = "aster.guard.reconciliation.side-effect-retention") Duration sideEffectRetention;
  @ConfigProperty(name = "aster.guard.reconciliation.orphan-retention") Duration orphanRetention;
  @ConfigProperty(name = "aster.guard.reconciliation.run-retention") Duration runRetention;

  /** 枚举候选租户逐个对账；单租户失败不影响后续。循环跑完（含无租户、含跳过）即记最近成功时刻。 */
  public List<TenantRun> reconcileAll(Instant now) {
    List<TenantRun> runs = new ArrayList<>();
    for (String tenantId : candidateTenants(now.minus(lookback))) {
      runs.add(reconcileTenant(tenantId, now));
    }
    metrics.markLoopSucceeded(now);
    return runs;
  }

  public List<String> candidateTenants(Instant since) {
    return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(CANDIDATE_TENANTS)
        .setParameter("since", since).getResultList().stream().map(String.class::cast).toList());
  }

  public TenantRun reconcileTenant(String tenantId, Instant now) {
    Attempt attempt;
    try {
      attempt = QuarkusTransaction.requiringNew().call(() -> reconcileLocked(tenantId, now));
    } catch (RuntimeException e) {
      Log.errorf(e, "Guard 对账失败，整租户回滚、下轮重试: tenant=%s", tenantId);
      metrics.recordRun(TenantOutcome.FAILED);
      return TenantRun.of(tenantId, TenantOutcome.FAILED);
    }
    metrics.recordRun(attempt.run().outcome());
    attempt.detected().forEach(d -> metrics.recordDetected(d.kind()));
    return attempt.run();
  }

  private Attempt reconcileLocked(String tenantId, Instant now) {
    Object locked = em.createNativeQuery(TRY_LOCK).setParameter("t", tenantId).getSingleResult();
    if (!Boolean.TRUE.equals(locked)) {
      return new Attempt(TenantRun.of(tenantId, TenantOutcome.SKIPPED_LOCKED), List.of());
    }
    Optional<GuardReconciliationRunEntity> latest = GuardReconciliationRunEntity.latest(tenantId);
    if (latest.isPresent() && latest.get().finishedAt.isAfter(now.minus(minInterval))) {
      return new Attempt(TenantRun.of(tenantId, TenantOutcome.SKIPPED_RECENT), List.of());
    }
    Window w = Window.of(now, lookback, grace);
    GuardReconciliationRunEntity run = placeholder(tenantId, w);

    List<Detected> detected = new ArrayList<>(detector.detectLedger(tenantId, w, run.id, batchLimit));
    SideEffectBatch sideEffects = detector.detectSideEffects(tenantId, w, run.id, batchLimit);
    detected.addAll(sideEffects.detected());
    int resolved = detector.autoResolve(tenantId, w, allowUnconsumedTtl);
    int open = detector.countOpen(tenantId);
    warnIfSaturated(tenantId, sideEffects.checked());

    Map<String, Integer> byKind = byKind(detected);
    Long auditId = null;
    if (!detected.isEmpty() || resolved > 0) {
      auditId = appender.appendInCurrentTransaction(receiptBuilder.reconciliationEvent(tenantId, run.id, w.since(),
          w.cutoff(), detected.size(), resolved, open, byKind,
          detected.stream().map(Detected::orphanId).toList())).auditId();
    }
    run.sideEffectsChecked = sideEffects.checked();
    run.newOrphans = detected.size();
    run.resolvedOrphans = resolved;
    run.openOrphans = open;
    run.newByKind = json(byKind);
    run.auditId = auditId;
    return new Attempt(new TenantRun(tenantId, TenantOutcome.OK, run.id, detected.size(), resolved, open, auditId),
        detected);
  }

  /**
   * 保留期清理（计划 D9）：已对账的上报行（{@code reconciled_at} 早于 side-effect-retention；未对账的无论多旧都不删）、
   * 非 OPEN 孤儿（解除或检出早于 orphan-retention；OPEN 永不删除）、run 行（早于 run-retention）。先删上报行：孤儿行的
   * {@code side_effect_id} 随之置空，{@code detail} 已复制上报关键字段。每批一个独立事务，不与对账争锁。
   */
  public PurgeResult purgeExpired(Instant now) {
    int sideEffects = purge(PURGE_SIDE_EFFECTS, now.minus(sideEffectRetention));
    int orphans = purge(PURGE_ORPHANS, now.minus(orphanRetention));
    int runs = purge(PURGE_RUNS, now.minus(runRetention));
    return new PurgeResult(sideEffects, orphans, runs);
  }

  private int purge(String sql, Instant cutoff) {
    int total = 0;
    int n;
    do {
      n = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(sql).setParameter("cutoff", cutoff)
          .setParameter("batch", PURGE_BATCH).executeUpdate());
      total += n;
    } while (n >= PURGE_BATCH);
    return total;
  }

  /** 先插 run 行取得 id（孤儿行的 first_run_id），计数为 0，末步回填。时刻取本轮逻辑时刻 {@code now}。 */
  private GuardReconciliationRunEntity placeholder(String tenantId, Window w) {
    GuardReconciliationRunEntity run = new GuardReconciliationRunEntity();
    run.tenantId = tenantId;
    run.windowStart = w.since();
    run.windowEnd = w.cutoff();
    run.startedAt = w.now();
    run.finishedAt = w.now();
    run.newByKind = "{}";
    run.persistAndFlush();
    return run;
  }

  private void warnIfSaturated(String tenantId, int sideEffectsChecked) {
    if (sideEffectsChecked >= batchLimit) {
      Log.warnf("Guard 对账上报批次已满（%d 条），剩余留给下一轮: tenant=%s", batchLimit, tenantId);
    }
  }

  private static Map<String, Integer> byKind(List<Detected> detected) {
    Map<String, Integer> m = new TreeMap<>();
    detected.forEach(d -> m.merge(d.kind().wire(), 1, Integer::sum));
    return m;
  }

  private String json(Map<String, Integer> m) {
    try {
      return mapper.writeValueAsString(m);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("newByKind 序列化失败", e);
    }
  }
}
