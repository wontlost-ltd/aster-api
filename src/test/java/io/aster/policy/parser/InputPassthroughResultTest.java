package io.aster.policy.parser;

import aster.truffle.trace.TraceAccess;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.replay.ReplayMetadata;
import io.aster.replay.core.ExecutionPhaseResult;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.replay.core.ReplayExecutionRequest;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则原样返回入参（aster-cloud#639）：evaluate-source / evaluate-json / What-If 共用的
 * {@link DynamicCnlExecutor} 路径。
 *
 * <p>缺陷形态：入参作为 Java Map 传入 guest 被包成 host object，规则 {@code Return person.} 时
 * 结果按成员读出空键集 → {@code {}}，trace 归一化遇到 host object 降级 → {@code trace_not_replayable}。
 * 这里逐一钉住结构体、结构体的嵌套字段、列表、映射四种入参形态，以及 guest 内部构造的结构体不回归。
 */
class InputPassthroughResultTest {

    private static final String PERSON_TYPES = """
        Define Address has city as Text.

        Define Person has name as Text, age as Int, address as Address.
        """;

    @BeforeAll
    static void enableTrace() {
        // 生产由 TruffleTraceInitializer 在启动时开启；单测进程需显式开启才能观测 trace 可回放性
        TraceAccess.setEnabled(true);
    }

    private static Map<String, Object> person() {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "Wellington");
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", "Ann");
        person.put("age", 30);
        person.put("address", address);
        return person;
    }

    private static Object run(String source, String fn, Map<String, Object> context) {
        return DynamicCnlExecutor.executeWithContext(source, context, fn, "en-US").result();
    }

    @Test
    void 原样返回结构体入参得到完整值() {
        String source = "Module issue639.echo.\n\n" + PERSON_TYPES + """

            Rule echo given person as Person, produce Person:
              Return person.
            """;

        Object result = run(source, "echo", Map.of("person", person()));

        assertThat(result).isEqualTo(person());
    }

    @Test
    void 携带类型标记的入参也原样返回() {
        String source = "Module issue639.tagged.\n\n" + PERSON_TYPES + """

            Rule echo given person as Person, produce Person:
              Return person.
            """;
        Map<String, Object> tagged = new LinkedHashMap<>(person());
        tagged.put("__type", "Person");

        Object result = run(source, "echo", Map.of("person", tagged));

        assertThat(result).isEqualTo(tagged);
    }

    @Test
    void 返回入参的嵌套结构体字段得到完整值() {
        String source = "Module issue639.nested.\n\n" + PERSON_TYPES + """

            Rule home given person as Person, produce Address:
              Return person.address.
            """;

        Object result = run(source, "home", Map.of("person", person()));

        assertThat(result).isEqualTo(Map.of("city", "Wellington"));
    }

    @Test
    void 原样返回列表入参得到完整值() {
        String source = """
            Module issue639.list.

            Rule ids given xs as List of Int, produce List of Int:
              Return xs.
            """;

        Object result = run(source, "ids", Map.of("xs", List.of(1, 2, 3)));

        assertThat(result).isInstanceOf(Object[].class);
        assertThat((Object[]) result).containsExactly(1, 2, 3);
    }

    @Test
    void 原样返回映射入参得到完整值() {
        String source = """
            Module issue639.map.

            Rule scores given s as Map Text to Int, produce Map Text to Int:
              Return s.
            """;
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("math", 90);
        scores.put("art", 75);

        Object result = run(source, "scores", Map.of("s", scores));

        assertThat(result).isEqualTo(scores);
    }

    @Test
    void 规则内构造的结构体转换不回归() {
        String source = "Module issue639.build.\n\n" + PERSON_TYPES + """

            Rule rename given person as Person, produce Person:
              Return Person with name set to "Bob", age set to person.age, address set to person.address.
            """;

        Object result = run(source, "rename", Map.of("person", person()));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("name")).isEqualTo("Bob");
        assertThat(map.get("age")).isEqualTo(30);
        assertThat(map.get("address")).isEqualTo(Map.of("city", "Wellington"));
    }

    @Test
    void 原样返回入参的执行可回放且重放一致() {
        String source = "Module issue639.replay.\n\n" + PERSON_TYPES + """

            Rule echo given person as Person, produce Person:
              Return person.
            """;
        Map<String, Object> context = Map.of("person", person());

        ReplayMetadata first = captureReplay(source, context);
        ReplayMetadata second = captureReplay(source, context);

        assertThat(first.replayabilityStatus()).isEqualTo(ReplayMetadata.STATUS_REPLAYABLE);
        assertThat(first.replayabilityReasons()).isEmpty();
        assertThat(first.canonicalOutput())
            .isNotEqualTo("{}")
            .contains("\"name\":\"Ann\"", "\"city\":\"Wellington\"", "\"age\":30");
        assertThat(first.traceHash()).isNotNull();
        // 重放：同一输入再执行一次，输出与 trace 锚点逐一相同
        assertThat(second.canonicalOutputHash()).isEqualTo(first.canonicalOutputHash());
        assertThat(second.traceHash()).isEqualTo(first.traceHash());
        assertThat(second.canonicalOutput()).isEqualTo(first.canonicalOutput());
    }

    private static ReplayMetadata captureReplay(String source, Map<String, Object> context) {
        ReplayExecutionCore core = new ReplayExecutionCore();
        ReplayExecutionRequest req = new ReplayExecutionRequest(
            "tenant-639", source, context, "echo", "en-US", null, null, true, true, true, true);
        ReplayExecutor executor = (tenantId, src, ctx, fn, locale, vocab, legacy, aliases, trusted) -> {
            DynamicCnlExecutor.ExecutionResult r = DynamicCnlExecutor.executeWithContext(src, ctx, fn, locale);
            return new ReplayExecutorResult(r.result(), r.moduleName(), r.functionName(), r.executionTimeMs());
        };
        ExecutionPhaseResult phase = core.execute(req, executor);
        DecisionTrace trace = core.buildDecisionTrace(phase.execResult(), phase.traceDrainResult(), true);
        return core.computeReplayMetadata("toolchain-639", context, phase.execResult(), trace, phase.traceDrainResult());
    }
}
