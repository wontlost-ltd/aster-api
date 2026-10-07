package io.aster.guard;

import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

/**
 * 待审批过期调度（ADR 0040 §4.3）：默认每分钟把已到期的 PENDING 标为 EXPIRED 并写审批事件。
 *
 * <p>读取路径已自行判定过期（{@link GuardDecisionService#get}），本调度只负责把事实落库、入链。
 * 不加 {@code @Transactional}：{@link GuardApprovalService#expireDue} 内部每步自开事务，
 * 审计追加不能在外层事务里执行。
 */
@ApplicationScoped
public class GuardApprovalExpiryScheduler {

  @Inject
  GuardApprovalService service;

  @Scheduled(
      cron = "${aster.guard.approval-expiry-cron:0 * * * * ?}",
      identity = "guard-approval-expiry",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  void expire() {
    int n = service.expireDue(Instant.now());
    if (n > 0) {
      Log.infof("Action Guard 待审批过期 %d 条", n);
    }
  }
}
