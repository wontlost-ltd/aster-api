package io.aster.policy.parser;

import io.aster.policy.runtime.ExecutionWatchdog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicCnlExecutor} 的墙钟上界：指数分支递归（不会栈溢出、可运行数小时）必须在
 * 看门狗上限附近被中止，并以 {@link DynamicCnlExecutor.DynamicExecutionException.Kind#TIMEOUT} 上报；
 * 中止后执行器仍可正常服务后续请求。
 */
class DynamicCnlExecutorWatchdogTest {

    /** 每层两次自调用：深度 40 ≈ 2^41 次调用，远超任何合理墙钟上限，但栈深只有 40。 */
    static final String EXPONENTIAL_POLICY = """
        Module watchdog.exponential.

        Rule boom given n as Int, produce Int:
          If n equals to 0:
            Return 1.
          Return boom(n minus 1) plus boom(n minus 1).
        """;

    private static final long TEST_TIMEOUT_MS = 500;
    /** 允许的额外延迟：安全点轮询 + Context 关闭 + CI 抖动。 */
    private static final long MARGIN_MS = 4_000;

    private String previous;

    @BeforeEach
    void shortenTimeout() {
        previous = System.getProperty(ExecutionWatchdog.TIMEOUT_PROPERTY);
        System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, Long.toString(TEST_TIMEOUT_MS));
    }

    @AfterEach
    void restoreTimeout() {
        if (previous == null) {
            System.clearProperty(ExecutionWatchdog.TIMEOUT_PROPERTY);
        } else {
            System.setProperty(ExecutionWatchdog.TIMEOUT_PROPERTY, previous);
        }
    }

    @Test
    void 指数递归在墙钟上限附近被中止并归为TIMEOUT() {
        long start = System.nanoTime();
        DynamicCnlExecutor.DynamicExecutionException ex = assertThrows(
            DynamicCnlExecutor.DynamicExecutionException.class,
            () -> DynamicCnlExecutor.executeWithContext(
                EXPONENTIAL_POLICY, Map.of("n", 40), "boom", "en-US"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(DynamicCnlExecutor.DynamicExecutionException.Kind.TIMEOUT, ex.kind(),
            "看门狗中止必须以 TIMEOUT 上报：" + ex.getMessage());
        assertTrue(elapsedMs >= TEST_TIMEOUT_MS, "不得早于上限中止：" + elapsedMs + "ms");
        assertTrue(elapsedMs < TEST_TIMEOUT_MS + MARGIN_MS, "应在上限附近停下，实际 " + elapsedMs + "ms");
    }

    @Test
    void 中止后执行器仍可服务后续请求() {
        assertThrows(DynamicCnlExecutor.DynamicExecutionException.class,
            () -> DynamicCnlExecutor.executeWithContext(
                EXPONENTIAL_POLICY, Map.of("n", 40), "boom", "en-US"));

        // 小规模输入在上限内正常完成：2^5 = 32
        DynamicCnlExecutor.ExecutionResult ok = DynamicCnlExecutor.executeWithContext(
            EXPONENTIAL_POLICY, Map.of("n", 5), "boom", "en-US");
        assertEquals(32, ((Number) ok.result()).intValue());
    }
}
