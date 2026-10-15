package io.aster.llm.model;

/**
 * 一次请求携带的 BYOK 覆盖凭证（issue: BYOK 真接入推理，Phase 2；ADR 0048 增加 model）。
 *
 * <p>由 aster-cloud 在【已 HMAC 验签】的内部请求 body 顶层以 {@code _byok} envelope 传入，
 * 经 {@code AiAssistantResource} 从原始 body 解析、剥离后构造，<b>不进业务 DTO、不进 PromptComposer、
 * 不进审计 vault、不打日志</b>。
 *
 * <p>安全：携带 provider + 用户解密后的 apiKey + 可选 baseUrl + 可选 model。baseUrl 来自 cloud 已验签
 * envelope，但仍是用户配置派生值，aster-api 每次使用前必须重新经过管理员 allowlist + SSRF guard。
 * model 为空表示「用平台该 provider 的默认模型」。{@link #toString()} 脱敏。
 */
public record ByokOverride(String provider, String apiKey, String baseUrl, String model) {

    public ByokOverride(String provider, String apiKey) {
        this(provider, apiKey, null, null);
    }

    public ByokOverride(String provider, String apiKey, String baseUrl) {
        this(provider, apiKey, baseUrl, null);
    }

    public boolean isValid() {
        return provider != null && !provider.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }

    public boolean hasCustomBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    @Override
    public String toString() {
        return "ByokOverride{provider=" + provider
            + ", baseUrl=" + (hasCustomBaseUrl() ? baseUrl : "<default>")
            + ", model=" + (hasModel() ? model : "<default>")
            + ", apiKey=***}";
    }
}
