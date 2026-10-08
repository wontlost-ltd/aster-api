package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.evidence.AgentIdentity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * CNL 源代码策略评估请求
 *
 * 支持直接传入 Aster CNL 源代码进行评估，无需提前部署。
 * 适用于 Dashboard 执行场景。
 *
 * @param source CNL 源代码
 * @param context 评估上下文参数（Map 或 Object 数组）
 * @param locale CNL 语言 (可选，默认 en-US)
 * @param functionName 要执行的函数名 (可选，未指定时由服务端入口选择器决定)
 * @param vocabulary 领域词汇表（可选）。ADR 0014 线C：发布的策略携带其快照
 *        领域词汇，使执行端规范化阶段能把用户自定义术语翻译为规范化名称。
 *        形如 aster-lang-core DomainVocabulary 的 JSON（id/name/locale/version/
 *        structs/fields/functions/enumValues）。为 null 时仅用内置词汇。
 * @param aliasSet 用户关键词别名（可选，ADR 0022）。已发布版本冻结的规范别名快照，
 *        形如 {@code {"TIMES":["multiplied by"],...}}（kind → 多词短语数组）。执行端归一
 *        阶段据此把别名归回规范关键词，使别名写的源码能编译。冻结版本 = 已授权+校验+进
 *        envelope 的可信快照，执行端按 allowStructural=true 应用。为 null 时无用户别名。
 */
public record SourcePolicyRequest(
    // 见 CnlSourceLimits：CNL 解析在长输入上超线性耗时，无上限即 DoS 向量。
    @NotNull(message = "source 不能为空")
    @Size(max = CnlSourceLimits.MAX_SOURCE_LENGTH,
          message = "源代码长度超过上限（最多 " + CnlSourceLimits.MAX_SOURCE_LENGTH + " 字符）")
    String source,

    @NotNull(message = "context 不能为空")
    Object context,

    String locale,

    String functionName,

    Map<String, Object> vocabulary,

    Map<String, java.util.List<String>> aliasSet,

    // 行动中的模型身份（ADR 0041 §3）；可选，由调用方自报
    @JsonProperty("agent")
    AgentIdentity agent
) {
    /**
     * 获取语言配置，默认 en-US
     */
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale;
    }

    /**
     * 获取函数名；未指定时返回 null，由入口选择器决定。
     */
    public String getFunctionNameOrDefault() {
        return functionName == null || functionName.isBlank() ? null : functionName.trim();
    }
}
