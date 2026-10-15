package io.aster.llm.model;

/**
 * 单次 LLM 调用的运行时凭证与目标（issue: BYOK 真接入推理，Phase 2）。
 *
 * <p>把「本次请求用哪个 provider / baseUrl / apiKey」从全局 {@code LlmConfig} 解耦为 per-request，
 * 使同一执行层（{@code VertxLlmClient}）既能走平台 key，也能走用户自带 BYOK key，而无需在执行层
 * 重新长出「平台 vs BYOK」的业务分支——client 只认这个已解析好的目标。
 *
 * <ul>
 *   <li>平台调用：由 {@code LlmConfig} 填充（apiKey=平台 Vault key，provider/baseUrl=config）。</li>
 *   <li>BYOK 调用：apiKey=用户解密后的 key，provider 来自受控 allowlist。无自定义 baseUrl 时沿用
 *       官方硬编码端点；有自定义 baseUrl 时必须命中管理员 allowlist 且通过 SSRF guard。</li>
 * </ul>
 *
 * <p>安全：本 record 携带明文 apiKey，<b>禁止</b>写入日志、审计 vault、错误体、或作为整体
 * {@code toString()} 打印。{@link #toString()} 已重写脱敏。
 */
public record LlmRuntimeOptions(
    String apiKey,
    String provider,
    String baseUrl,
    Source source,
    WireFormat wireFormat,
    boolean customEndpoint,
    /** BYOK 指定的模型；null = 平台该 provider 默认（含 Anthropic 兜底规则，见 LlmModelPolicy）。 */
    String model
) {

    public LlmRuntimeOptions(String apiKey, String provider, String baseUrl, Source source) {
        this(apiKey, provider, baseUrl, source, inferWireFormat(provider, baseUrl), false, null);
    }

    public LlmRuntimeOptions(String apiKey, String provider, String baseUrl, Source source,
                             WireFormat wireFormat, boolean customEndpoint) {
        this(apiKey, provider, baseUrl, source, wireFormat, customEndpoint, null);
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    /** 凭证来源：平台 key 还是用户自带 BYOK key。 */
    public enum Source {
        PLATFORM,
        BYOK
    }

    /** 请求线协议：显式表达 body/auth/role 映射，禁止再从 baseUrl 字符串猜。 */
    public enum WireFormat {
        OPENAI_NATIVE,
        OPENAI_COMPATIBLE,
        ANTHROPIC_NATIVE
    }

    public boolean usedByok() {
        return source == Source.BYOK;
    }

    public static WireFormat inferWireFormat(String provider, String baseUrl) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if ("anthropic".equals(normalizedProvider)) {
            return WireFormat.ANTHROPIC_NATIVE;
        }
        if ("openai".equals(normalizedProvider) && isHost(baseUrl, "api.openai.com")) {
            return WireFormat.OPENAI_NATIVE;
        }
        return WireFormat.OPENAI_COMPATIBLE;
    }

    private static boolean isHost(String baseUrl, String expectedHost) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String host = java.net.URI.create(baseUrl.trim()).getHost();
            return expectedHost.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 脱敏 toString：绝不泄漏 apiKey。 */
    @Override
    public String toString() {
        return "LlmRuntimeOptions{provider=" + provider
            + ", baseUrl=" + baseUrl
            + ", source=" + source
            + ", wireFormat=" + wireFormat
            + ", customEndpoint=" + customEndpoint
            + ", model=" + (hasModel() ? model : "<default>")
            + ", apiKey=***}";
    }
}
