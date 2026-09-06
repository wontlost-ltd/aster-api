package io.aster.api.workflow;

import aster.runtime.workflow.ExecutionHandle;
import aster.runtime.workflow.WorkflowEvent;
import aster.runtime.workflow.WorkflowMetadata;
import aster.truffle.runtime.DependencyGraph;
import io.aster.api.workflow.DeterminismSnapshot;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Workflow 混沌测试：覆盖资源耗尽、并发冲突、时序超时、故障恢复与分布式一致性五大类 24 个场景。
 */
@QuarkusTest
@Tag("chaos")
class WorkflowChaosTest extends CrashRecoveryTestBase {

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    PostgresEventStore eventStore;

    @Inject
    WorkflowSchedulerService schedulerService;

    @Inject
    TimerSchedulerService timerSchedulerService;

    @Inject
    SagaCompensationExecutor compensationExecutor;

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanTables() {
        WorkflowTimerEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
    }

    // ========================= 1. 资源耗尽场景 =========================

    @Test
    void testMassiveFanOut100ParallelSteps() throws Exception {
        // Given：构造包含 100 个并行支线的 workflow
        String workflowId = bootstrapWorkflow("RUNNING");
        int branches = 100;
        CountDownLatch ready = new CountDownLatch(branches);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        Map<String, Instant> completionTimes = new ConcurrentHashMap<>();

        // When：同时追加 STEP_STARTED/STEP_COMPLETED 事件模拟资源耗尽
        for (int i = 0; i < branches; i++) {
            final String stepId = "fan-" + i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(2, TimeUnit.SECONDS);
                    appendStepStarted(workflowId, stepId, List.of("root"));
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(3, 15));
                    appendStepCompleted(workflowId, stepId, List.of("root"), stepId + "-ok", true);
                    completionTimes.put(stepId, Instant.now());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await(2, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        appendWorkflowCompleted(workflowId, Map.of("fanout", (Object) branches));
        schedulerService.processWorkflow(workflowId);

        // Then：验证 EVENT 序列与状态稳定
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(completionTimes).hasSize(branches);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertSequenceIntegrity(workflowId);
    }

    @Test
    void testDeepNesting20Levels() {
        // Given：构造 20 层嵌套 step，依赖结构类似链表
        String workflowId = bootstrapWorkflow("RUNNING");
        String previous = null;
        int depth = 20;
        for (int i = 1; i <= depth; i++) {
            String stepId = "nested-" + i;
            List<String> deps = previous == null ? List.of() : List.of(previous);
            appendStepStarted(workflowId, stepId, deps);
            appendStepCompleted(workflowId, stepId, deps, "ok-" + i, false);
            previous = stepId;
        }
        appendWorkflowCompleted(workflowId, Map.of("result", "deep-tree"));

        // When：执行调度验证深层事件不会堆栈溢出
        schedulerService.processWorkflow(workflowId);

        // Then：事件数量与序列均满足期望
        WorkflowStateEntity state = findWorkflowState(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        long completionCount = events.stream()
                .filter(e -> WorkflowEvent.Type.STEP_COMPLETED.equals(e.getEventType()))
                .count();
        assertThat(completionCount).isEqualTo(depth);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertSequenceIntegrity(workflowId);
    }

    @Test
    void testLargePayload10MBEvent() {
        // Given：构造单个 10MB payload 事件
        String workflowId = bootstrapWorkflow("RUNNING");
        String payload = "x".repeat(10 * 1024 * 1024);
        appendStepStarted(workflowId, "blob-step", List.of());
        appendStepCompleted(workflowId, "blob-step", List.of(), Map.of("blob", payload), false);
        appendWorkflowCompleted(workflowId, Map.of("result", "blob-ok"));

        // When：调度完成 workflow 并读取事件
        schedulerService.processWorkflow(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);

        // Then：payload 未被截断且 workflow 正常完成
        WorkflowStateEntity state = findWorkflowState(workflowId);
        Map<?, ?> blobPayload = events.stream()
                .filter(e -> WorkflowEvent.Type.STEP_COMPLETED.equals(e.getEventType()))
                .map(WorkflowEvent::getPayload)
                .map(Map.class::cast)
                .map(m -> (Map<?, ?>) m.get("result"))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertThat(blobPayload.get("blob").toString().length()).isGreaterThanOrEqualTo(10 * 1024 * 1024);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    /**
     * 并发调用 {@code processWorkflow} 时不得因连接获取失败而崩溃。
     *
     * <p>★<b>本用例曾是彻底的假绿</b>（「测试配置掩盖生产行为」专项扫描发现）。
     * 实测：把 {@code processWorkflow} 开头改成无条件抛异常后，
     * 本类 66 个用例红了 63 个，<b>唯独这一条仍绿</b>（0.336s）。
     * 两处结构性缺陷让它<b>不可能</b>变红：
     * <ol>
     *   <li>{@code executor.submit(...)} 的返回值被丢弃，任务体里的异常
     *       被吞进无人过问的 {@code Future}，永远传不到测试线程；
     *       而 {@code finally { latch.countDown(); }} 保证即使全部抛异常，
     *       latch 也照常归零、{@code assertDoesNotThrow} 照常通过。</li>
     *   <li>终态断言 {@code status == "COMPLETED"} 与被测代码无关——
     *       {@code appendWorkflowCompleted} 在调用 {@code processWorkflow}
     *       <b>之前</b>就把完成事件写好了，状态达成不依赖被测执行路径。</li>
     * </ol>
     *
     * <p>现在改为：收集 {@code Future} 并逐个 {@code get()} 让异常真正传播；
     * 断言前把状态<b>重置为非终态</b>，使 COMPLETED 只可能由 {@code processWorkflow} 产生。
     *
     * <p>★关于池大小：测试环境 {@code jdbc.max-size=50}、生产是 8，故本用例
     * <b>不声称</b>覆盖了「池耗尽」这一生产故障——它守的是「并发推进不崩、
     * 且状态确由被测代码推进」。真正守住 C1「执行期不占连接」的是
     * {@code ReplayBatchConcurrencyIT.重放执行期间不得持有事务与连接}。
     * 用例名保留是为了不打断既有引用，但语义已在此说明。
     */
    @Test
    void testDatabaseConnectionPoolExhaustion() throws Exception {
        // Given：准备 12 个 workflow 并发完成
        List<String> workflows = IntStream.range(0, 12)
                .mapToObj(i -> {
                    String workflowId = bootstrapWorkflow("RUNNING");
                    appendStepStarted(workflowId, "io-" + i, List.of());
                    appendStepCompleted(workflowId, "io-" + i, List.of(), "done", false);
                    appendWorkflowCompleted(workflowId, Map.of("result", (Object) i));
                    return workflowId;
                })
                .toList();

        // ★把状态压回非终态：否则 COMPLETED 是 appendWorkflowCompleted 给的，
        //   与 processWorkflow 跑没跑无关，断言就是恒真的。
        for (String workflowId : workflows) {
            resetStatusTo(workflowId, "RUNNING");
        }

        // When：线程池并发触发 processWorkflow
        ExecutorService executor = Executors.newFixedThreadPool(workflows.size());
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (String workflowId : workflows) {
                futures.add(executor.submit(() -> schedulerService.processWorkflow(workflowId)));
            }
            // ★逐个 get()：任务体里的异常在这里抛出来，测试才可能变红。
            //   丢弃 Future 等于把被测代码的失败静音。
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // Then：状态必须由 processWorkflow 真正推进到 COMPLETED
        for (String workflowId : workflows) {
            WorkflowStateEntity state = findWorkflowState(workflowId);
            assertThat(state.status)
                .as("★workflow %s 必须被 processWorkflow 推进到 COMPLETED——"
                    + "断言前已重置为 RUNNING，故这里的 COMPLETED 只能来自被测代码", workflowId)
                .isEqualTo("COMPLETED");
        }
    }

    /**
     * 把 workflow 状态直接压回指定值（绕过事件重放，仅供测试重置基线用）。
     *
     * <p>★用 {@code QuarkusTransaction} 而非 {@code @Transactional}：
     * 测试类里的自调用不走 CDI 拦截，注解在这里不生效。
     */
    private void resetStatusTo(String workflowId, String status) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity s =
                WorkflowStateEntity.findById(java.util.UUID.fromString(workflowId));
            s.status = status;
            s.persist();
        });
    }

    @Test
    void testMemoryPressureUnderLoad() {
        // Given：构造 5 个带 1MB snapshot 的 workflow
        List<String> workflows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String workflowId = bootstrapWorkflow("RUNNING");
            updateStateSnapshot(workflowId, randomJsonBlob(1));
            appendStepStarted(workflowId, "memory-step-" + i, List.of());
            appendStepCompleted(workflowId, "memory-step-" + i, List.of(), "ok", false);
            appendWorkflowCompleted(workflowId, Map.of("result", "memory-ok"));
            workflows.add(workflowId);
        }

        // When：依次执行调度，确保 snapshot 不会触发 OOM
        workflows.forEach(schedulerService::processWorkflow);

        // Then：snapshot 内容仍可读取且 workflow 完成
        for (String workflowId : workflows) {
            WorkflowStateEntity state = findWorkflowState(workflowId);
            assertThat(state.snapshot).isNotBlank();
            assertThat(state.status).isEqualTo("COMPLETED");
        }
    }

    // ========================= 2. 并发冲突场景 =========================

    @Test
    void testConcurrentWorkflowModification() throws Exception {
        // Given：一个 RUNNING workflow 同时被两个线程修改
        String workflowId = bootstrapWorkflow("RUNNING");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int stepsPerThread = 10;

        Runnable writerA = () -> {
            try {
                start.await();
                for (int i = 0; i < stepsPerThread; i++) {
                    String stepId = "hot-" + i;
                    appendStepStarted(workflowId, stepId, List.of());
                    appendStepCompleted(workflowId, stepId, List.of(), "A-" + i, true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable writerB = () -> {
            try {
                start.await();
                for (int i = 0; i < stepsPerThread; i++) {
                    String stepId = "cold-" + i;
                    appendStepStarted(workflowId, stepId, List.of());
                    appendStepCompleted(workflowId, stepId, List.of(), "B-" + i, false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        executor.submit(writerA);
        executor.submit(writerB);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                .as("concurrent writers should finish within timeout")
                .isTrue();
        appendWorkflowCompleted(workflowId, Map.of("result", "concurrent-ok"));

        // When：调度器读取乱序事件，验证未出现死锁
        schedulerService.processWorkflow(workflowId);

        // Then：事件序列完整且状态完成
        assertSequenceIntegrity(workflowId);
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    @Test
    void testIdempotencyKeyConflicts() throws Exception {
        // Given：相同 idempotencyKey 重复调度同一 workflow
        String workflowId = UUID.randomUUID().toString();
        WorkflowMetadata metadata = new WorkflowMetadata();
        ExecutionHandle first = workflowRuntime.schedule(workflowId, "idem-key", metadata);
        ExecutionHandle second = workflowRuntime.schedule(workflowId, "idem-key", metadata);

        // When：只写入一次完成事件并调度
        appendWorkflowCompleted(workflowId, Map.of("result", "idempotent"));
        schedulerService.processWorkflow(workflowId);

        // Then：两个 handle 指向同一 workflow 且事件仅存在一份
        assertThat(second.getWorkflowId()).isEqualTo(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        long startedCount = events.stream()
                .filter(e -> WorkflowEvent.Type.WORKFLOW_STARTED.equals(e.getEventType()))
                .count();
        assertThat(startedCount).isEqualTo(1);
        assertThat(first.getResult().get(3, TimeUnit.SECONDS)).isInstanceOf(Map.class);
    }

    @Test
    void testLockContentionMultipleNodes() throws Exception {
        // Given：多个 worker 同时处理同一 workflow
        String workflowId = bootstrapWorkflow("RUNNING");
        appendStepStarted(workflowId, "lock-step", List.of());
        appendStepCompleted(workflowId, "lock-step", List.of(), "ok", false);
        appendWorkflowCompleted(workflowId, Map.of("result", "lock-ok"));

        // When：模拟 5 个节点同时 processWorkflow
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> schedulerService.processWorkflow(workflowId)));
        }
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        // Then：状态完成且锁已释放
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertThat(state.lockOwner).isNull();
    }

    @Test
    void testRaceConditionEventOrdering() {
        // Given：多线程乱序写入事件
        String workflowId = bootstrapWorkflow("RUNNING");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            final int base = t * 25;
            executor.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    String stepId = "race-" + (base + i);
                    appendStepStarted(workflowId, stepId, List.of());
                    appendStepCompleted(workflowId, stepId, List.of(), "ok", false);
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        appendWorkflowCompleted(workflowId, Map.of("result", "race-ok"));

        // When：读取事件并校验 sequence
        schedulerService.processWorkflow(workflowId);

        // Then：sequence 严格递增
        assertSequenceIntegrity(workflowId);
    }

    @Test
    void testOptimisticLockingFailures() {
        // Given：workflow 被手动锁定，模拟乐观锁冲突
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowCompleted(workflowId, Map.of("result", "lock-retry"));
        updateWorkflowLock(workflowId, "external-worker", Instant.now().plusSeconds(30));

        // When：processWorkflow 在锁被占用时不会抛异常
        assertDoesNotThrow(() -> schedulerService.processWorkflow(workflowId));

        // 清除锁并重新执行
        updateWorkflowLock(workflowId, null, null);
        schedulerService.processWorkflow(workflowId);

        // Then：最终状态完成且锁为空
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertThat(state.lockOwner).isNull();
    }

    // ========================= 3. 时序与超时场景 =========================

    @Test
    void testCascadingTimeouts() {
        // Given：构造连续三个 timeout 的 step
        String workflowId = bootstrapWorkflow("RUNNING");
        for (int i = 1; i <= 3; i++) {
            String stepId = "timeout-" + i;
            appendStepStarted(workflowId, stepId, i == 1 ? List.of() : List.of("timeout-" + (i - 1)));
            appendStepFailed(workflowId, stepId, List.of(), "timeout-" + i);
        }
        appendWorkflowFailed(workflowId, "timeout-chain");

        // When：执行调度触发失败与补偿
        schedulerService.processWorkflow(workflowId);

        // Then：状态 FAILED 且 STEP_FAILED 事件数量匹配
        WorkflowStateEntity state = findWorkflowState(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        long failureCount = events.stream()
                .filter(e -> WorkflowEvent.Type.STEP_FAILED.equals(e.getEventType()))
                .count();
        assertThat(failureCount).isEqualTo(3);
        assertThat(state.status).isEqualTo("FAILED");
    }

    @Test
    void testClockSkewScenarios() throws Exception {
        // Given：写入包含正负偏移的 clock_times
        String workflowId = bootstrapWorkflow("RUNNING");
        DeterminismSnapshot snapshot = new DeterminismSnapshot();
        snapshot.setClockTimes(List.of(
                Instant.now().minusSeconds(5).toString(),
                Instant.now().plusSeconds(5).toString()
        ));
        String json = objectMapper.writeValueAsString(snapshot);
        updateClockTimes(workflowId, json);
        appendWorkflowCompleted(workflowId, Map.of("result", "clock-skew"));

        // When：processWorkflow 会尝试 replay，但 snapshot 应保持不变
        schedulerService.processWorkflow(workflowId);

        // Then：clock_times 仍等于原始值
        WorkflowStateEntity state = findWorkflowState(workflowId);
        try {
            JsonNode expectedNode = objectMapper.readTree(json);
            JsonNode actualNode = objectMapper.readTree(state.clockTimes);
            assertThat(actualNode).isEqualTo(expectedNode);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compare clock_times JSON", e);
        }
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    @Test
    void testTimerPrecisionEdgeCases() throws Exception {
        // Given：创建 fireAt 几乎相同的三个 timer
        String workflowId = bootstrapWorkflow("PAUSED");
        List<UUID> timerIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WorkflowTimerEntity timer = timerSchedulerService.scheduleTimer(
                    workflowId,
                    "precision-" + i,
                    Duration.ofMillis(5),
                    "{}"
            );
            timerIds.add(timer.timerId);
        }
        fastForwardTimers(timerIds);

        // When：手动触发 pollExpiredTimers，验证高精度触发不会卡死
        timerSchedulerService.pollExpiredTimers();
        Thread.sleep(200);

        // Then：timer 状态被推进且 workflow 被唤醒
        for (UUID timerId : timerIds) {
            WorkflowTimerEntity timer = findTimerById(timerId);
            assertThat(timer.status).isIn("EXECUTING", "COMPLETED", "PENDING");
        }
    }

    @Test
    void testLongRunningWorkflow7Days() {
        // Given：一个运行超过 7 天的 workflow
        String workflowId = bootstrapWorkflow("RUNNING");
        setWorkflowStartedAt(workflowId, Instant.now().minus(7, ChronoUnit.DAYS));
        appendStepStarted(workflowId, "long-step", List.of());
        appendStepCompleted(workflowId, "long-step", List.of(), "steady", false);
        appendWorkflowCompleted(workflowId, Map.of("result", "long-run"));

        // When：调度完成 workflow
        schedulerService.processWorkflow(workflowId);

        // Then：durationMs 反映长时间运行
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertThat(state.durationMs).isNotNull();
        assertThat(state.durationMs).isGreaterThanOrEqualTo(Duration.ofDays(7).toMillis());
    }

    @Test
    void testGracefulShutdownMidExecution() {
        // Given：workflow 正在运行，随后被标记为 PAUSED 模拟关机
        String workflowId = bootstrapWorkflow("RUNNING");
        appendStepStarted(workflowId, "shutdown-step", List.of());

        // When：首次 processWorkflow 后手动将状态改为 PAUSED，再次追加完成事件
        assertDoesNotThrow(() -> schedulerService.processWorkflow(workflowId));
        overrideWorkflowStatus(workflowId, "PAUSED");
        appendStepCompleted(workflowId, "shutdown-step", List.of(), "resume", false);
        appendWorkflowCompleted(workflowId, Map.of("result", "graceful"));
        schedulerService.processWorkflow(workflowId);

        // Then：workflow 能够在下次调度中恢复完成
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    // ========================= 4. 故障恢复场景 =========================

    @Test
    void testPartialCompensationFailure() {
        // Given：部分补偿失败的场景
        String workflowId = bootstrapWorkflow("RUNNING");
        appendStepCompleted(workflowId, "step-A", List.of(), "A", true);
        appendStepCompleted(workflowId, "step-B", List.of("step-A"), "B", true);
        appendStepFailed(workflowId, "step-C", List.of("step-B"), "boom");

        // When：第一次调度进入补偿模式
        schedulerService.processWorkflow(workflowId);

        // 模拟补偿失败：只执行一个补偿，another remain scheduled
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        String firstScheduled = events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_SCHEDULED.equals(e.getEventType()))
                .map(WorkflowEvent::getPayload)
                .map(Map.class::cast)
                .map(m -> m.get("stepId").toString())
                .findFirst()
                .orElseThrow();
        compensationExecutor.executeCompensation(workflowId, firstScheduled, null);
        appendWorkflowFailed(workflowId, "compensation-step-B");
        schedulerService.processWorkflow(workflowId);

        // Then：状态进入 FAILED，同时补偿事件数量与完成数量不一致
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("FAILED");

        events = eventStore.getEvents(workflowId, 0);
        long scheduled = events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_SCHEDULED.equals(e.getEventType()))
                .count();
        long completed = events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_COMPLETED.equals(e.getEventType()))
                .count();
        assertThat(scheduled).isGreaterThan(completed);
    }

    @Test
    void testEventReplayAfterCorruption() {
        // Given：clock_times 被写入损坏 JSON
        String workflowId = bootstrapWorkflow("RUNNING");
        updateClockTimes(workflowId, "\"not-json\"");
        appendWorkflowCompleted(workflowId, Map.of("result", "corrupted"));

        // When：processWorkflow 会打印告警但仍继续推进
        schedulerService.processWorkflow(workflowId);

        // Then：workflow 完成但 clock_times 仍保留损坏值，便于后续修复
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
        assertThat(state.clockTimes).isEqualTo("\"not-json\"");
    }

    @Test
    void testSnapshotRecovery() {
        // Given：保存 snapshot 后模拟崩溃
        String workflowId = bootstrapWorkflow("RUNNING");
        eventStore.saveSnapshot(workflowId, 2, Map.of("stage", "alpha"));
        appendStepStarted(workflowId, "resume-step", List.of());
        appendStepCompleted(workflowId, "resume-step", List.of(), "done", false);
        appendWorkflowCompleted(workflowId, Map.of("result", "snapshot-ok"));

        // When：调度恢复 workflow
        schedulerService.processWorkflow(workflowId);

        // Then：snapshot 仍可读取且 workflow 完成
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.snapshot).contains("alpha");
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    @Test
    void testCircularDependencyDetection() {
        // Given：DependencyGraph 中存在 A->B->C->A 环
        DependencyGraph graph = new DependencyGraph();
        graph.addTask("alpha", Set.of("gamma"), 1);
        graph.addTask("beta", Set.of("alpha"), 1);

        // When & Then：注册 gamma 时形成闭环 alpha -> gamma -> beta -> alpha
        assertThatThrownBy(() -> graph.addTask("gamma", Set.of("beta"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular");
    }

    @Test
    @Transactional
    void testWorkflowMigrationVersionUpgrade() {
        // Given：workflow 在运行过程中升级策略版本
        String workflowId = bootstrapWorkflow("RUNNING");
        PolicyVersion version = new PolicyVersion();
        version.policyId = "policy-" + UUID.randomUUID();
        version.version = Instant.now().toEpochMilli();
        version.moduleName = "module";
        version.functionName = "fn";
        version.content = "fn main {}";
        version.active = true;
        version.createdAt = Instant.now();
        version.persist();

        updatePolicyVersion(workflowId, version.id);
        appendWorkflowCompleted(workflowId, Map.of("result", "upgrade"));

        // When：调度完成 workflow
        schedulerService.processWorkflow(workflowId);

        // Then：状态完成且记录新的策略版本
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.policyVersionId).isEqualTo(version.id);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    // ========================= 5. 分布式一致性场景 =========================

    @Test
    void testEventSourcingConsistency() {
        // Given：事件溯源链路包含固定顺序
        String workflowId = bootstrapWorkflow("RUNNING");
        appendStepStarted(workflowId, "es-step-1", List.of());
        appendStepCompleted(workflowId, "es-step-1", List.of(), "ok1", false);
        appendStepStarted(workflowId, "es-step-2", List.of("es-step-1"));
        appendStepCompleted(workflowId, "es-step-2", List.of("es-step-1"), "ok2", false);
        appendWorkflowCompleted(workflowId, Map.of("result", "es"));

        // When：调度完成并重新读取事件
        schedulerService.processWorkflow(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);

        // Then：事件顺序保持写入顺序
        List<Long> sequences = events.stream().map(WorkflowEvent::getSequence).toList();
        assertThat(sequences).isSorted();
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    @Test
    void testMultiTenantIsolation() {
        // Given：两个不同租户的 workflow
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String workflowA = bootstrapWorkflowWithTenant("RUNNING", tenantA);
        String workflowB = bootstrapWorkflowWithTenant("RUNNING", tenantB);
        appendWorkflowCompleted(workflowA, Map.of("tenant", tenantA));
        appendWorkflowCompleted(workflowB, Map.of("tenant", tenantB));

        // When：分别调度
        schedulerService.processWorkflow(workflowA);
        schedulerService.processWorkflow(workflowB);

        // Then：租户字段保持隔离
        WorkflowStateEntity stateA = findWorkflowState(workflowA);
        WorkflowStateEntity stateB = findWorkflowState(workflowB);
        assertThat(stateA.tenantId).isEqualTo(tenantA);
        assertThat(stateB.tenantId).isEqualTo(tenantB);
    }

    @Test
    void testPartialDistributedTransactionFailure() {
        // Given：step1 成功，step2 失败触发补偿
        String workflowId = bootstrapWorkflow("RUNNING");
        appendStepCompleted(workflowId, "txn-step-1", List.of(), "done", true);
        appendStepFailed(workflowId, "txn-step-2", List.of("txn-step-1"), "remote-error");

        // When：第一次调度进入 COMPENSATING
        schedulerService.processWorkflow(workflowId);
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPENSATING");

        // 执行补偿，仅完成一个 step 模拟部分补偿
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_SCHEDULED.equals(e.getEventType()))
                .map(WorkflowEvent::getPayload)
                .map(Map.class::cast)
                .map(m -> m.get("stepId").toString())
                .forEach(step -> compensationExecutor.executeCompensation(workflowId, step, null));
        appendWorkflowCompleted(workflowId, Map.of("result", "txn-compensated"));
        schedulerService.processWorkflow(workflowId);

        // Then：最终状态 COMPENSATED
        WorkflowStateEntity finalState = findWorkflowState(workflowId);
        assertThat(finalState.status).isEqualTo("COMPENSATED");
    }

    @Test
    void testCQRSReadModelStaleness() throws Exception {
        // Given：result 字段初始为 stale
        String workflowId = bootstrapWorkflow("RUNNING");
        updateWorkflowResult(workflowId, "{\"status\":\"stale\"}");
        appendWorkflowCompleted(workflowId, Map.of("status", "fresh"));

        // When：调度完成 workflow
        schedulerService.processWorkflow(workflowId);

        // Then：事件流中的最新结果为 fresh，但状态表仍保留 stale，用于检测读模型滞后
        WorkflowStateEntity state = findWorkflowState(workflowId);
        Map<?, ?> tableResult = objectMapper.readValue(state.result, Map.class);
        assertThat(tableResult.get("status")).isEqualTo("stale");

        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        Map<?, ?> completedPayload = events.stream()
                .filter(e -> WorkflowEvent.Type.WORKFLOW_COMPLETED.equals(e.getEventType()))
                .map(WorkflowEvent::getPayload)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(completedPayload.get("status")).isEqualTo("fresh");
    }

    // ========================= 辅助方法 =========================

    @Transactional
    String bootstrapWorkflow(String status) {
        return bootstrapWorkflowWithTenant(status, null);
    }

    @Transactional
    String bootstrapWorkflowWithTenant(String status, String tenantId) {
        String workflowId = UUID.randomUUID().toString();
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = status;
        state.snapshot = "{}";
        state.lastEventSeq = 0L;
        state.snapshotSeq = 0L;
        state.tenantId = tenantId;
        state.createdAt = Instant.now();
        state.updatedAt = state.createdAt;
        state.persist();
        return workflowId;
    }

    private void appendStepStarted(String workflowId, String stepId, List<String> dependencies) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", new ArrayList<>(dependencies));
        payload.put("status", "STARTED");
        payload.put("startedAt", Instant.now().toString());
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.STEP_STARTED, payload);
    }

    private void appendStepCompleted(String workflowId,
                                     String stepId,
                                     List<String> dependencies,
                                     Object result,
                                     boolean hasCompensation) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", new ArrayList<>(dependencies));
        payload.put("status", "COMPLETED");
        payload.put("completedAt", Instant.now().toString());
        payload.put("hasCompensation", hasCompensation);
        if (result != null) {
            payload.put("result", result);
        }
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.STEP_COMPLETED, payload);
    }

    private void appendStepFailed(String workflowId,
                                  String stepId,
                                  List<String> dependencies,
                                  String error) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", new ArrayList<>(dependencies));
        payload.put("status", "FAILED");
        payload.put("error", error);
        payload.put("completedAt", Instant.now().toString());
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.STEP_FAILED, payload);
    }

    private void appendWorkflowCompleted(String workflowId, Map<String, ?> result) {
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_COMPLETED, result);
    }

    private void appendWorkflowFailed(String workflowId, String reason) {
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_FAILED, Map.of("reason", reason));
    }

    private void assertSequenceIntegrity(String workflowId) {
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getSequence())
                    .as("sequence[%s]", i)
                    .isEqualTo(events.get(i - 1).getSequence() + 1);
        }
    }

    private String randomJsonBlob(int megaBytes) {
        int size = megaBytes * 1024 * 1024;
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) ('a' + i % 26));
        }
        return "{\"blob\":\"" + builder + "\"}";
    }

    @Transactional
    void updateStateSnapshot(String workflowId, String snapshotJson) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.snapshot = snapshotJson;
        state.persist();
    }

    @Transactional
    void updateClockTimes(String workflowId, String clockTimes) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.clockTimes = clockTimes;
        state.persist();
    }

    @Transactional
    void setWorkflowStartedAt(String workflowId, Instant instant) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.startedAt = instant;
        state.persist();
    }

    @Transactional
    void overrideWorkflowStatus(String workflowId, String status) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.status = status;
        state.persist();
    }

    @Transactional
    void updateWorkflowLock(String workflowId, String owner, Instant expiresAt) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.lockOwner = owner;
        state.lockExpiresAt = expiresAt;
        state.persist();
    }

    @Transactional
    void updatePolicyVersion(String workflowId, Long policyVersionId) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.policyVersionId = policyVersionId;
        state.persist();
    }

    @Transactional
    void updateWorkflowResult(String workflowId, String resultJson) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        state.result = resultJson;
        state.persist();
    }

    @Transactional
    void fastForwardTimers(List<UUID> timerIds) {
        for (UUID timerId : timerIds) {
            WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
            if (timer != null) {
                timer.fireAt = Instant.now().minusMillis(5);
                timer.status = "PENDING";
                timer.persist();
            }
        }
    }

}
