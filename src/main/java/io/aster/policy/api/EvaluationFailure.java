package io.aster.policy.api;

import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.EntryFunctionException;

/**
 * 评估失败到 HTTP 状态与机器可读错误码的映射（/evaluate、/evaluate-json、/evaluate/batch 共用）。
 *
 * <p>沿异常的 cause 链找第一个能归因的类型：调用方可自行处理的情形回 4xx 并保留真实原因；
 * 其余一律 500 + 固定文案，不透出内部异常消息或堆栈。
 *
 * @param status  HTTP 状态码
 * @param code    机器可读错误码（与 cloud 侧 Cedar 导出的 409 "no executable version" 同口径）
 * @param message 面向调用方的说明
 */
public record EvaluationFailure(int status, String code, String message) {

    public static final String NO_EXECUTABLE_VERSION = "no_executable_version";
    public static final String POLICY_NOT_FOUND = "policy_not_found";
    public static final String FUNCTION_NOT_FOUND = "function_not_found";
    public static final String FUNCTION_NOT_EXECUTABLE = "function_not_executable";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String EVALUATION_FAILED = "evaluation_failed";

    /** 500 的固定文案：与旧响应的 error 文本一致，老客户端的展示不变。 */
    public static final String GENERIC_MESSAGE = "Policy evaluation failed";

    /** cause 链遍历上限，防御自引用或异常深嵌套。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    public static EvaluationFailure classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            EvaluationFailure classified = classifyOne(current);
            if (classified != null) {
                return classified;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return new EvaluationFailure(500, EVALUATION_FAILED, GENERIC_MESSAGE);
    }

    /** 是否为调用方错误（4xx）。 */
    public boolean clientError() {
        return status >= 400 && status < 500;
    }

    private static EvaluationFailure classifyOne(Throwable t) {
        if (t instanceof PolicyNotExecutableException e) {
            return switch (e.reason()) {
                case POLICY_NOT_FOUND -> new EvaluationFailure(404, POLICY_NOT_FOUND, e.getMessage());
                case NO_EXECUTABLE_VERSION -> new EvaluationFailure(409, NO_EXECUTABLE_VERSION, e.getMessage());
            };
        }
        if (t instanceof EntryFunctionException e) {
            // 函数不存在 → 404；被 main 等入口抢占 → 400（调用方可改调用的函数，消息里给了做法）
            return e.isMissingFunction()
                ? new EvaluationFailure(404, FUNCTION_NOT_FOUND, e.getMessage())
                : new EvaluationFailure(400, FUNCTION_NOT_EXECUTABLE, e.getMessage());
        }
        if (t instanceof DynamicCnlExecutor.DynamicExecutionException e) {
            return switch (e.kind()) {
                case INVALID_INPUT -> new EvaluationFailure(400, INVALID_REQUEST, e.getMessage());
                case FUNCTION_NOT_FOUND -> new EvaluationFailure(404, FUNCTION_NOT_FOUND, e.getMessage());
                // 执行期失败：消息可能含内部细节，继续沿 cause 链找，找不到即落到 500
                case EXECUTION -> null;
            };
        }
        return null;
    }
}
