package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件数据
 *
 * 事件类型：
 * - delta: 流式增量内容
 * - validation_error: 编译校验失败，附带错误详情
 * - repair_start: 开始修复尝试，data 为 "attempt/maxAttempts"
 * - final: 生成完成，包含最终完整代码（validated=true 表示编译通过）
 * - error: 服务端错误
 * - upstream_reached: 已连上 provider，本次调用计入 AI 配额（cloud#470）；无 data，前端忽略
 *
 * @param type      事件类型
 * @param data      内容数据
 * @param error     错误信息（仅 error/validation_error 类型）
 * @param validated 编译是否通过（仅 final 类型）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeneratePolicyEvent(
    String type,
    String data,
    String error,
    Boolean validated
) {
    public static GeneratePolicyEvent delta(String content) {
        return new GeneratePolicyEvent("delta", content, null, null);
    }

    public static GeneratePolicyEvent validationError(String errorDetails) {
        return new GeneratePolicyEvent("validation_error", null, errorDetails, null);
    }

    public static GeneratePolicyEvent repairStart(int attempt, int maxAttempts) {
        return new GeneratePolicyEvent("repair_start", attempt + "/" + maxAttempts, null, null);
    }

    public static GeneratePolicyEvent finalResult(String source, boolean validated) {
        return new GeneratePolicyEvent("final", source, null, validated);
    }

    public static GeneratePolicyEvent upstreamReached() {
        return new GeneratePolicyEvent("upstream_reached", null, null, null);
    }

    public static GeneratePolicyEvent error(String message) {
        return new GeneratePolicyEvent("error", null, message, null);
    }
}
