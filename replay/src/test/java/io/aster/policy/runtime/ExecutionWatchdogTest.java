package io.aster.policy.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 看门狗的配置解析、期限边界语义与线程分工。
 *
 * <p>取消动作经包级 {@code arm(Runnable, long)} 注入，从而能确定性地制造「关闭阻塞」「关闭失败」，
 * 而不依赖真实 guest 执行的时序。
 */
class ExecutionWatchdogTest {

    @AfterEach
    void clear() {
        System.clearProperty(ExecutionWatchdog.TIMEOUT_PROPERTY);
    }

    @Test
    void 未配置时取缺省值() {
        System.clearProperty(ExecutionWatchdog.TIMEOUT_PROPERTY);
        assertEquals(ExecutionWatchdog.DEFAULT_TIMEOUT_MS, ExecutionWatchdog.configuredTimeoutMs());
        assertEquals(5_000L, ExecutionWatchdog.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void 正值配置生效() {
        System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, "1234");
        assertEquals(1234L, ExecutionWatchdog.configuredTimeoutMs());
    }

    @Test
    void 非正值与非法值回落缺省() {
        System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, "0");
        assertEquals(ExecutionWatchdog.DEFAULT_TIMEOUT_MS, ExecutionWatchdog.configuredTimeoutMs());
        System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, "-1");
        assertEquals(ExecutionWatchdog.DEFAULT_TIMEOUT_MS, ExecutionWatchdog.configuredTimeoutMs());
        System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, "abc");
        assertEquals(ExecutionWatchdog.DEFAULT_TIMEOUT_MS, ExecutionWatchdog.configuredTimeoutMs());
    }

    @Test
    void 期限边界_触发与解除互斥_disarm返回值与取消是否执行严格一致() throws Exception {
        record Trial(boolean fired, AtomicBoolean cancelled) { }
        List<Trial> trials = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            AtomicBoolean cancelled = new AtomicBoolean();
            ExecutionWatchdog.Guard guard = ExecutionWatchdog.arm(() -> cancelled.set(true), 1);
            spinNanos(ThreadLocalRandom.current().nextLong(0, 2_000_000));
            trials.add(new Trial(guard.disarm(), cancelled));
        }
        // 关闭在虚拟线程上异步执行，给它时间落地后再核对
        Thread.sleep(500);
        for (Trial t : trials) {
            assertEquals(t.fired(), t.cancelled().get(),
                "解除先赢则绝不取消；触发先赢则必然取消——不允许第三种结果");
        }
    }

    @Test
    void 一次慢关闭不推迟其他到期的超时() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        ExecutionWatchdog.Guard slow = ExecutionWatchdog.arm(() -> {
            slowStarted.countDown();
            sleepQuietly(3_000);
        }, 5);
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS));

        int n = 6;
        long timeoutMs = 50;
        CountDownLatch others = new CountDownLatch(n);
        ConcurrentLinkedQueue<Long> latenessMs = new ConcurrentLinkedQueue<>();
        long armedAt = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ExecutionWatchdog.arm(() -> {
                latenessMs.add((System.nanoTime() - armedAt) / 1_000_000 - timeoutMs);
                others.countDown();
            }, timeoutMs);
        }
        assertTrue(others.await(1, TimeUnit.SECONDS), "其余超时必须在慢关闭进行期间照常触发");
        for (long late : latenessMs) {
            assertTrue(late < 500, "触发延迟过大：" + late + "ms");
        }
        assertTrue(slow.disarm());
    }

    @Test
    void 关闭失败_守卫仍为已触发_但非取消异常按真实错误上报() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        ExecutionWatchdog.Guard guard = ExecutionWatchdog.arm(() -> {
            attempted.countDown();
            throw new RuntimeException("close failed");
        }, 1);
        assertTrue(attempted.await(2, TimeUnit.SECONDS));

        assertTrue(guard.disarm(), "已触发即不可逆，Context 必须丢弃");
        assertFalse(guard.timedOut(new ArithmeticException("guest 自身错误")),
            "关闭失败后 guest 抛出的真实错误不得被冒充为超时");
        assertTrue(guard.timedOut(new RuntimeException("wrapped",
                new IllegalStateException("The Context is already closed."))),
            "已触发且失败由关闭引起时归为超时");

        // 关闭失败不得拖垮看门狗：后续守卫照常触发
        CountDownLatch next = new CountDownLatch(1);
        ExecutionWatchdog.arm(next::countDown, 1);
        assertTrue(next.await(2, TimeUnit.SECONDS));
    }

    @Test
    void 未触发时任何异常都不算超时() {
        ExecutionWatchdog.Guard guard = ExecutionWatchdog.arm(() -> { }, 60_000);
        assertFalse(guard.timedOut(new IllegalStateException("closed")));
        assertFalse(guard.disarm());
    }

    private static void spinNanos(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
