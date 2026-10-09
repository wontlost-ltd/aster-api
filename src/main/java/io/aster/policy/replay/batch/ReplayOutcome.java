package io.aster.policy.replay.batch;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What-If 的统一结论码（ADR 0043 §2）：与 cloud Execution.outcome 同一套大写码。
 *
 * <p>目标侧由 {@link DecisionInterpreter.Verdict} 直映；基线侧由 cloud 窗口行折算。
 * 枚举保持纯函数，未知 outcome 的告警日志由调用方负责。
 */
public enum ReplayOutcome {
    ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE, ERROR;

    /** 参与前后比较的四个真实决策态。 */
    private static final Set<ReplayOutcome> COMPARABLE = EnumSet.of(ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE);

    public boolean comparable() {
        return COMPARABLE.contains(this);
    }

    /** 目标侧映射：Verdict 五值一一对应。 */
    public static ReplayOutcome fromVerdict(DecisionInterpreter.Verdict v) {
        return switch (v) {
            case APPROVED -> ALLOW;
            case DENIED -> DENY;
            case REQUIRE_APPROVAL -> REQUIRE_APPROVAL;
            case ESCALATE -> ESCALATE;
            case INDETERMINATE -> INDETERMINATE;
        };
    }

    /** 基线折算结果：outcome 与是否来自折算（legacy）。 */
    public record Base(ReplayOutcome outcome, boolean legacy) {
    }

    /** 基线折算：outcome 列优先；缺失时按 decision（小写六值，忽略大小写），再缺按 success。 */
    public static Base fromWindow(String outcome, String decision, boolean success) {
        if (outcome != null && !outcome.isBlank()) {
            return new Base(parse(outcome.trim()).orElse(ERROR), false);
        }
        if (decision != null && !decision.isBlank()) {
            return new Base(fromLegacyDecision(decision.trim().toLowerCase(Locale.ROOT)), true);
        }
        return new Base(success ? ALLOW : DENY, true);
    }

    /** 转移键，形如 "ALLOW->DENY"。 */
    public static String transitionKey(ReplayOutcome base, ReplayOutcome target) {
        return base.name() + "->" + target.name();
    }

    private static ReplayOutcome fromLegacyDecision(String decision) {
        return switch (decision) {
            case "approved" -> ALLOW;
            case "denied" -> DENY;
            case "require_approval" -> REQUIRE_APPROVAL;
            case "escalate" -> ESCALATE;
            case "indeterminate" -> INDETERMINATE;
            default -> ERROR;
        };
    }

    private static Optional<ReplayOutcome> parse(String s) {
        try {
            return Optional.of(valueOf(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
