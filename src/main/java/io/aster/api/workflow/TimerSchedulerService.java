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
     * {@code EXECUTING} 超过这个时长即视为「认领后进程消失」，退回 {@code PENDING}。
     *
     * <p>正常处理是毫秒级（{@code resumeWorkflow} 走异步 submit，不在此等待），
     * 故 5 分钟有极大余量，不会误伤在跑的 timer；同时也不至于让卡住的
     * workflow 等太久。
     */
    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(5);

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
            reclaimStuckTimers();

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
     * 认领后进程消失时，{@code EXECUTING} 会永久卡住 —— 本方法把它们退回
     * {@code PENDING}（issue #308）。
     *
     * <p><b>为什么会卡</b>：{@code pollExpiredTimers} 先原子认领（置 EXECUTING）
     * 再处理。若进程在这两步之间被杀（{@code kill -9}、pod 驱逐、OOM），
     * 既不会走到写终态、也不会走异常分支。而查询只捞 {@code status='PENDING'}，
     * 这行<b>再也不会被任何副本拾取</b>：一次性 timer 的 workflow 永久挂起，
     * 周期性 timer 整条周期链就此断掉。
     *
     * <p>★<b>表面毫无异常</b>：行还在、状态是合法值、没有任何错误日志 ——
     * 这正是它难被发现的原因。
     *
     * <p><b>为什么用 {@code fireAt} 判定陈旧</b>：本表没有 {@code updated_at} 列，
     * 而认领只改 {@code status}、<b>不动 {@code fireAt}</b>，故卡住的行会一直
     * 保留它「本该运行的时刻」，随时间越来越旧：
     * <ul>
     *   <li>一次性：{@code fireAt} 是创建时的到期时刻，此后不再改；</li>
     *   <li>周期性：上一轮成功才会把 {@code fireAt} 推到未来；在本轮认领后崩溃的话，
     *       它仍停在本轮那个已过期的值。</li>
     * </ul>
     * 两种都满足「越卡越旧」，故无需加列。
     *
     * <p>★阈值取 {@link #STUCK_TIMEOUT}：{@code fireAt} 的语义是「该跑的时刻」
     * 而非「认领的时刻」，两者最多差一个轮询周期（1 秒），故 5 分钟对正常处理
     * （毫秒级，且 {@code resumeWorkflow} 是异步 submit）有极大余量，
     * 不会误伤正在跑的 timer。
     */
    private void reclaimStuckTimers() {
        long freed = WorkflowTimerEntity.update(
            "status = 'PENDING' where status = 'EXECUTING' and fireAt < ?1",
            Instant.now().minus(STUCK_TIMEOUT));
        if (freed > 0) {
            // warn 而非 debug：这意味着此前有副本在认领后异常消失，值得被看见
            Log.warnf("回收 %d 个卡在 EXECUTING 的 timer（认领后进程消失），已退回 PENDING", freed);
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
