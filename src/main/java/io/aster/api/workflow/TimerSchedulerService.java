package io.aster.api.workflow;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Timer 调度服务
 *
 * 负责轮询到期的 timers 并触发 workflow 恢复执行。
 * 支持一次性 timer 和周期性 timer。
 */
@ApplicationScoped
public class TimerSchedulerService {

    @Inject
    WorkflowSchedulerService workflowScheduler;

    /**
     * 每秒轮询到期的 timers。
     *
     * <p>★<b>多副本下必须逐个原子认领</b>（issue #302 B1）：此前是
     * 「{@code find(status='PENDING')} → 改 {@code status='EXECUTING'} → persist」
     * 的先查后写，注释称其为「乐观锁」，但
     * {@link WorkflowTimerEntity} <b>没有 {@code @Version} 字段</b>，
     * 表里也没有版本列 —— 根本没有任何锁。
     * {@code aster-api} 跑 4 副本，四个调度器每秒各查一次，
     * 都能查到同一批 PENDING 行，于是<b>同一个 timer 被触发 4 次</b>，
     * workflow step 也就重复执行 4 次。
     *
     * <p>改为条件更新 {@code ... SET status='EXECUTING' WHERE timerId=? AND
     * status='PENDING'}：由数据库在行级串行化，抢同一行时只有一个副本
     * 的 UPDATE 会命中（返回 1），其余返回 0 直接跳过。
     */
    @Scheduled(every = "1s")
    @Transactional
    public void pollExpiredTimers() {
        try {
            List<WorkflowTimerEntity> expired = WorkflowTimerEntity.find(
                "fireAt <= ?1 AND status = 'PENDING' ORDER BY fireAt",
                Instant.now()
            ).page(0, 100).list(); // 每次最多处理 100 个 timer

            int claimed = 0;
            for (WorkflowTimerEntity timer : expired) {
                // ★认领与执行必须是「先原子占坑、再干活」：查询结果只是候选，
                //   真正的准入是下面这条 UPDATE 的命中行数。
                long won = WorkflowTimerEntity.update(
                    "status = 'EXECUTING' where timerId = ?1 and status = 'PENDING'",
                    timer.timerId);
                if (won == 0) {
                    // 别的副本已经领走——本副本不得再碰，否则就是重复执行
                    continue;
                }
                claimed++;
                processExpiredTimer(timer);
            }

            if (claimed > 0) {
                Log.debugf("Processed %d expired timers (候选 %d)", claimed, expired.size());
            }

        } catch (Exception e) {
            Log.errorf(e, "Error polling expired timers");
        }
    }

    /**
     * 处理单个已<b>认领</b>的到期 timer。
     *
     * <p>调用前 {@code status} 已由 {@link #pollExpiredTimers} 的条件更新
     * 原子地置为 {@code EXECUTING}，故这里不再重复置位。
     *
     * @param timer 已认领的 timer 实体
     */
    private void processExpiredTimer(WorkflowTimerEntity timer) {
        try {
            // 认领已在调用方完成（条件 UPDATE 命中）；同步内存态以免后续 persist 写回旧值
            timer.status = "EXECUTING";

            // 触发 workflow step 继续执行
            if (timer.stepId != null) {
                workflowScheduler.resumeWorkflowStep(timer.workflowId.toString(), timer.stepId);
            } else {
                // 如果没有指定 stepId，触发整个 workflow 恢复
                workflowScheduler.resumeWorkflow(timer.workflowId.toString());
            }

            // 处理周期性 timer
            if (timer.intervalMillis != null && timer.intervalMillis > 0) {
                // 计算下次执行时间（避免时钟漂移）
                timer.fireAt = Instant.now().plusMillis(timer.intervalMillis);
                timer.status = "PENDING";
                timer.retryCount = 0; // 重置重试计数
                timer.persist();

                Log.debugf("Rescheduled periodic timer %s for workflow %s (next fire: %s)",
                    timer.timerId, timer.workflowId, timer.fireAt);
            } else {
                // 一次性 timer：标记为已完成
                timer.status = "COMPLETED";
                timer.persist();

                Log.debugf("Completed one-time timer %s for workflow %s",
                    timer.timerId, timer.workflowId);
            }

        } catch (Exception e) {
            // 执行失败：标记为 FAILED 并递增重试计数
            timer.status = "FAILED";
            timer.retryCount++;
            timer.persist();

            Log.errorf(e, "Failed to execute timer %s for workflow %s (retry count: %d)",
                timer.timerId, timer.workflowId, timer.retryCount);

            // 可选：实现指数退避重试策略
            // if (timer.retryCount < maxRetries) {
            //     timer.fireAt = Instant.now().plus(Duration.ofSeconds(1L << timer.retryCount));
            //     timer.status = "PENDING";
            //     timer.persist();
            // }
        }
    }

    /**
     * 创建一次性 timer
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId     step 标识符（可选）
     * @param delay      延迟时间
     * @param payload    timer 负载数据
     * @return 创建的 timer 实体
     */
    @Transactional
    public WorkflowTimerEntity scheduleTimer(
        String workflowId,
        String stepId,
        Duration delay,
        String payload
    ) {
        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = java.util.UUID.randomUUID();
        timer.workflowId = java.util.UUID.fromString(workflowId);
        timer.stepId = stepId;
        timer.fireAt = Instant.now().plus(delay);
        timer.payload = payload;
        timer.status = "PENDING";
        timer.persist();

        Log.debugf("Scheduled timer %s for workflow %s (fire at: %s)",
            timer.timerId, workflowId, timer.fireAt);

        return timer;
    }

    /**
     * 创建周期性 timer
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId     step 标识符（可选）
     * @param interval   执行间隔
     * @param payload    timer 负载数据
     * @return 创建的 timer 实体
     */
    @Transactional
    public WorkflowTimerEntity schedulePeriodicTimer(
        String workflowId,
        String stepId,
        Duration interval,
        String payload
    ) {
        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = java.util.UUID.randomUUID();
        timer.workflowId = java.util.UUID.fromString(workflowId);
        timer.stepId = stepId;
        timer.fireAt = Instant.now().plus(interval);
        timer.intervalMillis = interval.toMillis();
        timer.payload = payload;
        timer.status = "PENDING";
        timer.persist();

        Log.debugf("Scheduled periodic timer %s for workflow %s (interval: %s)",
            timer.timerId, workflowId, interval);

        return timer;
    }

    /**
     * 取消 timer
     *
     * @param timerId timer 唯一标识符
     * @return 是否成功取消
     */
    @Transactional
    public boolean cancelTimer(java.util.UUID timerId) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer != null && "PENDING".equals(timer.status)) {
            timer.status = "CANCELLED";
            timer.persist();
            Log.debugf("Cancelled timer %s", timerId);
            return true;
        }
        return false;
    }
}
