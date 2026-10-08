package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.evidence.AgentIdentity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * REST API请求：策略评估
 *
 * 用于单个策略的评估请求，包含策略模块、函数名和上下文数据。
 */
public record EvaluationRequest(
    @NotBlank(message = "policyModule不能为空")
    @JsonProperty("policyModule")
    String policyModule,

    @NotBlank(message = "policyFunction不能为空")
    @JsonProperty("policyFunction")
    String policyFunction,

    // context 元素数上限：真实函数参数个数很小，限制数组长度防止超大数组在
    // 序列化/求值时放大成本（见 CnlSourceLimits.MAX_CONTEXT_ELEMENTS）。
    @NotNull(message = "context不能为null")
    @Size(max = CnlSourceLimits.MAX_CONTEXT_ELEMENTS,
          message = "context 参数过多（最多 " + CnlSourceLimits.MAX_CONTEXT_ELEMENTS + " 个）")
    @JsonProperty("context")
    Object[] context,

    // 行动中的模型身份（ADR 0041 §3）；可选，由调用方自报
    @JsonProperty("agent")
    AgentIdentity agent
) {
}
