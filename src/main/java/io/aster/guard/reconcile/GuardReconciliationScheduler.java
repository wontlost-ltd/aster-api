package io.aster.guard.reconcile;

import io.aster.guard.reconcile.GuardReconciliationService.TenantRun;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.List;

/**
 * Guard 对账调度（ADR 0054 §10）：每 5 分钟对账，每日一次保留期清理。每个副本都跑：同一租户由对账服务的 advisory 锁与 min-interval 保证只处理一次。
 * 不加 {@code @Transactional}：服务按租户自开事务，审计追加不能在外层事务里执行。
 */
@ApplicationScoped
public class GuardReconciliationScheduler {

  @Inject
  GuardReconciliationService service;

  @Inject
  Clock clock;

  @Scheduled(
      cron = "${aster.guard.reconciliation.cron}",
      identity = "guard-reconciliation",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  void reconcile() {
    List<TenantRun> runs = service.reconcileAll(clock.instant());
    for (TenantRun run : runs) {
      if (run.newOrphans() > 0) {
        Log.warnf("Guard 对账检出新孤儿动作 %d 条（OPEN %d）: tenant=%s runId=%d", run.newOrphans(), run.open(),
            run.tenantId(), run.runId());
      }
    }
  }

  @Scheduled(
      cron = "${aster.guard.reconciliation.retention-cron}",
      identity = "guard-reconciliation-retention",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  void retention() {
    GuardReconciliationService.PurgeResult r = service.purgeExpired(clock.instant());
    if (r.any()) {
      Log.infof("Guard 对账保留期清理：上报 %d 行、孤儿 %d 行、run %d 行", r.sideEffects(), r.orphans(), r.runs());
    }
  }
}
