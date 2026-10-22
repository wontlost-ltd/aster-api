package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件数据
 *
 * 事件类型：
 * - delta: 流式增量内容
 * - validation_error: 编译校验失败，附带错误详情
 * - repair_start: 开始修复尝试，data 为 "attempt/maxAttempts"
 * - final: 生成完成，data 为最终完整文本；generate 另带 validated（编译是否通过），
 *   suggest / assistant 不带 validated。所有 final 都带 truncated（上游是否因输出上限截断），
 *   已知停止原因时另带 stopReason
 * - error: 服务端错误
 * - meta: 流首帧，data 为本次实际发出的 model（ADR 0048）
 * - upstream_reached: 已连上 provider，本次调用计入 AI 配额（cloud#470）；无 data，前端忽略
 *
 * @param type      事件类型
 * @param data      内容数据
 * @param error     错误信息（仅 error/validation_error 类型）
 * @param validated 编译是否通过（仅 final 类型）
 * @param message   provider 说明（仅 error 类型，脱敏截断，可空）
 * @param providerErrorType provider 的 error.type（仅 error 类型，可空）
 * @param truncated 上游是否因输出上限截断（仅 final 类型，恒有值）
 * @param stopReason 归一化后的上游停止原因（仅 final 类型，未知则省略）；截断时为 max_tokens
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeneratePolicyEvent(
    String type,
    String data,
    String error,
    Boolean validated,
    String message,
    String providerErrorType,
    Boolean truncated,
    String stopReason
) {
    public static GeneratePolicyEvent delta(String content) {
        return new GeneratePolicyEvent("delta", content, null, null, null, null, null, null);
    }

    public static GeneratePolicyEvent validationError(String errorDetails) {
        return new GeneratePolicyEvent("validation_error", null, errorDetails, null, null, null, null, null);
    }

    public static GeneratePolicyEvent repairStart(int attempt, int maxAttempts) {
        return new GeneratePolicyEvent("repair_start", attempt + "/" + maxAttempts, null, null, null, null, null, null);
    }

    /** generate 的终帧：带编译结论与截断标记。 */
    public static GeneratePolicyEvent finalResult(String source, boolean validated, String stopReason) {
        return new GeneratePolicyEvent("final", source, null, validated, null, null,
            isTruncated(stopReason), stopReason);
    }

    /**
     * suggest / assistant 的终帧：data 为完整回答（与 generate 的 final 一样，消费方据此整体替换已拼出的内容）。
     * 不带 validated——问答类输出没有编译结论，带上会让消费方误显示「未通过校验」。
     */
    public static GeneratePolicyEvent finalText(String text, String stopReason) {
        return new GeneratePolicyEvent("final", text, null, null, null, null,
            isTruncated(stopReason), stopReason);
    }

    private static boolean isTruncated(String stopReason) {
        return io.aster.llm.model.LlmStreamEvent.STOP_MAX_TOKENS.equals(stopReason);
    }

    public static GeneratePolicyEvent upstreamReached() {
        return new GeneratePolicyEvent("upstream_reached", null, null, null, null, null, null, null);
    }

    /** 首帧：本次实际发往 provider 的 model（ADR 0048 §5），cloud 据此记账。 */
    public static GeneratePolicyEvent meta(String model) {
        return new GeneratePolicyEvent("meta", model, null, null, null, null, null, null);
    }

    public static GeneratePolicyEvent error(String message) {
        return new GeneratePolicyEvent("error", null, message, null, null, null, null, null);
    }

    /** provider 失败：error 为分类码，message/providerErrorType 为 provider 给出的说明（可空，空则不输出）。 */
    public static GeneratePolicyEvent error(String code, String message, String providerErrorType) {
        return new GeneratePolicyEvent("error", null, code, null, message, providerErrorType, null, null);
    }
}
