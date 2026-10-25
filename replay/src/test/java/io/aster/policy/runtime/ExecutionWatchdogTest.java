package io.aster.policy.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 超时配置的解析规则：缺省 5s；非正值或非法值一律回落缺省，绝不允许“无上界”。 */
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
}
