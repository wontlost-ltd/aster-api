package io.aster.api.workflow;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.function.Executable;

/**
 * 工作流测试的硬性限时。
 *
 * <p>不能只靠 JUnit 默认的 {@code @Timeout}：它在测试线程上运行、超时只中断线程，要等方法自行返回才报失败。
 * 已发布的 truffle（≤1.0.37）里 {@code AsyncTaskRegistry.executeUntilComplete} 以 {@code parkNanos} 轮询且不检查中断，
 * 重放时钟耗尽时（aster-api#414）会无限等待，默认 {@code @Timeout} 拦不住，还会把 1ms 轮询变成空转。
 * 这里改用 {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}：在独立线程执行，到时立即判失败并
 * 中断该线程，测试线程不受其是否响应中断影响。
 */
final class WorkflowTestTimeouts {

  /** 工作流重试用例的默认上限：正常 1–3 秒完成，留足余量。 */
  static final Duration WORKFLOW_RUN_LIMIT = Duration.ofSeconds(60);

  private WorkflowTestTimeouts() {
  }

  /** 在 {@code limit} 内执行完毕，否则立即以失败结束；执行体抛出的异常原样上抛。 */
  static void completeWithin(Duration limit, Executable body) {
    assertTimeoutPreemptively(limit, body,
        () -> "工作流执行超过 " + limit.toSeconds() + "s 未结束（疑似延迟重试 poller 卡住，见 aster-api#414）");
  }
}
