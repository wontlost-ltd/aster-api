package io.aster.policy.analysis;

import io.aster.policy.parser.InProcessCnlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则冲突检测测试（Phase 2）。
 *
 * <p>★两组断言同等重要：
 * <ul>
 *   <li><b>能报</b>：真矛盾/真冗余必须被发现</li>
 *   <li><b>不误报</b>：正常规则一条提示都不能有</li>
 * </ul>
 * 后者甚至更重要——业务人员对静态检查的信任极其脆弱，一次误报就会让整个
 * 功能被忽略。故本文件用了多组"正常写法"做负向断言。
 */
class RuleConflictAnalyzerTest {

    private static List<RuleConflictAnalyzer.Finding> analyze(String body) {
        String src = "Module m.\n\nRule r given x as Number produce Number:\n" + body;
        return RuleConflictAnalyzer.analyze(InProcessCnlParser.parse(src).module());
    }

    @Test
    void 关键_嵌套矛盾条件被判为恒假() {
        var f = analyze("""
              If x is greater than 100:
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertEquals(1, f.size(), "应恰好报 1 条，实际: " + f);
        assertEquals(RuleConflictAnalyzer.Finding.Kind.ALWAYS_FALSE, f.get(0).kind());
        assertTrue(f.get(0).message().contains("矛盾"), f.get(0).message());
    }

    @Test
    void 关键_被外层蕴含的条件判为多余() {
        // x>100 时 x>50 恒成立
        var f = analyze("""
              If x is greater than 100:
                If x is greater than 50:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertEquals(1, f.size(), "应恰好报 1 条，实际: " + f);
        assertEquals(RuleConflictAnalyzer.Finding.Kind.REDUNDANT, f.get(0).kind());
    }

    @Test
    void 关键_正常的收窄条件不误报() {
        // x>100 内层 x>200 是合理的进一步收窄，不是冗余也不是矛盾
        var f = analyze("""
              If x is greater than 100:
                If x is greater than 200:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "正常收窄不该报，实际: " + f);
    }

    @Test
    void 关键_正常区间条件不误报() {
        var f = analyze("""
              If x is greater than 100:
                If x is less than 200:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "x∈(100,200) 是可满足的，不该报: " + f);
    }

    @Test
    void 关键_单层条件不误报() {
        var f = analyze("""
              If x is greater than 100:
                Return 1.
              Return 0.
            """);
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void 关键_无法规约的条件被跳过_不猜不报() {
        // 变量比变量：无法规约成区间，必须静默跳过而非瞎报
        var f = analyze("""
              If x is greater than x:
                Return 1.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "无法规约的条件不该产生任何提示: " + f);
    }

    @Test
    void 矛盾分支内部不再重复报_避免同源噪音() {
        // 内层已矛盾，再往里嵌一层不该额外报
        var f = analyze("""
              If x is greater than 100:
                If x is less than 50:
                  If x is less than 10:
                    Return 1.
                  Return 2.
                Return 3.
              Return 0.
            """);
        assertEquals(1, f.size(), "矛盾分支内部应停止分析，实际: " + f);
    }

    @Test
    void else_分支不继承_then_的约束() {
        // else 里 x<=100，与 x<50 不矛盾——若错误地继承了 then 的 x>100 会误报
        var f = analyze("""
              If x is greater than 100:
                Return 1.
              Otherwise:
                If x is less than 50:
                  Return 2.
                Return 3.
            """);
        assertTrue(f.isEmpty(), "else 分支不该继承 then 约束，实际: " + f);
    }

    @Test
    void 报告包含函数名与行号_便于定位() {
        var f = analyze("""
              If x is greater than 100:
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertEquals("r", f.get(0).functionName());
        assertTrue(f.get(0).line() > 0, "应有行号，实际: " + f.get(0).line());
    }

    @Test
    void 空模块与空函数体不抛异常() {
        assertTrue(RuleConflictAnalyzer.analyze(null).isEmpty());
        var f = analyze("  Return 0.\n");
        assertTrue(f.isEmpty());
    }

    @Test
    void 关键_不同变量之间不产生交叉误报() {
        // x 与 y 的约束互不影响
        var src = "Module m.\n\nRule r given x as Number, y as Number produce Number:\n"
            + "  If x is greater than 100:\n"
            + "    If y is less than 50:\n"
            + "      Return 1.\n"
            + "    Return 2.\n"
            + "  Return 0.\n";
        var f = RuleConflictAnalyzer.analyze(InProcessCnlParser.parse(src).module());
        assertTrue(f.isEmpty(), "不同变量不该交叉判定，实际: " + f);
    }

    @Test
    void 边界相等且开闭不同_正确判空() {
        // x >= 5 内层 x < 5 —— 矛盾
        var f = analyze("""
              If x is at least 5:
                If x is less than 5:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertEquals(1, f.size(), f.toString());
        assertEquals(RuleConflictAnalyzer.Finding.Kind.ALWAYS_FALSE, f.get(0).kind());
    }
}
