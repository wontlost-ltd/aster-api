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

    // ★以下三组来自 Codex 交叉审查复现的**真实误报**（P0/发布阻断）。
    // 每一条都曾让分析器对实际可达的分支报 ALWAYS_FALSE——直接击穿
    // 「宁可漏报，不可误报」这条本类最重要的承诺。

    @Test
    void 关键_Set重绑定后不得沿用旧约束() {
        // 运行时：x 被改成 0，内层 x < 50 恒真。分析器若沿用外层 x > 100 会误报。
        var f = analyze("""
              If x is greater than 100:
                Set x to 0.
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "Set 重绑定后不得报任何冲突，实际: " + f);
    }

    @Test
    void 关键_分支内的写操作要传播到分支之后() {
        // then 分支改写了 x，If 之后就不能再沿用 x 的旧约束。
        var f = analyze("""
              If x is greater than 100:
                Set x to 0.
                Return 1.
              If x is less than 50:
                Return 2.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "分支内写操作须使后续约束失效，实际: " + f);
    }

    @Test
    void 关键_超出double安全整数范围一律放弃分析() {
        // 生产运行时对非 Decimal 数值统一转 double 比较，2^53 以上相邻整数会折叠成
        // 同一个值：9007199254740992 与 ...93 在运行时相等，内层分支实际可达。
        // 本类用精确 BigDecimal，若不放弃分析就会与运行时结论相反。
        var f = analyze("""
              If x is equal to 9007199254740992L:
                If x is equal to 9007199254740993L:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertTrue(f.isEmpty(), "超安全整数范围须放弃分析（宁可漏报），实际: " + f);
    }

    @Test
    void 安全整数范围内的矛盾仍要能报出() {
        // 放弃分析只针对超范围值，不能因此丧失正常量级的检测能力。
        var f = analyze("""
              If x is equal to 100:
                If x is equal to 200:
                  Return 1.
                Return 2.
              Return 0.
            """);
        assertEquals(1, f.size(), "安全范围内的矛盾仍应报出，实际: " + f);
        assertEquals(RuleConflictAnalyzer.Finding.Kind.ALWAYS_FALSE, f.get(0).kind());
    }

    // ★第二轮审查复现的误报：恒假分支早退，跳过了 else 的写传播。
    @Test
    void 关键_then恒假时else的写仍须失效约束() {
        // 运行时 x=101 必走 else 把 x 设为 0，故 line 9 的 x < 50 恒真。
        // 旧实现在 line 5 判恒假后直接 return，跳过 else 遍历与写失效，
        // 导致 line 9 沿用已失效的 x > 100 被误报为 ALWAYS_FALSE。
        var f = analyze("""
              If x is greater than 100:
                If x is less than 50:
                  Return 9.
                Otherwise:
                  Set x to 0.
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """);
        // line 5 那条是**真**恒假，应当报出；但只应有这一条。
        assertEquals(1, f.size(), "只应报内层真恒假那一条，实际: " + f);
        assertEquals(5, f.get(0).line(), "报错行应为真正恒假的那行，实际: " + f);
    }

    // Match / Workflow 分支内写操作的失效逻辑已在 collectWrites 中实现（见该方法
    // 的 switch），但**尚无 CNL 层测试覆盖**——本仓 Match 的具体表面语法未确认，
    // 用错语法只会得到 parse 错误而非有效断言。这是**已知的测试缺口**，
    // 不是「已验证通过」。补测需先确认 Match 的 CNL 写法。
}
