package io.aster.llm.model;

/**
 * LLM 流式响应事件
 *
 * @param type  事件类型：delta / done / error / usage / reached
 * @param delta 增量文本（仅 delta 类型）
 * @param error 错误信息（仅 error 类型）
 * @param usage token 用量（仅 usage 类型，issue #185）
 * @param message provider 说明（脱敏、截断；仅 error 类型，可空）
 * @param providerErrorType provider 的 error.type（仅 error 类型，可空）
 */
public record LlmStreamEvent(
    Type type,
    String delta,
    String error,
    LlmUsage usage,
    String message,
    String providerErrorType
) {
    public enum Type {
        DELTA,
        DONE,
        ERROR,
        /** SSE 末帧携带的 token 用量（issue #185：OpenAI stream_options.include_usage / Anthropic message_delta）。 */
        USAGE,
        /**
         * 已连上 provider、即将发出请求（cloud#470）。此后的调用一律计入 AI 配额——
         * 不论随后是 provider 报错、超时、无内容还是用户中途断开，请求都已到达第三方。
         * 在它之前失败（请求构造异常、连不上 provider）不计。
         */
        REACHED
    }

    public static LlmStreamEvent delta(String text) {
        return new LlmStreamEvent(Type.DELTA, text, null, null, null, null);
    }

    public static LlmStreamEvent done() {
        return new LlmStreamEvent(Type.DONE, null, null, null, null, null);
    }

    public static LlmStreamEvent error(String code) {
        return new LlmStreamEvent(Type.ERROR, null, code, null, null, null);
    }

    /** 带 provider 说明的结构化错误：code 为分类码，message/providerErrorType 可空。 */
    public static LlmStreamEvent error(String code, String message, String providerErrorType) {
        return new LlmStreamEvent(Type.ERROR, null, code, null, message, providerErrorType);
    }

    public static LlmStreamEvent reached() {
        return new LlmStreamEvent(Type.REACHED, null, null, null, null, null);
    }

    public static LlmStreamEvent usage(LlmUsage usage) {
        return new LlmStreamEvent(Type.USAGE, null, null, usage, null, null);
    }
}
