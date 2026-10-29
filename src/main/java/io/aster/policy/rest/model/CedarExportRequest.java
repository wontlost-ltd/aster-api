package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * REST API 请求：把一条 Aster 规则导出为 Cedar（ADR 0047 §6）。
 *
 * <p>{@code source} 与 {@code versionId} 恰给其一：源码模式与编译端点同形（源码、语言、别名集、领域词汇），
 * 版本模式读取本租户已保存的版本。{@code claimedRef} 为调用方自述的外部引用（如 aster-cloud 的
 * 策略 id 与版本号），原样记入审计，aster-api 不核验。
 */
public record CedarExportRequest(
    @JsonProperty("source")
    String source,

    @JsonProperty("locale")
    String locale,

    @JsonProperty("aliasSet")
    Map<String, List<String>> aliasSet,

    @JsonProperty("functionName")
    String functionName,

    @JsonProperty("versionId")
    Long versionId,

    @JsonProperty("claimedRef")
    Map<String, String> claimedRef,

    // 领域词汇表（可选，仅源码模式，ADR 0051），形同 CompileRequest.vocabulary（DomainVocabulary JSON）
    @JsonProperty("vocabulary")
    Map<String, Object> vocabulary
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale.trim();
    }
}
