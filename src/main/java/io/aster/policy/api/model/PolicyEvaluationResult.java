package io.aster.policy.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.governance.VerdictView;

import java.util.List;
import java.util.Objects;

/**
 * 策略评估结果，包含最终输出、耗时和缓存命中标记。
 *
 * <p>decision / ruleId / controls 为治理字段（ADR 0039）：decision 仅在结果为内置 Verdict 值时非 null；
 * ruleId / controls 来自被执行函数的 {@code @id} / {@code @control} 注解，无注解时为 null / 空列表。
 * profile 为模块声明的治理档案 id（ADR 0046 §6），未声明时为 null 且不进入缓存 JSON。
 *
 * <p>本类会作为 {@code policy-results} 缓存值写入 Redis（生产默认缓存后端）并在命中时按 JSON 还原。
 * 类有两个多参构造器，Jackson 无法自行推断用哪个创建实例，命中时会报 “no Creators” 解码失败，
 * 导致相同请求第二次评估失败（Action Guard 降级为 DENY evaluation_failed）。因此在全参构造器上显式声明
 * {@link JsonCreator}；同时忽略未知字段，保证滚动发布期间旧实例读到新版本写入的字段时不失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyEvaluationResult {

    private final Object result;
    private final double executionTimeMs;
    private final boolean fromCache;
    private final VerdictView decision;
    private final String ruleId;
    private final List<String> controls;
    private final String profile;

    /** 不携带治理字段的构造（decision / ruleId 为 null，controls 为空）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache) {
        this(result, executionTimeMs, fromCache, null, null, List.of());
    }

    /** 无治理档案的构造（profile 为 null）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache,
                                  VerdictView decision, String ruleId, List<String> controls) {
        this(result, executionTimeMs, fromCache, decision, ruleId, controls, null);
    }

    @JsonCreator
    public PolicyEvaluationResult(@JsonProperty("result") Object result,
                                  @JsonProperty("executionTimeMs") double executionTimeMs,
                                  @JsonProperty("fromCache") boolean fromCache,
                                  @JsonProperty("decision") VerdictView decision,
                                  @JsonProperty("ruleId") String ruleId,
                                  @JsonProperty("controls") List<String> controls,
                                  @JsonProperty("profile") String profile) {
        this.result = result;
        this.executionTimeMs = executionTimeMs;
        this.fromCache = fromCache;
        this.decision = decision;
        this.ruleId = ruleId;
        this.controls = controls == null ? List.of() : List.copyOf(controls);
        this.profile = profile;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getProfile() {
        return profile;
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
            && Objects.equals(controls, that.controls)
            && Objects.equals(profile, that.profile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, executionTimeMs, fromCache, decision, ruleId, controls, profile);
    }
}
