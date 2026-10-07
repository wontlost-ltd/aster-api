package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策解读（ADR 0039 §5.2）：三条类型驱动规则，其余一律 INDETERMINATE。
 *
 * <p>守的是一类隐蔽错误：若「什么算通过」靠字符串猜测，比较出来的「变化」
 * 可能只是解读规则的差异，而不是策略本身的变化。
 */
class DecisionInterpreterTest {

    @Test
    void 布尔直接映射() {
        assertThat(DecisionInterpreter.interpret(true))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(false))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @Test
    void Verdict值按outcome直映() {
        assertThat(DecisionInterpreter.interpret(verdict("ALLOW"))).isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(verdict("DENY"))).isEqualTo(DecisionInterpreter.Verdict.DENIED);
        assertThat(DecisionInterpreter.interpret(verdict("REQUIRE_APPROVAL"))).isEqualTo(DecisionInterpreter.Verdict.REQUIRE_APPROVAL);
        assertThat(DecisionInterpreter.interpret(verdict("ESCALATE"))).isEqualTo(DecisionInterpreter.Verdict.ESCALATE);
    }

    @Test
    void 未知outcome为INDETERMINATE() {
        assertThat(DecisionInterpreter.interpret(verdict("MAYBE"))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void 模板约定approved布尔键仍直映() {
        assertThat(DecisionInterpreter.interpret(Map.of("__type", "Decision", "approved", true, "reason", "OK")))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(Map.of("approved", false)))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "approved", "true", "REJECTED", "denied", "false", "Hello"})
    void 字符串一律INDETERMINATE(String s) {
        assertThat(DecisionInterpreter.interpret(s)).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void decision字符串键不再识别() {
        assertThat(DecisionInterpreter.interpret(Map.of("decision", "APPROVED")))
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void approved键非布尔不识别() {
        assertThat(DecisionInterpreter.interpret(Map.of("approved", "yes")))
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void 反射isApproved不再识别() {
        Object host = new Object() {
            @SuppressWarnings("unused")
            public boolean isApproved() {
                return true;
            }
        };
        assertThat(DecisionInterpreter.interpret(host)).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void null与无关结构不是决策() {
        assertThat(DecisionInterpreter.interpret(null)).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
        assertThat(DecisionInterpreter.interpret(Map.of("score", 700))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
        assertThat(DecisionInterpreter.interpret(java.util.List.of(1, 2, 3))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    private static Map<String, Object> verdict(String outcome) {
        var m = new LinkedHashMap<String, Object>();
        m.put("__type", "Verdict");
        m.put("outcome", outcome);
        return m;
    }
}
