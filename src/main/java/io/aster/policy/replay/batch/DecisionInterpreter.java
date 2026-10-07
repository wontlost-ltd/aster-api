package io.aster.policy.replay.batch;

import io.aster.policy.governance.VerdictView;

import java.util.Map;

/**
 * 把策略执行结果解读成决策口径（ADR 0039 §5.2）。
 *
 * <p>What-If 要比较 base 与 target 两个版本的决策，「什么算通过」必须与线上完全一致，
 * 否则比较出的「变化」可能只是解读规则的差异。因此只保留三条类型驱动的确定性规则，
 * 不做任何字符串语义猜测：
 * <ol>
 *   <li>Verdict 值（{@code __type == "Verdict"}）按 {@code outcome} 直映；</li>
 *   <li>{@code Boolean} 直映；</li>
 *   <li>{@code Map} 含 Boolean 类型的 {@code approved} 键直映
 *       （{@code Define Decision has approved, reason} 模板约定）。</li>
 * </ol>
 * 其余一律 {@link Verdict#INDETERMINATE}：它表示「这不是一个准入决策」，
 * 不参与比较，避免把值输出误当成拒绝而造出假的「变化」。
 */
public final class DecisionInterpreter {

    private DecisionInterpreter() {
    }

    public enum Verdict {
        /** 明确通过（Verdict ALLOW，或布尔/approved 为 true）。 */
        APPROVED,
        /** 明确拒绝（Verdict DENY，或布尔/approved 为 false）。 */
        DENIED,
        /** 需要人工批准：真实的非通过决策，参与比较。 */
        REQUIRE_APPROVAL,
        /** 需要上报：真实的非通过决策，参与比较。 */
        ESCALATE,
        /** 不是准入决策（值输出、未知 outcome 等），不参与比较。 */
        INDETERMINATE
    }

    /** 按三条规则解读执行结果；无法解读时返回 {@link Verdict#INDETERMINATE}。 */
    public static Verdict interpret(Object result) {
        var view = VerdictView.from(result);
        if (view.isPresent()) {
            return switch (view.get().outcome()) {
                case "ALLOW" -> Verdict.APPROVED;
                case "DENY" -> Verdict.DENIED;
                case "REQUIRE_APPROVAL" -> Verdict.REQUIRE_APPROVAL;
                case "ESCALATE" -> Verdict.ESCALATE;
                default -> Verdict.INDETERMINATE;
            };
        }
        if (result instanceof Boolean b) {
            return fromBoolean(b);
        }
        if (result instanceof Map<?, ?> m && m.get("approved") instanceof Boolean b) {
            return fromBoolean(b);
        }
        return Verdict.INDETERMINATE;
    }

    private static Verdict fromBoolean(boolean approved) {
        return approved ? Verdict.APPROVED : Verdict.DENIED;
    }
}
