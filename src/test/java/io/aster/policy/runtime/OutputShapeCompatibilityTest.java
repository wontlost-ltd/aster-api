package io.aster.policy.runtime;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.parser.CoreIrEntrySelector;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.replay.core.parser.ReplayMappers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出参形态兼容（aster-cloud#639 复审）：换用共享转换器后，非「原样返回入参」的结果形态必须与改动前一致。
 *
 * <p>期望值取自改动前实现在两条路径上的实测输出（Decimal → 字符串、超 long 整数 → double、
 * 枚举 → {@code _enum/value/variant/args} 映射、Date → epoch-day 整数等）。唯一有意的变化是映射改为保留键序
 * （此前为 HashMap），而 canonical JSON 本就按键排序，outputHash 不受影响。
 * 每个用例同时跑 {@link DynamicCnlExecutor} 与 {@link TrufflePolicyRuntime}，两条路径结果必须一致。
 */
class OutputShapeCompatibilityTest {

    private static TrufflePolicyRuntime runtime;

    @BeforeAll
    static void setUp() {
        runtime = new TrufflePolicyRuntime();
        runtime.init();
    }

    @AfterAll
    static void tearDown() {
        runtime.cleanup();
    }

    /** 同一规则、同一入参在两条路径上执行，对两个结果施加同一断言。 */
    private static void onBothPaths(String source, String fn, Object arg, Consumer<Object> expectation) throws Exception {
        Object viaExecutor = DynamicCnlExecutor.executeWithContext(source, Map.of("x", arg), fn, "en-US").result();

        var parsed = InProcessCnlParser.parse(source, "en-US");
        CoreModel.Module core = new CoreLowering().lowerModule(parsed.module());
        String coreJson = CoreIrEntrySelector.withEntry(ReplayMappers.DEFAULT.writeValueAsString(core), fn);
        ExecutionResult viaRuntime = runtime.execute(coreJson, new Object[]{arg}, CompilationMetadata.empty());
        assertThat(viaRuntime.success()).as("执行失败: %s", viaRuntime.error()).isTrue();

        expectation.accept(viaExecutor);
        expectation.accept(viaRuntime.result());
    }

    @Test
    void Decimal结果仍为规范字符串() throws Exception {
        onBothPaths("Module shape.dec.\n\nRule dec given x as Int, produce Decimal:\n"
                + "  Return Decimal.divide(x, 4, 2, \"HALF_EVEN\").\n", "dec", 10,
            r -> assertThat(r).isEqualTo("2.5"));
    }

    @Test
    void 超过安全整数的long保持Long() throws Exception {
        onBothPaths("Module shape.lng.\n\nRule big given x as Int, produce Int:\n  Return x.\n", "big",
            9007199254740993L, r -> assertThat(r).isEqualTo(9007199254740993L));
    }

    @Test
    void 超过long的整数仍按double返回() throws Exception {
        BigInteger huge = BigInteger.TWO.pow(70);
        onBothPaths("Module shape.bi.\n\nRule big given x as Int, produce Int:\n  Return x.\n", "big", huge,
            r -> assertThat(r).isEqualTo(huge.doubleValue()));
    }

    @Test
    void 浮点数保持Double() throws Exception {
        onBothPaths("Module shape.dbl.\n\nRule d given x as Float, produce Float:\n  Return x.\n", "d", 1.25d,
            r -> assertThat(r).isEqualTo(1.25d));
    }

    @Test
    void null结果仍为null() throws Exception {
        onBothPaths("Module shape.nul.\n\nRule n given x as Int, produce Text:\n  Return null.\n", "n", 1,
            r -> assertThat(r).isNull());
    }

    @Test
    void 枚举结果仍为_enum映射() throws Exception {
        onBothPaths("Module shape.en.\n\nDefine Color as one of Red, Green.\n\n"
                + "Rule pick given x as Int, produce Color:\n  Return Red.\n", "pick", 1,
            r -> {
                assertThat(r).isInstanceOf(Map.class);
                Map<?, ?> m = (Map<?, ?>) r;
                assertThat(new java.util.HashSet<Object>(m.keySet())).containsExactlyInAnyOrder("_enum", "value", "variant", "args");
                assertThat(m.get("_enum")).isEqualTo("Color");
                assertThat(m.get("value")).isEqualTo("Red");
                assertThat(m.get("variant")).isEqualTo("Red");
                assertThat((Object[]) m.get("args")).isEmpty();
            });
    }

    @Test
    void Date结果仍为epoch_day整数() throws Exception {
        onBothPaths("Module shape.dt.\n\nRule d given x as Text, produce Int:\n  Return Date.fromISO(x).\n", "d",
            "2026-10-27", r -> assertThat(r).isEqualTo(20753));
    }

    /**
     * 入参映射里的 null、小数与超 long 整数原样返回。改动前该用例结果为 {@code {}}（即本缺陷），
     * 这里钉住修复后的形态：null 保持 null，超 long 整数与顶层同口径转 double。
     */
    @Test
    void 入参映射中的null_小数_大整数原样返回() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Ann");
        input.put("nick", null);
        input.put("score", 1.5d);
        input.put("big", BigInteger.TWO.pow(70));
        onBothPaths("Module shape.mn.\n\nRule e given x as Map Text to Text, produce Map Text to Text:\n"
                + "  Return x.\n", "e", input,
            r -> {
                Map<String, Object> expected = new LinkedHashMap<>();
                expected.put("name", "Ann");
                expected.put("nick", null);
                expected.put("score", 1.5d);
                expected.put("big", BigInteger.TWO.pow(70).doubleValue());
                assertThat(r).isEqualTo(expected);
            });
    }
}
