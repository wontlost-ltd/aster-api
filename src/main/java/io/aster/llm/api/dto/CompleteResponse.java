package io.aster.llm.api.dto;

/**
 * 代码补全响应
 *
 * @param completion      补全文本
 * @param model           实际使用的模型
 * @param upstreamReached 本次调用是否已到达第三方 AI（cloud#518）。cloud 只对 true 计入 AI 配额：
 *                        未配置 key、连不上 provider 时返回的空补全不计费；provider 报错、
 *                        超时等已到达的失败照样计费
 */
public record CompleteResponse(
    String completion,
    String model,
    boolean upstreamReached
) {}
