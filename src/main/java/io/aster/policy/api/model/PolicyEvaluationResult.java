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
 * <p>policyVersionId / sourceHash / sourceEnvelopeSha256 为被执行封存版本的 id、源码内容哈希与信封哈希
 * （aster-api#459）：供 /evaluate 把决策钉到版本。sourceHash 与 guard 收据同口径（内容哈希）；信封哈希
 * 旧版本为 null。结果缓存键已含 versionId，命中值与键指向同一版本；无版本时为 null 且不进入缓存 JSON。
 *
 * <p>inputRefs / verifiedInputsRequired（ADR 0046 §11.6 第 4 条）只在模块声明档案时非 null：前者是写评估事件用的
 * 证据形状路径（/evaluate 的输入是位置数组、没有匹配键，非空即 {@code ["*"]}），后者是要求项快照、只供审计。
 * 二者都不进响应契约；缓存里的旧结果缺这两键，读回为 null，事件按「旧事件」不写。
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
    private final Long policyVersionId;
    private final String sourceHash;
    private final String sourceEnvelopeSha256;
    private final List<String> inputRefs;
    private final Boolean verifiedInputsRequired;

    /** 不携带治理字段的构造（decision / ruleId 为 null，controls 为空）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache) {
        this(result, executionTimeMs, fromCache, null, null, List.of());
    }

    /** 无治理档案的构造（profile 为 null）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache,
                                  VerdictView decision, String ruleId, List<String> controls) {
        this(result, executionTimeMs, fromCache, decision, ruleId, controls, null);
    }

    /** 无版本身份的构造（policyVersionId / sourceHash 为 null）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache,
                                  VerdictView decision, String ruleId, List<String> controls, String profile) {
        this(result, executionTimeMs, fromCache, decision, ruleId, controls, profile, null, null, null);
    }

    /** 无输入引用的构造（版本身份齐全）。 */
    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache,
                                  VerdictView decision, String ruleId, List<String> controls, String profile,
                                  Long policyVersionId, String sourceHash, String sourceEnvelopeSha256) {
        this(result, executionTimeMs, fromCache, decision, ruleId, controls, profile, policyVersionId, sourceHash,
            sourceEnvelopeSha256, null, null);
    }

    @JsonCreator
    public PolicyEvaluationResult(@JsonProperty("result") Object result,
                                  @JsonProperty("executionTimeMs") double executionTimeMs,
                                  @JsonProperty("fromCache") boolean fromCache,
                                  @JsonProperty("decision") VerdictView decision,
                                  @JsonProperty("ruleId") String ruleId,
                                  @JsonProperty("controls") List<String> controls,
                                  @JsonProperty("profile") String profile,
                                  @JsonProperty("policyVersionId") Long policyVersionId,
                                  @JsonProperty("sourceHash") String sourceHash,
                                  @JsonProperty("sourceEnvelopeSha256") String sourceEnvelopeSha256,
                                  @JsonProperty("inputRefs") List<String> inputRefs,
                                  @JsonProperty("verifiedInputsRequired") Boolean verifiedInputsRequired) {
        this.result = result;
        this.executionTimeMs = executionTimeMs;
        this.fromCache = fromCache;
        this.decision = decision;
        this.ruleId = ruleId;
        this.controls = controls == null ? List.of() : List.copyOf(controls);
        this.profile = profile;
        this.policyVersionId = policyVersionId;
        this.sourceHash = sourceHash;
        this.sourceEnvelopeSha256 = sourceEnvelopeSha256;
        this.inputRefs = inputRefs == null ? null : List.copyOf(inputRefs);
        this.verifiedInputsRequired = verifiedInputsRequired;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long getPolicyVersionId() {
        return policyVersionId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSourceHash() {
        return sourceHash;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSourceEnvelopeSha256() {
        return sourceEnvelopeSha256;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getInputRefs() {
        return inputRefs;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getVerifiedInputsRequired() {
        return verifiedInputsRequired;
    }

    /** 同一结果换 fromCache 标记（其余字段原样）。 */
    public PolicyEvaluationResult withFromCache(boolean cached) {
        return new PolicyEvaluationResult(result, executionTimeMs, cached, decision, ruleId, controls, profile,
            policyVersionId, sourceHash, sourceEnvelopeSha256, inputRefs, verifiedInputsRequired);
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
            && Objects.equals(profile, that.profile)
            && Objects.equals(policyVersionId, that.policyVersionId)
            && Objects.equals(sourceHash, that.sourceHash)
            && Objects.equals(sourceEnvelopeSha256, that.sourceEnvelopeSha256)
            && Objects.equals(inputRefs, that.inputRefs)
            && Objects.equals(verifiedInputsRequired, that.verifiedInputsRequired);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, executionTimeMs, fromCache, decision, ruleId, controls, profile,
            policyVersionId, sourceHash, sourceEnvelopeSha256, inputRefs, verifiedInputsRequired);
    }
}
