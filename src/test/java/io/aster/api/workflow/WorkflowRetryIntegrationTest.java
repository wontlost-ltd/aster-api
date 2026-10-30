package io.aster.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import aster.core.exceptions.MaxRetriesExceededException;
import aster.runtime.workflow.WorkflowEvent;
import aster.truffle.runtime.AsyncTaskRegistry;
import io.aster.workflow.DeterminismContext;
import io.aster.api.workflow.DeterminismSnapshot;
import aster.truffle.runtime.WorkflowScheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 工作流重试端到端集成测试
 *
 * 测试从 WorkflowScheduler 到 PostgresEventStore 的完整重试流程：
 * - 步骤失败后成功重试
 * - 达到最大重试次数抛异常
 * - 多步骤混合重试场景
 * - 重放一致性验证
 *
 * <p>每次 {@code executeUntilComplete} 都经 {@link WorkflowTestTimeouts#completeWithin} 硬性限时：延迟重试 poller
 * 若卡住（如 aster-api#414 中重放时钟耗尽导致到期任务永不触发）会无限等待且不响应中断，限时让它按时以失败结束。
 */
@QuarkusTest
class WorkflowRetryIntegrationTest {

  @Inject
  PostgresEventStore eventStore;

  @BeforeEach
  @AfterEach
  @Transactional
  void cleanup() {
    WorkflowTimerEntity.deleteAll();
    WorkflowEventEntity.deleteAll();
    WorkflowStateEntity.deleteAll();
  }

  @Test
  @Transactional
  void testRetrySuccessE2E() {
    String workflowId = UUID.randomUUID().toString();
    AsyncTaskRegistry registry = new AsyncTaskRegistry();
    registry.setWorkflowId(workflowId);
    registry.setEventStore(eventStore);
    registry.startPolling();

    try {
      AtomicInteger attempts = new AtomicInteger();
      AsyncTaskRegistry.RetryPolicy policy = new AsyncTaskRegistry.RetryPolicy(3, "exponential", 100L);

      // 第1次失败，第2次成功
      registry.registerTaskWithRetry("task-success", () -> {
        if (attempts.incrementAndGet() == 1) {
          throw new RuntimeException("transient error");
        }
        registry.setResult("task-success", "ok");
        return "ok";
      }, Collections.emptySet(), policy);

      WorkflowTestTimeouts.completeWithin(WorkflowTestTimeouts.WORKFLOW_RUN_LIMIT, registry::executeUntilComplete);

      // 验证任务成功
      assertThat(registry.getResult("task-success")).isEqualTo("ok");
      assertThat(attempts.get()).isEqualTo(2);

      // 验证事件日志记录了重试元数据
      List<WorkflowEventEntity> events = WorkflowEventEntity.find(
          "workflowId = ?1 ORDER BY sequence",
          UUID.fromString(workflowId)
      ).list();

      assertThat(events).hasSizeGreaterThanOrEqualTo(1);
      WorkflowEventEntity retryEvent = events.stream()
          .filter(e -> e.attemptNumber == 2)
          .findFirst()
          .orElseThrow();

      assertThat(retryEvent.backoffDelayMs).isGreaterThanOrEqualTo(100L);
      assertThat(retryEvent.failureReason).isEqualTo("transient error");
    } finally {
      registry.stopPolling();
      registry.shutdown();
    }
  }

  @Test
  @Transactional
  void testRetryMaxAttemptsE2E() {
    String workflowId = UUID.randomUUID().toString();
    AsyncTaskRegistry registry = new AsyncTaskRegistry();
    registry.setWorkflowId(workflowId);
    registry.setEventStore(eventStore);
    registry.startPolling();

    try {
      AsyncTaskRegistry.RetryPolicy policy = new AsyncTaskRegistry.RetryPolicy(3, "linear", 50L);

      registry.registerTaskWithRetry("task-fail", () -> {
        throw new RuntimeException("persistent failure");
      }, Collections.emptySet(), policy);

      // 验证达到最大次数后抛出 MaxRetriesExceededException
      assertThatThrownBy(() -> WorkflowTestTimeouts.completeWithin(
          WorkflowTestTimeouts.WORKFLOW_RUN_LIMIT, registry::executeUntilComplete))
          .isInstanceOf(MaxRetriesExceededException.class)
          .hasMessageContaining("Max retries (3) exceeded")
          .hasMessageContaining("persistent failure");

      // 验证事件日志记录了所有 3 次尝试
      List<WorkflowEventEntity> events = WorkflowEventEntity.find(
          "workflowId = ?1 ORDER BY sequence",
          UUID.fromString(workflowId)
      ).list();

      // 应该有 2 次重试事件（attemptNumber = 2, 3）
      long retryEventCount = events.stream()
          .filter(e -> e.attemptNumber > 1)
          .count();

      assertThat(retryEventCount).isEqualTo(2);

      // 验证 backoff 递增（线性策略）
      WorkflowEventEntity retry1 = events.stream()
          .filter(e -> e.attemptNumber == 2)
          .findFirst()
          .orElseThrow();
      WorkflowEventEntity retry2 = events.stream()
          .filter(e -> e.attemptNumber == 3)
          .findFirst()
          .orElseThrow();

      assertThat(retry1.backoffDelayMs).isGreaterThanOrEqualTo(50L);
      assertThat(retry2.backoffDelayMs).isGreaterThanOrEqualTo(100L);
      assertThat(retry2.backoffDelayMs).isGreaterThan(retry1.backoffDelayMs);
    } finally {
      registry.stopPolling();
      registry.shutdown();
    }
  }

  @Test
  @Transactional
  void testMultiStepRetryE2E() {
    String workflowId = UUID.randomUUID().toString();
    AsyncTaskRegistry registry = new AsyncTaskRegistry();
    registry.setWorkflowId(workflowId);
    registry.setEventStore(eventStore);
    registry.startPolling();

    try {
      AtomicInteger step1Attempts = new AtomicInteger();
      AtomicInteger step2Attempts = new AtomicInteger();

      AsyncTaskRegistry.RetryPolicy policy1 = new AsyncTaskRegistry.RetryPolicy(3, "exponential", 100L);
      AsyncTaskRegistry.RetryPolicy policy2 = new AsyncTaskRegistry.RetryPolicy(2, "linear", 50L);

      // 步骤1：第1次失败，第2次成功
      registry.registerTaskWithRetry("step1", () -> {
        if (step1Attempts.incrementAndGet() == 1) {
          throw new RuntimeException("step1 error");
        }
        registry.setResult("step1", "step1-ok");
        return "step1-ok";
      }, Collections.emptySet(), policy1);

      // 步骤2：依赖步骤1，第1次成功
      registry.registerTaskWithRetry("step2", () -> {
        step2Attempts.incrementAndGet();
        registry.setResult("step2", "step2-ok");
        return "step2-ok";
      }, Collections.singleton("step1"), policy2);

      WorkflowTestTimeouts.completeWithin(WorkflowTestTimeouts.WORKFLOW_RUN_LIMIT, registry::executeUntilComplete);

      // 验证两个步骤都成功
      assertThat(registry.getResult("step1")).isEqualTo("step1-ok");
      assertThat(registry.getResult("step2")).isEqualTo("step2-ok");
      assertThat(step1Attempts.get()).isEqualTo(2);
      assertThat(step2Attempts.get()).isEqualTo(1);

      // 验证事件日志
      List<WorkflowEventEntity> events = WorkflowEventEntity.find(
          "workflowId = ?1 ORDER BY sequence",
          UUID.fromString(workflowId)
      ).list();

      // 应该有 step1 的重试事件
      long step1RetryCount = events.stream()
          .filter(e -> e.attemptNumber == 2 && e.payload.toString().contains("step1"))
          .count();

      assertThat(step1RetryCount).isGreaterThanOrEqualTo(1);
    } finally {
      registry.stopPolling();
      registry.shutdown();
    }
  }

  @Test
  void testRetryReplayConsistency() {
    String workflowId = UUID.randomUUID().toString();

    // 第一次执行：记录事件
    DeterminismContext recordingContext = new DeterminismContext();
    AsyncTaskRegistry registry1 = new AsyncTaskRegistry(1, recordingContext);
    registry1.setWorkflowId(workflowId);
    registry1.setEventStore(eventStore);
    registry1.startPolling();

    try {
      AtomicInteger attempts = new AtomicInteger();
      AsyncTaskRegistry.RetryPolicy policy = new AsyncTaskRegistry.RetryPolicy(3, "exponential", 1000L);

      registry1.registerTaskWithRetry("task-replay", () -> {
        if (attempts.incrementAndGet() < 2) {
          throw new RuntimeException("error-" + attempts.get());
        }
        registry1.setResult("task-replay", "success");
        return "success";
      }, Collections.emptySet(), policy);

      WorkflowTestTimeouts.completeWithin(WorkflowTestTimeouts.WORKFLOW_RUN_LIMIT, registry1::executeUntilComplete);
    } finally {
      registry1.stopPolling();
      registry1.shutdown();
    }

    // 获取第一次执行的事件日志
    List<WorkflowEventEntity> originalEvents = WorkflowEventEntity.find(
        "workflowId = ?1 AND attemptNumber > 1 ORDER BY sequence",
        UUID.fromString(workflowId)
    ).list();

    assertThat(originalEvents).isNotEmpty();
    Long originalBackoff = originalEvents.get(0).backoffDelayMs;
    DeterminismSnapshot recorded = DeterminismSnapshot.from(
        recordingContext.clock(),
        recordingContext.uuid(),
        recordingContext.random()
    );

    // 清理状态，准备重放
    cleanup();

    // 第二次执行（重放）：使用相同种子
    DeterminismContext replayContext = new DeterminismContext();
    recorded.applyTo(
        replayContext.clock(),
        replayContext.uuid(),
        replayContext.random()
    );
    AsyncTaskRegistry registry2 = new AsyncTaskRegistry(1, replayContext);
    registry2.setWorkflowId(workflowId);
    registry2.setEventStore(eventStore);
    registry2.startPolling();

    try {
      AtomicInteger attempts2 = new AtomicInteger();
      AsyncTaskRegistry.RetryPolicy policy = new AsyncTaskRegistry.RetryPolicy(3, "exponential", 1000L);

      registry2.registerTaskWithRetry("task-replay", () -> {
        if (attempts2.incrementAndGet() < 2) {
          throw new RuntimeException("error-" + attempts2.get());
        }
        registry2.setResult("task-replay", "success");
        return "success";
      }, Collections.emptySet(), policy);

      WorkflowTestTimeouts.completeWithin(WorkflowTestTimeouts.WORKFLOW_RUN_LIMIT, registry2::executeUntilComplete);
    } finally {
      registry2.stopPolling();
      registry2.shutdown();
    }

    // 获取重放的事件日志
    List<WorkflowEventEntity> replayEvents = WorkflowEventEntity.find(
        "workflowId = ?1 AND attemptNumber > 1 ORDER BY sequence",
        UUID.fromString(workflowId)
    ).list();

    assertThat(replayEvents).isNotEmpty();
    Long replayBackoff = replayEvents.get(0).backoffDelayMs;

    // 验证重放一致性：相同种子产生相同的 backoff 延迟
    assertThat(replayBackoff).isEqualTo(originalBackoff);
  }
}
