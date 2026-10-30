package io.aster.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * 证明 {@link WorkflowTestTimeouts#completeWithin} 对「不响应中断的轮询」也能按时结束——
 * 这正是 aster-api#414 中 executeUntilComplete 卡住时的形态，默认 {@code @Timeout} 对它无效。
 */
class WorkflowTestTimeoutsTest {

  @Test
  void uninterruptibleSpinIsCutOffOnTime() {
    AtomicBoolean stop = new AtomicBoolean();
    long start = System.nanoTime();
    try {
      assertThatThrownBy(() -> WorkflowTestTimeouts.completeWithin(Duration.ofMillis(300), () -> {
        // 模拟旧版 executeUntilComplete：parkNanos 轮询且忽略中断
        while (!stop.get()) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
      })).isInstanceOf(AssertionFailedError.class).hasMessageContaining("aster-api#414");
    } finally {
      stop.set(true);
    }
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(elapsedMs).as("限时必须按时生效，而不是等执行体自行返回").isLessThan(2_000L);
  }

  @Test
  void bodyExceptionPropagatesUnchanged() {
    IllegalStateException boom = new IllegalStateException("boom");
    assertThatThrownBy(() -> WorkflowTestTimeouts.completeWithin(Duration.ofSeconds(5), () -> {
      throw boom;
    })).isSameAs(boom);
  }

  @Test
  void fastBodyCompletesNormally() {
    AtomicBoolean ran = new AtomicBoolean();
    WorkflowTestTimeouts.completeWithin(Duration.ofSeconds(5), () -> ran.set(true));
    assertThat(ran).isTrue();
  }
}
