package io.aster.policy.analysis;

import io.aster.policy.parser.InProcessCnlParser;
import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Stmt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间抽取与求交测试（Phase 2 地基）。
 *
 * <p>★用**真实 parser** 产出的 AST，不手工构造 Expr 节点——手构的节点很容易
 * 与真实形态不符（例如误以为比较是独立的 BinaryExpr，实际是
 * {@code Call[target=Name[">"]]}），那样测试全绿而生产全错。
 */
class ConditionIntervalTest {

    /** 解析一段 CNL，取出第一个 If 的条件表达式。 */
    private static Expr firstIfCond(String condition) {
        String src = "Module m.\n\nRule r given x as Number produce Number:\n"
            + "  If " + condition + ":\n    Return 1.\n  Return 0.\n";
        var res = InProcessCnlParser.parse(src);
        for (Decl d : res.module().decls()) {
            if (d instanceof Decl.Func f) {
                for (Stmt s : f.body().statements()) {
                    if (s instanceof Stmt.If ifs) return ifs.cond();
                }
            }
        }
        throw new IllegalStateException("未找到 If 条件: " + condition);
    }

    private static ConditionInterval parseInterval(String condition) {
        return ConditionInterval.fromComparison(firstIfCond(condition)).orElseThrow(
            () -> new AssertionError("未能从条件抽取区间: " + condition));
    }

    @Test
    void 大于_抽取为开下界() {
        var iv = parseInterval("x is greater than 100");
        assertEquals("x", iv.variable());
        assertEquals(new BigDecimal("100"), iv.lo());
        assertFalse(iv.loInclusive());
        assertEquals(null, iv.hi());
    }

    @Test
    void 至少_抽取为闭下界() {
        var iv = parseInterval("x is at least 700");
        assertEquals(new BigDecimal("700"), iv.lo());
        assertTrue(iv.loInclusive(), "`is at least` 应是闭区间——业务上「满 700」含 700");
    }

    @Test
    void 小于_抽取为开上界() {
        var iv = parseInterval("x is less than 50");
        assertEquals(new BigDecimal("50"), iv.hi());
        assertFalse(iv.hiInclusive());
        assertEquals(null, iv.lo());
    }

    @Test
    void 关键_恒假条件被判空_这是死规则检测的核心() {
        // x > 100 且 x < 50 —— 永远不可能同时成立
        var a = parseInterval("x is greater than 100");
        var b = parseInterval("x is less than 50");
        var merged = a.intersect(b).orElseThrow();
        assertTrue(merged.isEmpty(), "x>100 ∧ x<50 必须判为恒假，实际: " + merged.describe());
    }

    @Test
    void 关键_可满足条件不被误判为空_避免假阳性() {
        // x > 100 且 x < 200 —— 在 (100,200) 上成立
        var merged = parseInterval("x is greater than 100")
            .intersect(parseInterval("x is less than 200")).orElseThrow();
        assertFalse(merged.isEmpty(), "x>100 ∧ x<200 是可满足的，误报会让人不信任检测器");
        assertEquals(new BigDecimal("100"), merged.lo());
        assertEquals(new BigDecimal("200"), merged.hi());
    }

    @Test
    void 关键_边界相接但开区间_判空() {
        // x > 5 且 x < 5 —— 空
        var merged = parseInterval("x is greater than 5")
            .intersect(parseInterval("x is less than 5")).orElseThrow();
        assertTrue(merged.isEmpty());
    }

    @Test
    void 边界相接且两端闭_不判空_单点可满足() {
        // x >= 5 且 x <= 5 —— 单点 {5}，可满足
        var merged = parseInterval("x is at least 5")
            .intersect(parseInterval("x is at most 5")).orElseThrow();
        assertFalse(merged.isEmpty(), "x∈{5} 是可满足的");
    }

    @Test
    void 求交取更紧的界() {
        // x > 100 ∧ x > 200 ⇒ x > 200
        var merged = parseInterval("x is greater than 100")
            .intersect(parseInterval("x is greater than 200")).orElseThrow();
        assertEquals(new BigDecimal("200"), merged.lo());
    }

    @Test
    void 不同变量不求交_调用方须先分组() {
        var x = ConditionInterval.unbounded("x");
        var y = ConditionInterval.unbounded("y");
        assertTrue(x.intersect(y).isEmpty());
    }

    @Test
    void 关键_无法识别的条件返回empty_不猜() {
        // 变量比变量：没有常量边界，无法规约成区间 —— 必须返回 empty，
        // 而不是瞎猜一个界。误报会让业务人员不信任整个检测器。
        assertEquals(Optional.empty(),
            ConditionInterval.fromComparison(firstIfCond("x is greater than y")),
            "变量比变量无法规约成区间，必须返回 empty");

        // 非比较表达式（布尔字面量）同样不该被当成区间
        assertEquals(Optional.empty(),
            ConditionInterval.fromComparison(new Expr.Bool(true, null)));
    }

    @Test
    void describe_产出人类可读文本_供向业务人员解释() {
        var merged = parseInterval("x is greater than 100")
            .intersect(parseInterval("x is less than 200")).orElseThrow();
        String d = merged.describe();
        assertTrue(d.contains("100") && d.contains("200") && d.contains("x"), d);
    }

    @Test
    void 空_steps_安全性_unbounded不为空() {
        assertFalse(ConditionInterval.unbounded("x").isEmpty());
    }
}
