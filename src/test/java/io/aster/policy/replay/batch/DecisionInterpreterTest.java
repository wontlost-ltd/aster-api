package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策解读（ADR 0034）。
 *
 * <p><b>这个测试守的是一类隐蔽错误</b>：What-If 比较 base 与 target 的决策，
 * 若「什么算通过」的口径与线上不一致，比较出来的「变化」可能只是两套解读规则
 * 的差异，而不是策略本身的变化——**数字看起来合理，实际测量的是错的东西**。
 *
 * <p>三态而非二态是关键：值输出（策略返回数字/文本）既不是通过也不是拒绝，
 * 强行归入任一边会凭空造出大量假「变化」。本仓在 executions 表上踩过同样的坑。
 */
class DecisionInterpreterTest {

    @Test
    void 布尔直接映射() {
        assertThat(DecisionInterpreter.interpret(true))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(false))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "approved", " Approved ", "true", "TRUE", "approve"})
    void 通过类字符串(String s) {
        assertThat(DecisionInterpreter.interpret(s))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "denied", "DENY", "false", " False "})
    void 拒绝类字符串(String s) {
        assertThat(DecisionInterpreter.interpret(s))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello", "42", "3.14", "maybe", "pending", ""})
    void 值输出不是决策(String s) {
        // 把「返回 42」当成拒绝，会让每一条值输出型执行都显示为「决策变化」——
        // 凭空造出大量假变化
        assertThat(DecisionInterpreter.interpret(s))
            .as("值输出 %s 必须是 INDETERMINATE", s)
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void null不是决策() {
        assertThat(DecisionInterpreter.interpret(null))
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void Map读approved键() {
        assertThat(DecisionInterpreter.interpret(Map.of("approved", true)))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(Map.of("approved", false)))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @Test
    void Map回退读decision键() {
        assertThat(DecisionInterpreter.interpret(Map.of("decision", "denied")))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @Test
    void Map无相关键则不猜() {
        assertThat(DecisionInterpreter.interpret(Map.of("score", 700)))
            .as("有 score 不代表有决策——不得猜")
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }

    @Test
    void 带isApproved的对象走反射() {
        record Decision(boolean approved) {
            @SuppressWarnings("unused")
            public boolean isApproved() {
                return approved;
            }
        }
        assertThat(DecisionInterpreter.interpret(new Decision(true)))
            .isEqualTo(DecisionInterpreter.Verdict.APPROVED);
        assertThat(DecisionInterpreter.interpret(new Decision(false)))
            .isEqualTo(DecisionInterpreter.Verdict.DENIED);
    }

    @Test
    void 无法解读的对象落INDETERMINATE而非默认拒绝() {
        // ★默认拒绝是危险的：它会把所有无法解读的结果都算成「拒绝」，
        //   于是 base 通过、target 无法解读时显示为「新增拒绝」——一个假变化
        assertThat(DecisionInterpreter.interpret(new Object()))
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
        assertThat(DecisionInterpreter.interpret(java.util.List.of(1, 2, 3)))
            .isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
    }
}
