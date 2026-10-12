package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多 Rule 策略必须执行**所请求的**函数，而不是源码里的第一个函数。
 *
 * <p>缺陷形态：DynamicCnlExecutor 按目标函数的参数表做位置映射、并把目标名写进
 * {@code ExecutionResult.functionName()}，但 Truffle 的 Loader 只认 DEFAULT_FUNCTION
 * （"main"）→ "main" → 第一个函数。于是：参数表相同时**静默返回另一个函数的结果**，
 * 且执行记录声称执行的是目标函数；参数个数不同时报 "expects at least N arguments"。
 *
 * <p>既有的入口测试都把入口命名为 main，恰好命中 Truffle 的按名优先，所以一直是绿的。
 * 本类刻意避开 main 这个名字。
 */
class DynamicCnlExecutorFunctionSelectionTest {

    private static final String EN_ADD_FIRST = """
        Module demo.select.

        Rule add2 given a as Int, b as Int, produce Int:
          Return a plus b.

        Rule twice given a as Int, b as Int, produce Int:
          Return add2(a, b) times 2.
        """;

    private static final String EN_TWICE_FIRST = """
        Module demo.select.

        Rule twice given a as Int, b as Int, produce Int:
          Return add2(a, b) times 2.

        Rule add2 given a as Int, b as Int, produce Int:
          Return a plus b.
        """;

    private static final String ZH_ADD_FIRST = """
        模块 demo.select。

        规则 add2 给定 a 作为 整数，b 作为 整数 产出 整数：
          返回 a 加上 b。

        规则 twice 给定 a 作为 整数，b 作为 整数 产出 整数：
          返回 add2(a, b) 乘以 2。
        """;

    /** 生产上「科学计算器」的最小复现：第一个 Rule 两参数，目标 Rule 一参数。 */
    private static final String ZH_ARITY_MISMATCH = """
        模块 demo.select。

        规则 power 给定 base 作为 整数，exponent 作为 整数 产出 整数：
          如果 exponent 小于 1：
            返回 1。
          返回 base 乘以 power(base, exponent 减去 1)。

        规则 factorial 给定 n 作为 整数 产出 整数：
          如果 n 小于 2：
            返回 1。
          返回 n 乘以 factorial(n 减去 1)。
        """;

    private static final String EN_WITH_MAIN = """
        Module demo.select.

        Rule main given a as Int, b as Int, produce Int:
          Return a plus b.

        Rule helper given a as Int, b as Int, produce Int:
          Return a times b.
        """;

    private static final Map<String, Object> AB = Map.of("a", 7, "b", 5);

    @Test
    void 目标在第二个时执行目标而非第一个函数() {
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(EN_ADD_FIRST, AB, "twice", "en-US");

        assertThat(result.result()).isEqualTo(24);
        assertThat(result.functionName()).isEqualTo("twice");
    }

    @Test
    void 目标在第一个时行为不变() {
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(EN_ADD_FIRST, AB, "add2", "en-US");

        assertThat(result.result()).isEqualTo(12);
        assertThat(result.functionName()).isEqualTo("add2");
    }

    @Test
    void 顺序反过来也执行目标() {
        assertThat(DynamicCnlExecutor.executeWithContext(EN_TWICE_FIRST, AB, "add2", "en-US").result())
            .isEqualTo(12);
        assertThat(DynamicCnlExecutor.executeWithContext(EN_TWICE_FIRST, AB, "twice", "en-US").result())
            .isEqualTo(24);
    }

    @Test
    void 中文源码同样执行目标() {
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(ZH_ADD_FIRST, AB, "twice", "zh-CN");

        assertThat(result.result()).isEqualTo(24);
        assertThat(result.functionName()).isEqualTo("twice");
    }

    @Test
    void 参数个数不同的目标不再被当成第一个函数执行() {
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            ZH_ARITY_MISMATCH, Map.of("n", 5), "factorial", "zh-CN");

        assertThat(result.result()).isEqualTo(120);
        assertThat(result.functionName()).isEqualTo("factorial");
    }

    @Test
    void 单个非main规则行为不变() {
        String single = """
            Module demo.select.

            Rule absolute given x as Int, produce Int:
              If x less than 0 then Return 0 minus x.
              Return x.
            """;
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(single, Map.of("x", -3), null, "en-US");

        assertThat(result.result()).isEqualTo(3);
        assertThat(result.functionName()).isEqualTo("absolute");
    }

    @Test
    void 请求main时执行main() {
        assertThat(DynamicCnlExecutor.executeWithContext(EN_WITH_MAIN, AB, "main", "en-US").result())
            .isEqualTo(12);
    }

    @Test
    void 模块含main时请求其它函数必须响亮失败而非静默执行main() {
        assertThatThrownBy(() -> DynamicCnlExecutor.executeWithContext(EN_WITH_MAIN, AB, "helper", "en-US"))
            .hasMessageContaining("helper")
            .hasMessageContaining("main");
    }
}
