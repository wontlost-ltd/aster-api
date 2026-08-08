package io.aster.policy.replay.batch;

import java.util.Locale;
import java.util.Map;

/**
 * 把策略执行结果解读成「是否通过」（ADR 0034）。
 *
 * <p><b>为什么单独抽一个类</b>：What-If 要比较 base 与 target 两个版本的决策，
 * 而「什么算通过」必须与线上口径**完全一致**——否则比较出来的「变化」
 * 可能只是两套解读规则的差异，而不是策略本身的变化。
 *
 * <p>那会是最糟的一种错误：数字看起来合理，实际测量的是错的东西。
 *
 * <p>★三态而非二态：{@link Verdict#INDETERMINATE} 表示「这不是一个准入决策」
 * （如策略返回一个数字或文本）。把它当成「拒绝」会凭空造出大量「变化」——
 * 本仓在 executions 表上踩过同样的坑（见 {@code decision} 列注释：
 * 值输出被 {@code success=false} 误记成失败）。
 */
public final class DecisionInterpreter {

    private DecisionInterpreter() {
    }

    public enum Verdict {
        /** 明确通过。 */
        APPROVED,
        /** 明确拒绝。 */
        DENIED,
        /**
         * 不是准入决策（值输出、计算结果等）。
         *
         * <p>★这类执行**不参与** approved/denied 的比较——
         * 它们既不是通过也不是拒绝，强行归入任一边都会造出假的「变化」。
         */
        INDETERMINATE
    }

    /**
     * 解读执行结果。
     *
     * <p>支持的形态与线上一致：
     * <ul>
     *   <li>{@code Boolean} —— true=通过</li>
     *   <li>{@code String} —— "APPROVED"/"true" 通过，"REJECTED"/"DENIED"/"false" 拒绝</li>
     *   <li>{@code Map} —— 读 {@code approved} / {@code decision} 键</li>
     *   <li>带 {@code isApproved()} 的对象 —— 反射读取</li>
     * </ul>
     * 其余一律 {@link Verdict#INDETERMINATE}——★<b>不猜</b>。
     */
    public static Verdict interpret(Object result) {
        if (result == null) {
            return Verdict.INDETERMINATE;
        }
        if (result instanceof Boolean b) {
            return b ? Verdict.APPROVED : Verdict.DENIED;
        }
        if (result instanceof String s) {
            return fromString(s);
        }
        if (result instanceof Map<?, ?> m) {
            Object approved = m.get("approved");
            if (approved instanceof Boolean b) {
                return b ? Verdict.APPROVED : Verdict.DENIED;
            }
            Object decision = m.get("decision");
            if (decision instanceof String s) {
                return fromString(s);
            }
            return Verdict.INDETERMINATE;
        }
        try {
            var method = result.getClass().getMethod("isApproved");
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                Object v = method.invoke(result);
                if (v instanceof Boolean b) {
                    return b ? Verdict.APPROVED : Verdict.DENIED;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // 没有这个方法：不是准入结果，落 INDETERMINATE
        }
        return Verdict.INDETERMINATE;
    }

    private static Verdict fromString(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("approved".equals(s) || "true".equals(s) || "approve".equals(s)) {
            return Verdict.APPROVED;
        }
        if ("rejected".equals(s) || "denied".equals(s) || "false".equals(s) || "deny".equals(s)) {
            return Verdict.DENIED;
        }
        // ★任意其他字符串是**值输出**（如 "Hello"、"42"），不是决策
        return Verdict.INDETERMINATE;
    }
}
