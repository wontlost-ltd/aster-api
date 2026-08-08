package io.aster.policy.replay.batch;

import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * What-If 批次调度器（ADR 0034 S2）。
 *
 * <p><b>为什么是轮询而不是创建时直接派发</b>：轮询是**单一机制**——
 * 进程重启后在途批次由下一轮自然接管，不需要「派发 + 轮询兜底」两套代码。
 * 代价是最多一个轮询周期的启动延迟（默认 2 秒），
 * 相对于一个跑几十秒的批次可以忽略。
 *
 * <p>{@code concurrentExecution = SKIP}：上一轮没跑完就跳过本轮，
 * 避免同一批次被两个调度线程同时领取。真正的互斥仍靠数据库条件更新
 * （{@code WHERE status = 'PENDING'}）保证——多副本部署时调度器会并行跑。
 */
@ApplicationScoped
public class ReplayBatchScheduler {

    @Inject
    ReplayBatchService service;

    /**
     * 领取并执行待跑批次。
     *
     * <p>每轮只领**一个**批次：批次内部是逐条串行重跑，本身已经吃满一个
     * PermitLease 许可；一次领多个会让单租户的并发上限（ADR 0034 §7.2）形同虚设。
     */
    @Scheduled(
        every = "${aster.whatif.batch.poll-interval:2s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    public void pollAndRun() {
        java.util.UUID batchId = service.claimNextPending();
        if (batchId == null) {
            return;
        }
        Log.infof("领取 What-If 批次 %s，开始执行", batchId);
        try {
            service.runBatch(batchId);
        } catch (RuntimeException e) {
            // ★不让异常逃出调度器：一个批次炸掉不应停掉整个轮询。
            //   批次自身的状态由 runBatch 内部保证已落到 FAILED——
            //   若连那都失败，下面的兜底会把它标记掉，避免永久卡在 RUNNING。
            Log.errorf(e, "What-If 批次 %s 执行异常", batchId);
            service.failBatchDefensively(batchId, ReplayFailureKind.UNKNOWN);
        }
    }

    /**
     * 过期清理（ADR 0034 §7.3）：30 天后转 EXPIRED 并**清空聚合结果**。
     *
     * <p>★走定时任务而非读路径惰性删除——读操作不该有写副作用。
     */
    @Scheduled(
        cron = "${aster.whatif.batch.expire-cron:0 30 3 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    @Transactional
    public void expireOldBatches() {
        List<ReplayBatchEntity> due = ReplayBatchEntity
            .<ReplayBatchEntity>list(
                "status in ?1 and expiresAt < ?2",
                List.of(ReplayBatchStatus.COMPLETED, ReplayBatchStatus.FAILED),
                Instant.now());
        if (due.isEmpty()) {
            return;
        }
        for (ReplayBatchEntity b : due) {
            // 保留元数据、清空数字——数字有时效性，留着陈旧的比删掉更危险
            b.markExpired();
            b.persist();
        }
        Log.infof("What-If 批次过期清理：%d 条转 EXPIRED（元数据保留，聚合结果已清空）", due.size());
    }
}
