package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.governance.VerdictView;

import java.util.List;

/**
 * REST API响应：策略评估结果
 *
 * 包含评估结果、执行时间、决策追踪和可能的错误信息。
 */
/*
 * ★字段序显式钉成字母序，不随 Jackson 默认值漂移。
 *
 * Quarkus 3.38 → 3.39 升级时，record 的序列化顺序由「字母序」变成了「声明序」，
 * 导致 PolicyEvaluationReplayOrderingTest 的 golden 逐字节断言失败——
 * **所有值都相同，只有字段排列变了**。
 *
 * 该测试有意做字节级断言（防「悄悄丢字段/改字段名」的协议漂移）。若每次升级都去改
 * 测试，等于把「现状契约」的定义权交给依赖的默认值；显式注解才能让「字段序变了」
 * 成为一次**有意的决定**，而非升级副作用。
 *
 * ★注意：MapperFeature.SORT_PROPERTIES_ALPHABETICALLY 对 record **不生效**
 * （实测加了 ObjectMapperCustomizer 后测试依然失败），必须用本注解。
 *
 * ★影响范围仅限本响应信封的序列化。replay 的 canonical 哈希
 * （canonicalInputHash / canonicalOutputHash / traceHash）由 canonical-json 自行
 * 规范化、不依赖字段序——3.39 升级中三个哈希均逐字未变，已实测确认。
 */
@JsonPropertyOrder(alphabetic = true)
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
    io.aster.policy.replay.TraceSkeleton traceSkeleton,

    // 治理结论（ADR 0039 §5.1）：仅当结果是内置 Verdict 值时非 null；否则不输出该字段，
    // 保持非 Verdict 策略的既有响应字节不变。
    @JsonProperty("decision")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    VerdictView decision,

    // 被执行规则的 @id（ADR 0039 §3）；无注解时不输出。
    @JsonProperty("ruleId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String ruleId,

    // 被执行规则的 @control 列表（ADR 0039 §3），保持声明顺序；空列表时不输出。
    @JsonProperty("controls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<String> controls,

    // 证据相关键（ADR 0041 §2.3）：用它向 GET /api/v1/audit/receipts 取链收据；simulate 模式不输出
    @JsonProperty("evidence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Evidence evidence,

    // 被执行模块声明的治理档案 id（ADR 0046 §6）；未声明时不输出，保持既有响应字节不变
    @JsonProperty("profile")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String profile,

    // 失败时的机器可读错误码（no_executable_version / policy_not_found / function_not_found /
    // invalid_request / evaluation_failed …），与 HTTP 状态一一对应；error 文本保留不变，老客户端照常解析。
    // 成功响应不输出，保持既有响应字节不变。
    @JsonProperty("code")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String code,

    // 输入缺字段（code=missing_input_field）时缺失字段相对调用方输入的路径，如 applicant.creditScore；
    // 其余响应不输出，保持既有响应字节不变。
    @JsonProperty("field")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String field
) {
    /** 证据引用：correlationId 与本次评估在审计链上的 POLICY_EVALUATION 事件一一对应，只做关联，不含业务数据。 */
    public record Evidence(@JsonProperty("correlationId") String correlationId) {}

    public record EntryDiagnostic(
        String code,
        String message,
        List<String> candidates
    ) {}

    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null, null, null, null, null, null, null, null, List.of(), null, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, null, executedFunction, null, null, null, null, null, List.of(), null, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, DecisionTrace trace) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, null, null, null, null, null, null, List.of(), null, null, null, null);
    }

    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, executedFunction, null, null, null, null, null, List.of(), null, null, null, null);
    }

    /** 携带治理字段（decision / ruleId / controls，ADR 0039）的成功响应。 */
    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction,
            VerdictView decision, String ruleId, List<String> controls) {
        return success(result, executionTimeMs, trace, executedFunction, decision, ruleId, controls, null);
    }

    /** 另携带模块声明的治理档案（ADR 0046 §6）。 */
    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction,
            VerdictView decision, String ruleId, List<String> controls, String profile) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, executedFunction, null, null, null,
            decision, ruleId, controls == null ? List.of() : List.copyOf(controls), null, profile, null, null);
    }

    /** 带错误码的失败响应：error 为说明文本（与旧响应同一字段），code 为机器可读错误码。 */
    public static EvaluationResponse failure(String code, String error, long executionTimeMs) {
        return failure(code, error, executionTimeMs, null);
    }

    /** 同上，另带字段路径（输入缺字段 missing_input_field 时非 null）。 */
    public static EvaluationResponse failure(String code, String error, long executionTimeMs, String field) {
        return new EvaluationResponse(null, executionTimeMs, error, null, null, null, null, null, null, null, List.of(), null, null, code, field);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error, null, null, null, null, null, null, null, List.of(), null, null, null, null);
    }

    public static EvaluationResponse ambiguous(List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(
            "ENTRY_AMBIGUOUS",
            "未指定入口函数，候选函数不唯一",
            safeCandidates
        );
        return new EvaluationResponse(null, 0, "入口函数不唯一", null, null, List.of(diagnostic), null, null, null, null, List.of(), null, null, null, null);
    }

    public static EvaluationResponse diagnostic(String code, String message, List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(code, message, safeCandidates);
        return new EvaluationResponse(null, 0, message, null, null, List.of(diagnostic), null, null, null, null, List.of(), null, null, null, null);
    }

    /** 附加回放元数据（replay-capture 模式；不改其它字段）。 */
    public EvaluationResponse withReplayMetadata(io.aster.policy.replay.ReplayMetadata rm) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, rm, traceSkeleton, decision, ruleId, controls, evidence, profile, code, field);
    }

    /** 附加决策骨架（脱敏，供漏斗分析；不改其它字段）。 */
    public EvaluationResponse withTraceSkeleton(io.aster.policy.replay.TraceSkeleton skeleton) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, replayMetadata, skeleton, decision, ruleId, controls, evidence, profile, code, field);
    }

    /** 附加证据键；correlationId 为 null 时不输出 evidence。 */
    public EvaluationResponse withEvidence(String correlationId) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, replayMetadata, traceSkeleton, decision, ruleId, controls,
            correlationId == null ? null : new Evidence(correlationId), profile, code, field);
    }
}
