package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.api.model.DecisionTrace;

import java.util.List;

/**
 * REST API响应：策略评估结果
 *
 * 包含评估结果、执行时间、决策追踪和可能的错误信息。
 */
public record EvaluationResponse(
    @JsonProperty("result")
    Object result,

    @JsonProperty("executionTimeMs")
    long executionTimeMs,

    @JsonProperty("error")
    String error,

    @JsonProperty("decisionTrace")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    DecisionTrace decisionTrace,

    @JsonProperty("executedFunction")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String executedFunction,

    @JsonProperty("diagnostics")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<EntryDiagnostic> diagnostics,

    // 回放元数据（ADR 0030 附录 A）——仅 replay-capture 模式产出（否则 null）。cloud BFF 拿到
    // 后写 Execution 新列（runtimeToolchainId/canonical*Hash/traceHash/...）。权威 hash 由本侧
    // （Java 评估侧）计算，cloud 只存储。
    @JsonProperty("replayMetadata")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    io.aster.policy.replay.ReplayMetadata replayMetadata,

    // 决策骨架：DecisionTrace 的**脱敏**投影（只有 expression/matched，无任何值）。
    // 供 cloud 落库后做"条件漏斗 / 死分支"聚合分析。
    //
    // ★与 decisionTrace 的区别：后者含 result 业务值，只在 trace=true 时回传给调用方；
    // 骨架**不含任何值**，故可对所有租户常态采集，不受 replayRetentionEnabled（默认关）
    // 门控——这正是它存在的意义：用零 PII 成本换取分析能力。
    // 详见 TraceSkeleton 类注释。
    @JsonProperty("traceSkeleton")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    io.aster.policy.replay.TraceSkeleton traceSkeleton
) {
    public record EntryDiagnostic(
        String code,
        String message,
        List<String> candidates
    ) {}

    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null, null, null, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, null, executedFunction, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, DecisionTrace trace) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, null, null, null, null);
    }

    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, executedFunction, null, null, null);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error, null, null, null, null, null);
    }

    public static EvaluationResponse ambiguous(List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(
            "ENTRY_AMBIGUOUS",
            "未指定入口函数，候选函数不唯一",
            safeCandidates
        );
        return new EvaluationResponse(null, 0, "入口函数不唯一", null, null, List.of(diagnostic), null, null);
    }

    public static EvaluationResponse diagnostic(String code, String message, List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(code, message, safeCandidates);
        return new EvaluationResponse(null, 0, message, null, null, List.of(diagnostic), null, null);
    }

    /** 附加回放元数据（replay-capture 模式；不改其它字段）。 */
    public EvaluationResponse withReplayMetadata(io.aster.policy.replay.ReplayMetadata rm) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, rm, traceSkeleton);
    }

    /** 附加决策骨架（脱敏，供漏斗分析；不改其它字段）。 */
    public EvaluationResponse withTraceSkeleton(io.aster.policy.replay.TraceSkeleton skeleton) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, replayMetadata, skeleton);
    }
}
