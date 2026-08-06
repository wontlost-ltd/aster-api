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
import java.util.Set;
import java.util.HashSet;

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
    private static boolean walk(Block block, Map<String, ConditionInterval> constraints,
                                String fnName, List<Finding> out) {
        if (block == null || block.statements() == null) return false;
        for (Stmt s : block.statements()) {
            // ★控制流终止后，后面的语句不可达，必须停止分析。
            //
            // 否则会把不可达代码里的条件当成正常路径来判，产出**错误分类**的告警：
            //   If x > 100:
            //     Return 1.
            //     If x < 50: Return 2.   ← 从不执行，却被报「与外层矛盾、永远不成立」
            // 这类提示既没解释清楚真正的问题（这段代码根本走不到），
            // 又给业务人员制造同源噪音。不可达代码是另一类问题，
            // 该由独立的 UNREACHABLE 检查负责，不是本方法的职责。
            //
            // ★★返回「本块是否终止」而不是就地 return —— 这是第三/四/五轮
            // 反复被攻破的根因：只判**直接** Return 会漏掉「If 的两个分支都终止
            // ⇒ 整个 If 之后不可达」。把终止性做成可传播的返回值，
            // 而不是每发现一种新形态就加一个特判。
            if (s instanceof Stmt.Return) {
                return true;
            }
            if (s instanceof Stmt.If ifs) {
                if (handleIf(ifs, constraints, fnName, out)) {
                    return true;
                }
                continue;
            }
            // ★写操作必须让该变量的旧约束失效，否则会产生**误报**。
            //
            // 反例：`If x > 100: Set x to 0. If x < 50: …`
            // 外层约束 x > 100 在 `Set x to 0` 之后已经不成立，若继续沿用，
            // 内层 x < 50 会被判成与外层矛盾 → 报 ALWAYS_FALSE，而运行时内层恒真。
            //
            // 不去推算新值（那要常量传播，且 `Set x to y + 1` 之类根本算不出）——
            // 直接丢弃该变量的全部约束，退回「对 x 一无所知」。这是保守方向：
            // 少一条约束只会少报，不会错报。
            for (String w : writtenVariable(s)) {
                constraints.remove(w);
            }
            // Match 的分支条件不是 Expr 比较，需另做处理，当前不覆盖（宁可漏报）。
            // 同理不把 Match 视为终止点：保守地当作「可能不终止」，只会少报。
        }
        return false;
    }

    /**
     * 该语句写入了哪些变量——这些变量的既有区间约束必须作废。
     *
     * <p>覆盖三种绑定语句：{@code Let}（新绑定，可能遮蔽同名外层变量）、
     * {@code Set}（重赋值）、{@code Start}（async 任务名绑定）。
     *
     * <p>注意 {@code Set x to 0} 在 Core lowering 阶段会被规范化成同名 Let，
     * 且 Truffle 对同名变量复用 slot——即两者在运行时都是真实的重绑定，
     * 分析器必须一视同仁。
     *
     * <p>返回 List 而非单值，是为了将来覆盖多重绑定语句时不必改调用点。
     */
    private static List<String> writtenVariable(Stmt s) {
        if (s instanceof Stmt.Let let && let.name() != null) return List.of(let.name());
        if (s instanceof Stmt.Set set && set.name() != null) return List.of(set.name());
        if (s instanceof Stmt.Start st && st.name() != null) return List.of(st.name());
        return List.of();
    }

    /**
     * @return 这个 If **整体**是否必然终止控制流（两个分支都终止才算）。
     *         没有 else 分支时恒为 false —— 条件不成立就会走到 If 之后。
     */
    private static boolean handleIf(Stmt.If ifs, Map<String, ConditionInterval> constraints,
                                    String fnName, List<Finding> out) {
        Optional<ConditionInterval> maybe = ConditionInterval.fromComparison(ifs.cond());

        Map<String, ConditionInterval> thenConstraints = new HashMap<>(constraints);

        // 两个分支各自是否已被证明不可达。不可达时**不再往里分析**（里面的一切
        // 都走不到，继续分析只会产生一堆同源噪音），但**状态传播照做**——见下方。
        //
        //   · 条件恒假 ⇒ then 不可达
        //   · 条件恒真（被外层完全蕴含）⇒ else 不可达
        // 两者必须对称处理：只给 then 加标志是局部补丁，会在恒真那侧漏出噪音。
        boolean thenUnreachable = false;
        boolean elseUnreachable = false;

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
                    thenUnreachable = true;
                } else {
                    if (impliedBy(existing, iv)) {
                        out.add(new Finding(
                            Finding.Kind.REDUNDANT, fnName, lineOf(ifs),
                            "这个条件是多余的，外层条件（" + existing.describe()
                                + "）已经保证它成立"));
                        // 条件恒真 ⇒ Otherwise 永远走不到
                        elseUnreachable = true;
                    }
                    thenConstraints.put(iv.variable(), merged);
                }
            } else {
                thenConstraints.put(iv.variable(), iv);
            }
        }

        // 不可达的分支不分析，其终止性也不参与判断——它根本不会执行。
        boolean thenTerminates = false;
        if (!thenUnreachable) {
            thenTerminates = walk(ifs.thenBlock(), thenConstraints, fnName, out);
        }
        // else 分支的约束是条件的补集——补集通常非凸（如 !(5<x<10)），
        // 区间表示不了。故 else 分支只用外层约束继续分析，不加新约束（保守，不误报）。
        boolean elseTerminates = false;
        boolean hasElse = ifs.elseBlock() != null;
        if (!elseUnreachable) {
            elseTerminates = walk(ifs.elseBlock(), new HashMap<>(constraints), fnName, out);
        }

        // ★分支内部的写操作必须传播到 If 之后：分支执行与否在静态分析期未知，
        // 只要**任一**分支可能改写某变量，If 之后就不能再沿用它的旧约束。
        // walk 收的是副本，故这里单独扫描分支体，把被写过的变量从当前作用域移除。
        //
        // ★★即便 then 恒假也必须传播（这是第二轮审查抓到的误报根因）：
        // 「不进去分析」和「不传播状态」是两件事。then 恒假时运行时**必走 else**，
        // else 里的写操作是**确定会发生**的——反例：
        //   If x > 100:
        //     If x < 50: Return 9.        ← 恒假，正确报出
        //     Otherwise: Set x to 0.      ← 必然执行
        //     If x < 50: Return 1.        ← 实际恒真，旧代码却报恒假
        // 早退会跳过 else 遍历与下面的写失效，让后续条件沿用已失效的约束。
        for (String w : variablesWrittenIn(ifs.thenBlock())) constraints.remove(w);
        for (String w : variablesWrittenIn(ifs.elseBlock())) constraints.remove(w);

        // ★整体终止 = 两个分支都终止。缺 else 时恒为 false（条件不成立就落到 If 之后）。
        //
        // 不可达分支按「已终止」处理：then 恒假时运行时必走 else，此时 If 的
        // 终止性完全由 else 决定；反之亦然。这样 `If A: Return. Otherwise: Return.`
        // 与「A 恒真且 then 终止」都能正确判为终止，无需再加特判。
        if (thenUnreachable) return elseTerminates;
        if (elseUnreachable) return thenTerminates;
        return hasElse && thenTerminates && elseTerminates;
    }

    /**
     * 递归收集块内（含嵌套分支）所有被写入的变量名。
     *
     * <p>只关心「写了谁」，不关心写成什么——见 {@link #walk} 中丢弃约束的理由。
     */
    private static Set<String> variablesWrittenIn(Block block) {
        Set<String> acc = new HashSet<>();
        collectWrites(block, acc);
        return acc;
    }

    private static void collectWrites(Block block, Set<String> acc) {
        if (block == null || block.statements() == null) return;
        for (Stmt s : block.statements()) {
            acc.addAll(writtenVariable(s));
            // ★必须覆盖**所有**带子块的语句类型：漏掉任何一种，其中的写操作就不会
            // 让约束失效，进而产生误报。这里宁可多收集（多丢约束只会少报）。
            switch (s) {
                case Stmt.If nested -> {
                    collectWrites(nested.thenBlock(), acc);
                    collectWrites(nested.elseBlock(), acc);
                }
                case Stmt.Match m -> {
                    if (m.cases() != null) {
                        for (Stmt.Case c : m.cases()) {
                            // Case 体是 Return 或 Block（sealed CaseBody）
                            if (c != null && c.body() instanceof Block b) collectWrites(b, acc);
                        }
                    }
                }
                case Stmt.Workflow w -> {
                    if (w.steps() != null) {
                        for (Stmt.WorkflowStep st : w.steps()) {
                            if (st == null) continue;
                            collectWrites(st.body(), acc);
                            collectWrites(st.compensate(), acc);
                        }
                    }
                }
                // Stmt 允许直接嵌套 Block（见 aster.core.ast.Stmt），漏掉它
                // 同样会让其中的写操作不失效
                case Block b -> collectWrites(b, acc);
                default -> { /* 其余语句无子块 */ }
            }
        }
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
