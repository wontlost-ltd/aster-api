package io.aster.policy.runtime;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.parser.CoreIrEntrySelector;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.replay.core.parser.ReplayMappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则原样返回入参（aster-cloud#639）：已发布版本 {@code /evaluate} 走的 {@link TrufflePolicyRuntime} 路径。
 *
 * <p>与 {@code InputPassthroughResultTest} 覆盖同一缺陷的另一条执行路径：两条路径共用
 * {@link PolyglotValues}，任一侧绕过它都会让 {@code Return person.} 重新退化成 {@code {}}。
 *
 * <p>不挂 {@code aster.truffle.tests.skip} 开关：该开关在构建脚本里恒为 true，挂上即永远不跑；
 * 而同一进程内 {@code DynamicCnlExecutor} 的执行测试证明 aster 解释器在单测 JVM 中可用。
 */
class TrufflePolicyRuntimeInputPassthroughTest {

    private TrufflePolicyRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new TrufflePolicyRuntime();
        runtime.init();
    }

    @AfterEach
    void tearDown() {
        runtime.cleanup();
    }

    /** 与发布编译同口径：CNL → AST → Core IR → JSON，并把目标函数钉为入口。 */
    private static String compile(String source, String fn) throws Exception {
        var parsed = InProcessCnlParser.parse(source, "en-US");
        CoreModel.Module core = new CoreLowering().lowerModule(parsed.module());
        return CoreIrEntrySelector.withEntry(ReplayMappers.DEFAULT.writeValueAsString(core), fn);
    }

    private Object run(String source, String fn, Object arg) throws Exception {
        ExecutionResult result = runtime.execute(compile(source, fn), new Object[]{arg}, CompilationMetadata.empty());
        assertThat(result.success()).as("执行失败: %s", result.error()).isTrue();
        return result.result();
    }

    private static Map<String, Object> person() {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", "Ann");
        person.put("age", 30);
        person.put("address", Map.of("city", "Wellington"));
        return person;
    }

    private static final String PERSON_TYPES = """
        Define Address has city as Text.

        Define Person has name as Text, age as Int, address as Address.
        """;

    @Test
    void 原样返回结构体入参得到完整值() throws Exception {
        String source = "Module issue639.rt.echo.\n\n" + PERSON_TYPES + """

            Rule echo given person as Person, produce Person:
              Return person.
            """;

        assertThat(run(source, "echo", person())).isEqualTo(person());
    }

    @Test
    void 返回入参的嵌套结构体字段得到完整值() throws Exception {
        String source = "Module issue639.rt.nested.\n\n" + PERSON_TYPES + """

            Rule home given person as Person, produce Address:
              Return person.address.
            """;

        assertThat(run(source, "home", person())).isEqualTo(Map.of("city", "Wellington"));
    }

    @Test
    void 原样返回列表入参得到完整值() throws Exception {
        String source = """
            Module issue639.rt.list.

            Rule ids given xs as List of Int, produce List of Int:
              Return xs.
            """;

        Object result = run(source, "ids", List.of(1, 2, 3));

        assertThat(result).isInstanceOf(Object[].class);
        assertThat((Object[]) result).containsExactly(1, 2, 3);
    }

    @Test
    void 原样返回映射入参得到完整值() throws Exception {
        String source = """
            Module issue639.rt.map.

            Rule scores given s as Map Text to Int, produce Map Text to Int:
              Return s.
            """;

        assertThat(run(source, "scores", Map.of("math", 90, "art", 75)))
            .isEqualTo(Map.of("math", 90, "art", 75));
    }
}
