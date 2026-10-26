package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * REST API 请求：编译 CNL 源代码（只解析+降级，不执行、不落库）。
 *
 * 供保存前的可编译性校验（cloud defense-in-depth）与 IDE compile-on-type 使用。
 * 与 {@code SchemaRequest} 同为**匿名**端点请求，故沿用更严的匿名源码上限
 * （{@link CnlSourceLimits#MAX_ANON_SOURCE_LENGTH}，16 KiB），把最坏单次解析
 * 耗时压进秒级、防算法复杂度 DoS。
 */
public record CompileRequest(
    @NotBlank(message = "源代码不能为空")
    @Size(max = CnlSourceLimits.MAX_ANON_SOURCE_LENGTH,
          message = "源代码长度超过上限（最多 " + CnlSourceLimits.MAX_ANON_SOURCE_LENGTH + " 字符）")
    @JsonProperty("source")
    String source,

    @JsonProperty("locale")
    String locale,

    // 用户自定义关键词别名（kind-name → 短语列表），可选。与执行/schema 一致：
    // 依赖别名的源码必须带 aliasSet 编译，否则会被误判为解析错误（前后端语义分裂）。
    @JsonProperty("aliasSet")
    Map<String, List<String>> aliasSet,

    // 请求把 aliasSet 中的结构词别名（Module/Rule/If/Otherwise/Match/When/Return 等）按已授权处理，可选。
    // 仅是「请求」而非「授权」：只有 HMAC 已验签的内部调用方（cloud BFF，已在服务端核对
    // 用户的结构词授权）传 true 才生效；匿名/未签名调用方传 true 一律忽略，按未授权编译。
    @JsonProperty("allowStructural")
    Boolean allowStructural,

    // 领域词汇表（可选，ADR 0014 线C），形同 SourcePolicyRequest.vocabulary（DomainVocabulary JSON）。
    // 保存门禁须与执行用同一份词汇编译，否则用本地化领域术语写的源码会在保存时被误判为解析错误。
    // 为 null/空时与不带词汇编译完全一致；格式非法时退化为仅内置词汇（与执行端口径一致）。
    @JsonProperty("vocabulary")
    Map<String, Object> vocabulary
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale.trim();
    }
}
