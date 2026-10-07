package io.aster.policy.api.model;

import io.aster.policy.governance.VerdictView;

import java.util.List;
import java.util.Objects;

/**
 * 策略评估结果，包含最终输出、耗时和缓存命中标记。
 *
 * <p>decision / ruleId / controls 为治理字段（ADR 0039）：decision 仅在结果为内置 Verdict 值时非 null；
 * ruleId / controls 来自被执行函数的 {@code @id} / {@code @control} 注解，无注解时为 null / 空列表。
 */
public class PolicyEvaluationResult {

    private final Object result;
    private final double executionTimeMs;
    private final boolean fromCache;
    private final VerdictView decision;
    private final String ruleId;
    private final List<String> controls;

    /** 不携带治理字段的构造（decision / ruleId 为 null，controls 为空）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache) {
        this(result, executionTimeMs, fromCache, null, null, List.of());
    }

    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache,
                                  VerdictView decision, String ruleId, List<String> controls) {
        this.result = result;
        this.executionTimeMs = executionTimeMs;
        this.fromCache = fromCache;
        this.decision = decision;
        this.ruleId = ruleId;
        this.controls = controls == null ? List.of() : List.copyOf(controls);
    }

    public Object getResult() {
        return result;
    }

    public double getExecutionTimeMs() {
        return executionTimeMs;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public VerdictView getDecision() {
        return decision;
    }

    public String getRuleId() {
        return ruleId;
    }

    public List<String> getControls() {
        return controls;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PolicyEvaluationResult that = (PolicyEvaluationResult) o;
        return Double.compare(that.executionTimeMs, executionTimeMs) == 0
            && fromCache == that.fromCache
            && Objects.equals(result, that.result)
            && Objects.equals(decision, that.decision)
            && Objects.equals(ruleId, that.ruleId)
            && Objects.equals(controls, that.controls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, executionTimeMs, fromCache, decision, ruleId, controls);
    }
}
