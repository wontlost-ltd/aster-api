package io.aster.policy.analysis;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Stmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则冲突与死规则静态检测（Phase 2）。
 *
 * <p>不需要任何执行数据——纯 AST 分析，故对**所有租户**立即可用，
 * 不受 {@code replayRetentionEnabled} 或骨架采集覆盖率影响。
 *
 * <p>当前检测两类问题：
 * <ul>
 *   <li><b>恒假条件</b>（{@link Finding.Kind#ALWAYS_FALSE}）：嵌套 If 的条件与
 *       外层条件求交后为空，如 {@code If x > 100: If x < 50: ...} —— 内层永远走不到</li>
 *   <li><b>恒真嵌套</b>（{@link Finding.Kind#REDUNDANT}）：内层条件被外层完全蕴含，
 *       如 {@code If x > 100: If x > 50: ...} —— 内层判断多余</li>
 * </ul>
 *
 * <p><b>★设计原则：宁可漏报，不可误报。</b>无法规约成区间的条件（变量比变量、
 * 字符串比较、函数调用、{@code !=}）一律跳过，不做任何猜测。业务人员对
 * 静态检查的信任极其脆弱——一次误报就会让整个功能被忽略，而漏报只是少给了
 * 一条提示。
 *
 * <p><b>不做的事</b>：跨 Rule 的冲突检测（需要知道哪些 Rule 会被同时评估，
 * 那是调用方的编排问题，AST 层面无从判断）。本类只在**单个 Rule 内**分析
 * 嵌套 If 的条件链。
 */
public final class RuleConflictAnalyzer {

    private RuleConflictAnalyzer() {}

    /** 一条检测结果。 */
    public record Finding(
        Kind kind,
        /** 所在 Rule 名。 */
        String functionName,
        /** 出问题的条件原文位置（行号，1 起）。 */
        int line,
        /** 人类可读说明——直接展示给业务人员，故用自然语言而非术语。 */
        String message
    ) {
        public enum Kind {
            /** 条件与外层约束矛盾，该分支永远不会执行。 */
            ALWAYS_FALSE,
            /** 条件被外层完全蕴含，判断是多余的。 */
            REDUNDANT
        }
    }

    /** 分析整个模块。 */
    public static List<Finding> analyze(Module module) {
        List<Finding> out = new ArrayList<>();
        if (module == null || module.decls() == null) return out;
        for (Decl d : module.decls()) {
            if (d instanceof Decl.Func f && f.body() != null) {
                // 初始约束为空——顶层 If 没有外层限制
                walk(f.body(), new HashMap<>(), f.name(), out);
            }
        }
        return out;
    }

    /**
     * 遍历语句块，维护「当前路径上已成立的区间约束」。
     *
     * <p>{@code constraints} 按变量名分组：进入 then 分支时把该条件并入约束，
     * 退出时恢复——用值传递（每层复制）而非回溯，代码更简单且不会漏恢复。
     */
    private static void walk(Block block, Map<String, ConditionInterval> constraints,
                             String fnName, List<Finding> out) {
        if (block == null || block.statements() == null) return;
        for (Stmt s : block.statements()) {
            if (s instanceof Stmt.If ifs) {
                handleIf(ifs, constraints, fnName, out);
            }
            // 其它语句类型（Let/Return/Match/…）不影响区间约束，跳过。
            // Match 的分支条件不是 Expr 比较，需另做处理，当前不覆盖（宁可漏报）。
        }
    }

    private static void handleIf(Stmt.If ifs, Map<String, ConditionInterval> constraints,
                                 String fnName, List<Finding> out) {
        Optional<ConditionInterval> maybe = ConditionInterval.fromComparison(ifs.cond());

        Map<String, ConditionInterval> thenConstraints = new HashMap<>(constraints);

        if (maybe.isPresent()) {
            ConditionInterval iv = maybe.get();
            ConditionInterval existing = constraints.get(iv.variable());

            if (existing != null) {
                ConditionInterval merged = existing.intersect(iv).orElse(iv);
                if (merged.isEmpty()) {
                    out.add(new Finding(
                        Finding.Kind.ALWAYS_FALSE, fnName, lineOf(ifs),
                        "这个条件与外层条件矛盾，永远不会成立（外层已限定 "
                            + existing.describe() + "）"));
                    // 矛盾分支内部不再往下分析——里面的一切都是不可达的，
                    // 继续分析只会产生一堆同源的噪音提示。
                    return;
                }
                if (impliedBy(existing, iv)) {
                    out.add(new Finding(
                        Finding.Kind.REDUNDANT, fnName, lineOf(ifs),
                        "这个条件是多余的，外层条件（" + existing.describe()
                            + "）已经保证它成立"));
                }
                thenConstraints.put(iv.variable(), merged);
            } else {
                thenConstraints.put(iv.variable(), iv);
            }
        }

        walk(ifs.thenBlock(), thenConstraints, fnName, out);
        // else 分支的约束是条件的补集——补集通常非凸（如 !(5<x<10)），
        // 区间表示不了。故 else 分支只用外层约束继续分析，不加新约束（保守，不误报）。
        walk(ifs.elseBlock(), constraints, fnName, out);
    }

    /**
     * outer 是否蕴含 inner（outer ⊆ inner）——即 inner 在 outer 成立时恒真。
     *
     * <p>只在两侧都有对应边界时才判定；任一侧无界则保守返回 false（不报多余）。
     */
    private static boolean impliedBy(ConditionInterval outer, ConditionInterval inner) {
        // inner 有下界时，outer 的下界必须不低于它
        if (inner.lo() != null) {
            if (outer.lo() == null) return false;
            int c = outer.lo().compareTo(inner.lo());
            if (c < 0) return false;
            if (c == 0 && !inner.loInclusive() && outer.loInclusive()) return false;
        }
        if (inner.hi() != null) {
            if (outer.hi() == null) return false;
            int c = outer.hi().compareTo(inner.hi());
            if (c > 0) return false;
            if (c == 0 && !inner.hiInclusive() && outer.hiInclusive()) return false;
        }
        // inner 完全无界时不算"多余"——那种写法本就不是比较条件
        return inner.lo() != null || inner.hi() != null;
    }

    private static int lineOf(Stmt.If ifs) {
        try {
            return ifs.span() != null && ifs.span().start() != null
                ? ifs.span().start().line() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
