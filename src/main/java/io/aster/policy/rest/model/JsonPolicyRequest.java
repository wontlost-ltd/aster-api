package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.evidence.AgentIdentity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON 格式策略评估请求
 *
 * 支持直接传入 Core IR JSON 格式的策略进行评估，无需提前部署
 *
 * @param policy Core IR JSON 格式的策略定义
 * @param context 评估上下文参数（Map 或 Object 数组）
 */
public record JsonPolicyRequest(
    // policy 是序列化的 Core IR JSON，会被反序列化 + 转换。设上限防止超大
    // body 在解析/转换时放大成本（context 为无界 Object，由全局 body 上限兜底）。
    @NotNull(message = "policy 不能为空")
    @Size(max = CnlSourceLimits.MAX_JSON_POLICY_LENGTH,
          message = "policy 长度超过上限（最多 " + CnlSourceLimits.MAX_JSON_POLICY_LENGTH + " 字符）")
    String policy,

    @NotNull(message = "context 不能为空")
    Object context,

    // 行动中的模型身份（ADR 0041 §3）；可选，由调用方自报
    @JsonProperty("agent")
    AgentIdentity agent
) {
}
