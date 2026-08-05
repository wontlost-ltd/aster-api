package io.aster.policy.analysis;

import aster.core.ast.Expr;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 单个数值比较条件的区间表示：{@code variable ∈ [lo, hi]}。
 *
 * <p>用于规则冲突/死规则静态检测（Phase 2）——把
 * {@code Call[target=Name[">"], args=[Name[x], Int[100]]]} 这类 AST 节点
 * 规约成可求交、可判空的区间，从而回答：
 * <ul>
 *   <li>两个条件能否同时成立（区间相交 → 可能冲突）</li>
 *   <li>某条件是否恒假（区间为空 → 死规则，如 {@code x > 100 且 x < 50}）</li>
 * </ul>
 *
 * <p><b>为什么用区间而不上 SMT</b>：CNL 的业务条件绝大多数是「字段 op 常量」
 * 的合取，区间算术足够且**可解释**——能直接告诉用户"这两条规则在 x∈(100,200)
 * 上都成立"。引入 SMT 会带来求解器依赖、超时处理与不可解释的反例，
 * 与"给业务人员看"的目标相悖。
 *
 * <p>边界用 {@code loInclusive}/{@code hiInclusive} 表达，因为
 * {@code >} 与 {@code >=} 的区别在业务上是实打实的（"满 700 分"vs"超过 700 分"）。
 *
 * <p>无界侧用 null 表示（非 ±Infinity）：BigDecimal 无无穷值，且 null 语义更明确。
 */
public record ConditionInterval(
    /** 被比较的变量名（如 {@code x} / {@code creditScore}）。 */
    String variable,
    /** 下界，null=负无穷。 */
    BigDecimal lo,
    boolean loInclusive,
    /** 上界，null=正无穷。 */
    BigDecimal hi,
    boolean hiInclusive
) {

    /** 全集（无约束）。 */
    public static ConditionInterval unbounded(String variable) {
        return new ConditionInterval(variable, null, false, null, false);
    }

    /**
     * 从 AST 的比较表达式抽取区间。
     *
     * <p>只识别「变量 op 数值常量」与其镜像「数值常量 op 变量」——
     * 后者要把运算符翻转（{@code 100 < x} 等价于 {@code x > 100}）。
     * 其它形态（变量比变量、函数调用、字符串比较）返回 empty：
     * <b>不猜</b>，宁可漏报也不误报——误报会让业务人员不信任整个检测器。
     */
    public static Optional<ConditionInterval> fromComparison(Expr expr) {
        if (!(expr instanceof Expr.Call call)) return Optional.empty();
        if (!(call.target() instanceof Expr.Name op)) return Optional.empty();
        if (call.args() == null || call.args().size() != 2) return Optional.empty();

        Expr left = call.args().get(0);
        Expr right = call.args().get(1);

        String opName = op.name();
        // 变量在左：x > 100
        Optional<String> lname = nameOf(left);
        Optional<BigDecimal> rnum = numberOf(right);
        if (lname.isPresent() && rnum.isPresent()) {
            return build(lname.get(), opName, rnum.get());
        }
        // 变量在右：100 < x —— 翻转运算符
        Optional<BigDecimal> lnum = numberOf(left);
        Optional<String> rname = nameOf(right);
        if (lnum.isPresent() && rname.isPresent()) {
            return build(rname.get(), mirror(opName), lnum.get());
        }
        return Optional.empty();
    }

    /** 运算符镜像：{@code 100 < x} ⇔ {@code x > 100}。 */
    private static String mirror(String op) {
        return switch (op) {
            case "<" -> ">";
            case ">" -> "<";
            case "<=" -> ">=";
            case ">=" -> "<=";
            default -> op; // == / != 对称
        };
    }

    private static Optional<ConditionInterval> build(String var, String op, BigDecimal v) {
        return switch (op) {
            case ">" -> Optional.of(new ConditionInterval(var, v, false, null, false));
            case ">=" -> Optional.of(new ConditionInterval(var, v, true, null, false));
            case "<" -> Optional.of(new ConditionInterval(var, null, false, v, false));
            case "<=" -> Optional.of(new ConditionInterval(var, null, false, v, true));
            // 相等：退化为单点区间 [v, v]
            case "==" -> Optional.of(new ConditionInterval(var, v, true, v, true));
            // != 不是区间（是区间的补，非凸），不处理——见类注释「不猜」原则
            default -> Optional.empty();
        };
    }

    private static Optional<String> nameOf(Expr e) {
        return e instanceof Expr.Name n ? Optional.of(n.name()) : Optional.empty();
    }

    /** 支持 Int / Long / Double / Decimal 四种数值字面量。 */
    private static Optional<BigDecimal> numberOf(Expr e) {
        if (e instanceof Expr.Int i) return Optional.of(new BigDecimal(i.value()));
        if (e instanceof Expr.Long l) return Optional.of(BigDecimal.valueOf(l.value()));
        if (e instanceof Expr.Double d) return Optional.of(BigDecimal.valueOf(d.value()));
        if (e instanceof Expr.Decimal d) return Optional.of(new BigDecimal(d.value()));
        return Optional.empty();
    }

    /**
     * 与另一区间求交。变量不同时返回 empty——不同变量的约束互不影响，
     * 调用方应按变量分组后再求交。
     */
    public Optional<ConditionInterval> intersect(ConditionInterval other) {
        if (!variable.equals(other.variable)) return Optional.empty();

        BigDecimal newLo = lo;
        boolean newLoInc = loInclusive;
        if (other.lo != null && (lo == null || other.lo.compareTo(lo) > 0
                || (other.lo.compareTo(lo) == 0 && !other.loInclusive))) {
            newLo = other.lo;
            newLoInc = other.loInclusive;
        }

        BigDecimal newHi = hi;
        boolean newHiInc = hiInclusive;
        if (other.hi != null && (hi == null || other.hi.compareTo(hi) < 0
                || (other.hi.compareTo(hi) == 0 && !other.hiInclusive))) {
            newHi = other.hi;
            newHiInc = other.hiInclusive;
        }

        return Optional.of(new ConditionInterval(variable, newLo, newLoInc, newHi, newHiInc));
    }

    /**
     * 区间是否为空（恒假）。
     *
     * <p>两种情况：lo > hi；或 lo == hi 但任一端开区间（如 {@code x > 5 且 x < 5}，
     * 甚至 {@code x >= 5 且 x < 5}）。
     */
    public boolean isEmpty() {
        if (lo == null || hi == null) return false;
        int cmp = lo.compareTo(hi);
        if (cmp > 0) return true;
        return cmp == 0 && !(loInclusive && hiInclusive);
    }

    /** 人类可读描述，用于向业务人员解释检测结果。 */
    public String describe() {
        if (lo == null && hi == null) return variable + " 无约束";
        StringBuilder sb = new StringBuilder();
        if (lo != null) sb.append(lo.toPlainString()).append(loInclusive ? " ≤ " : " < ");
        sb.append(variable);
        if (hi != null) sb.append(hiInclusive ? " ≤ " : " < ").append(hi.toPlainString());
        return sb.toString();
    }
}
