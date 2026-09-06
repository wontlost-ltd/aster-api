package io.aster.api.workflow;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timer 的<b>跨副本恰好一次</b>触发（issue #302 B1）。
 *
 * <p>★被修的缺陷：{@code pollExpiredTimers} 原本是
 * 「{@code find(status='PENDING')} → 改 {@code status='EXECUTING'} → persist」，
 * 注释称之为「乐观锁」，但 {@link WorkflowTimerEntity} <b>没有 {@code @Version}</b>，
 * 表里也没有版本列——根本没有任何锁。
 *
 * <p>{@code aster-api} 跑 <b>4 副本</b>，四个调度器每秒各查一次，
 * 都能查到同一批 PENDING 行，于是同一个 timer 被触发多次，
 * workflow step 随之重复执行。
 *
 * <p>★<b>判据是「workflow 被恢复了几次」</b>，用 spy 直接计数——
 * 而不是看 timer 的终态。终态区分不了「被触发 1 次」和「被触发 8 次」：
 * 两种情况最后都是 COMPLETED。
 */
@QuarkusTest
@TestProfile(TimerClaimConcurrencyIT.NoBackgroundSchedulerProfile.class)
class TimerClaimConcurrencyIT {

    /**
     * ★必须关掉后台调度器：{@code @Scheduled(1s)} 的轮询线程会与本用例的
     * 并发线程抢同一批 timer，让「触发了几次」变成时序赌博。
     * 本仓 TimerCrashRecoveryIT 也记录过同一个坑（后台线程下 spy 约 50% 被绕过）。
     */
    public static class NoBackgroundSchedulerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.scheduler.enabled", "false",
                "aster.scheduler.background.enabled", "false",
                "workflow.scheduler.polling.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Inject
    TimerSchedulerService timerScheduler;

    @InjectSpy
    WorkflowSchedulerService workflowScheduler;

    /** workflowId → 被恢复的次数。这是「触发了几次」的直接证据。 */
    private final Map<String, AtomicInteger> resumeCounts = new ConcurrentHashMap<>();

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowTimerEntity.deleteAll();
            WorkflowStateEntity.deleteAll();
        });
        resumeCounts.clear();
    }

    /** 让 spy 记录每次恢复调用，且**不真的**去跑 workflow（本用例只关心触发次数）。 */
    private void countResumesInsteadOfRunning() {
        Mockito.doAnswer(inv -> {
            resumeCounts.computeIfAbsent(inv.getArgument(0), k -> new AtomicInteger())
                .incrementAndGet();
            return null;
        }).when(workflowScheduler).resumeWorkflowStep(Mockito.anyString(), Mockito.anyString());

        Mockito.doAnswer(inv -> {
            resumeCounts.computeIfAbsent(inv.getArgument(0), k -> new AtomicInteger())
                .incrementAndGet();
            return null;
        }).when(workflowScheduler).resumeWorkflow(Mockito.anyString());
    }

    /**
     * ★8 个线程（模拟多副本）<b>同时调用真实的
     * {@link TimerSchedulerService#pollExpiredTimers()}</b>，
     * 每个到期 timer 只能触发一次 workflow 恢复。
     *
     * <p>缺陷版（先查后写、无条件谓词）下八个线程都会查到同一批 PENDING 行、
     * 都判定「该我处理」，于是每个 timer 被恢复多达 8 次。
     */
    @Test
    @DisplayName("多副本并发轮询，每个到期 timer 只触发一次 workflow 恢复")
    void concurrentPollersFireEachTimerExactlyOnce() throws Exception {
        countResumesInsteadOfRunning();

        int timerCount = 20;
        List<UUID> workflowIds = new java.util.ArrayList<>();
        for (int i = 0; i < timerCount; i++) {
            workflowIds.add(UUID.randomUUID());
        }

        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID wf : workflowIds) {
                WorkflowStateEntity state = new WorkflowStateEntity();
                state.workflowId = wf;
                state.status = "PAUSED";
                state.snapshot = "{}";
                state.createdAt = Instant.now();
                state.persist();

                WorkflowTimerEntity t = new WorkflowTimerEntity();
                t.timerId = UUID.randomUUID();
                t.workflowId = wf;
                t.stepId = "step1";
                t.fireAt = Instant.now().minusSeconds(1);
                t.status = "PENDING";
                t.payload = "{}";
                t.persist();
            }
        });

        int replicas = 8;
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        try {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < replicas; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    try {
                        timerScheduler.pollExpiredTimers();   // ★真实生产方法
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get())
            .as("★并发轮询不得抛异常——抛了说明认领路径本身有竞态")
            .isZero();

        assertThat(resumeCounts.keySet())
            .as("★每个 timer 都必须被触发过——漏掉说明有 timer 无人处理")
            .hasSize(timerCount);

        // ★核心断言：逐个 workflow 检查恢复次数
        for (Map.Entry<String, AtomicInteger> e : resumeCounts.entrySet()) {
            assertThat(e.getValue().get())
                .as("★workflow %s 被恢复 %d 次——必须恰好 1 次。"
                        + "多于 1 说明多个副本都触发了同一个 timer，workflow step 重复执行",
                    e.getKey(), e.getValue().get())
                .isEqualTo(1);
        }
    }

    /**
     * 一次性 timer 消费后必须落终态，不得回到 PENDING 被反复触发。
     *
     * <p>与上一条互补：上一条数「触发次数」，这条守「消费后不再可拾取」。
     */
    @Test
    @DisplayName("一次性 timer 消费后落终态，重复轮询不再触发")
    void oneTimeTimerNotRefiredOnSubsequentPolls() {
        countResumesInsteadOfRunning();

        UUID workflowId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = workflowId;
            state.status = "PAUSED";
            state.snapshot = "{}";
            state.createdAt = Instant.now();
            state.persist();

            WorkflowTimerEntity t = new WorkflowTimerEntity();
            t.timerId = timerId;
            t.workflowId = workflowId;
            t.stepId = null;              // 走 resumeWorkflow 分支
            t.fireAt = Instant.now().minusSeconds(1);
            t.status = "PENDING";
            t.payload = "{}";
            t.persist();
        });

        timerScheduler.pollExpiredTimers();
        timerScheduler.pollExpiredTimers();
        timerScheduler.pollExpiredTimers();

        AtomicInteger n = resumeCounts.get(workflowId.toString());
        assertThat(n)
            .as("★timer 应被触发过")
            .isNotNull();
        assertThat(n.get())
            .as("★连轮询 3 次，一次性 timer 只能触发 1 次；"
                + "多于 1 说明消费后又回到了可拾取状态")
            .isEqualTo(1);

        String status = QuarkusTransaction.requiringNew().call(() ->
            (String) em.createNativeQuery(
                    "SELECT status FROM workflow_timers WHERE timer_id = ?1")
                .setParameter(1, timerId).getSingleResult());
        assertThat(status)
            .as("★一次性 timer 消费后必须是终态，不得留在 PENDING")
            .isNotEqualTo("PENDING");
    }

    // ── issue #308：认领后进程消失，EXECUTING 不得永久卡住 ──────────────

    /**
     * ★卡在 {@code EXECUTING} 的 timer 必须被回收并<b>真正重新触发 workflow</b>。
     *
     * <p>{@code pollExpiredTimers} 先原子认领（置 EXECUTING）再处理。
     * 若进程在这两步之间被杀（{@code kill -9} / pod 驱逐 / OOM），
     * 既不写终态也不走异常分支；而查询只捞 {@code PENDING}，
     * 这行<b>再也不会被任何副本拾取</b> —— 一次性 timer 的 workflow 永久挂起。
     *
     * <p>★<b>判据是「workflow 有没有被再次恢复」</b>，不是「状态变回 PENDING」。
     * 只断言状态的话，一个「把 EXECUTING 改成 PENDING 就完事」的实现也能变绿，
     * 却可能因为别处的条件（比如 fireAt 已被改到未来）而永远轮不到它跑。
     */
    @Test
    @DisplayName("卡在 EXECUTING 的 timer 被回收后必须真正重新触发 workflow")
    void stuckExecutingTimerIsReclaimedAndRefired() {
        countResumesInsteadOfRunning();

        UUID workflowId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = workflowId;
            state.status = "PAUSED";
            state.snapshot = "{}";
            state.createdAt = Instant.now();
            state.persist();

            WorkflowTimerEntity t = new WorkflowTimerEntity();
            t.timerId = timerId;
            t.workflowId = workflowId;
            t.stepId = null;
            // 模拟「认领后进程被杀」：状态停在 EXECUTING，fireAt 是很久以前
            t.fireAt = Instant.now().minusSeconds(3600);
            t.status = "EXECUTING";
            t.payload = "{}";
            t.persist();
        });

        timerScheduler.pollExpiredTimers();

        AtomicInteger n = resumeCounts.get(workflowId.toString());
        assertThat(n)
            .as("★卡住的 timer 必须被回收并**重新触发 workflow**——"
                + "拿不到计数说明它仍然无人拾取，对应 workflow 永久挂起（issue #308）")
            .isNotNull();
        assertThat(n.get())
            .as("★回收后应恰好触发 1 次")
            .isEqualTo(1);
    }

    /**
     * ★反向守卫：<b>刚认领、还在跑</b>的 timer 不得被回收。
     *
     * <p>没有这条，「无条件把所有 EXECUTING 退回 PENDING」也能让上一条变绿，
     * 却会把正在处理中的 timer 抢回来重复触发 —— 那正是 #302 B1 修掉的问题，
     * 等于用一个回收机制把它重新引入。
     */
    @Test
    @DisplayName("刚认领仍在处理中的 timer 不得被回收")
    void freshlyClaimedTimerIsNotReclaimed() {
        countResumesInsteadOfRunning();

        UUID workflowId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = workflowId;
            state.status = "PAUSED";
            state.snapshot = "{}";
            state.createdAt = Instant.now();
            state.persist();

            WorkflowTimerEntity t = new WorkflowTimerEntity();
            t.timerId = timerId;
            t.workflowId = workflowId;
            t.stepId = null;
            // 刚刚才到期并被认领（远未到 5 分钟阈值）
            t.fireAt = Instant.now().minusSeconds(1);
            t.status = "EXECUTING";
            t.payload = "{}";
            t.persist();
        });

        timerScheduler.pollExpiredTimers();

        assertThat(resumeCounts.get(workflowId.toString()))
            .as("★仍在处理中的 timer 不得被抢回来重跑——"
                + "无条件回收会把 #302 B1 的重复触发问题重新引入")
            .isNull();

        String status = QuarkusTransaction.requiringNew().call(() ->
            (String) em.createNativeQuery(
                    "SELECT status FROM workflow_timers WHERE timer_id = ?1")
                .setParameter(1, timerId).getSingleResult());
        assertThat(status)
            .as("★未超时的 EXECUTING 应保持不动")
            .isEqualTo("EXECUTING");
    }
}
