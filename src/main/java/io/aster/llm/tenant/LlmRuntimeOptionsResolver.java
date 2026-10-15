package io.aster.llm.tenant;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.security.LlmEndpointPolicy;
import io.aster.security.net.ValidatedEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Map;

/**
 * 解析单次 LLM 调用的运行时凭证（issue: BYOK 真接入推理，Phase 2）。
 *
 * <p>无 BYOK 覆盖 → 平台凭证（apiKey 走 {@link TenantLlmKeyProvider}，provider/baseUrl 走 config）。
 * 有合法 BYOK 覆盖 → BYOK 凭证：apiKey=用户解密 key，provider 经 allowlist 规范化。baseUrl 为空时
 * 沿用官方硬编码端点；baseUrl 非空时必须命中管理员 allowlist 并通过 SSRF guard。
 *
 * <p>失败语义（红队铁律）：BYOK 覆盖存在但非法（provider 不在 allowlist / key 空）→ 抛错，
 * <b>绝不回退平台 key</b>（否则 BYOK 用户失败后又白嫖平台预算）。
 */
@ApplicationScoped
public class LlmRuntimeOptionsResolver {

    private static final Logger LOG = Logger.getLogger(LlmRuntimeOptionsResolver.class);

    /**
     * BYOK provider → 固定 baseUrl allowlist。仅 OpenAI / Anthropic（Vertex 不是简单 API-key
     * 模型，Phase 2 排除）。baseUrl 硬编码在此，不从任何外部输入取。
     */
    private static final Map<String, String> BYOK_PROVIDER_BASE_URLS = Map.of(
        "openai", "https://api.openai.com",
        "anthropic", "https://api.anthropic.com"
    );

    @Inject
    LlmConfig config;

    @Inject
    TenantLlmKeyProvider keyProvider;

    @Inject
    LlmEndpointPolicy endpointPolicy;

    /**
     * 解析平台凭证（无 BYOK）。
     *
     * @return 平台 {@link LlmRuntimeOptions}；平台 key 缺失时 apiKey 为 null（调用方须判空）。
     */
    public LlmRuntimeOptions resolvePlatform(String tenantId) {
        String apiKey = keyProvider.getApiKey(tenantId, config.provider());
        return new LlmRuntimeOptions(apiKey, config.provider(), config.baseUrl(),
            LlmRuntimeOptions.Source.PLATFORM);
    }

    /**
     * 解析本次调用的凭证：有合法 BYOK 覆盖走 BYOK，否则走平台。
     *
     * @throws IllegalArgumentException BYOK 覆盖存在但 provider 不在 allowlist（不回退平台）
     */
    public LlmRuntimeOptions resolve(String tenantId, ByokOverride byok) {
        if (byok == null || !byok.isValid()) {
            return resolvePlatform(tenantId);
        }
        String provider = byok.provider().trim().toLowerCase(Locale.ROOT);
        String officialBaseUrl = BYOK_PROVIDER_BASE_URLS.get(provider);
        if (officialBaseUrl == null) {
            // 红队：不接受未知 provider（防 SSRF / 打错端点），且不回退平台 key。
            throw new IllegalArgumentException("不支持的 BYOK provider: " + provider);
        }
        if (!byok.hasCustomBaseUrl()) {
            LOG.infof("使用 BYOK 官方端点: tenant=%s, provider=%s", tenantId, provider);
            return new LlmRuntimeOptions(byok.apiKey(), provider, officialBaseUrl,
                LlmRuntimeOptions.Source.BYOK,
                LlmRuntimeOptions.inferWireFormat(provider, officialBaseUrl),
                false,
                byok.hasModel() ? byok.model() : null);
        }

        ValidatedEndpoint endpoint = endpointPolicy.validateByokEndpoint(provider, byok.baseUrl());
        String baseUrl = endpoint.originalUri().toString();
        LlmRuntimeOptions.WireFormat wireFormat = "openai".equals(provider)
            ? LlmRuntimeOptions.WireFormat.OPENAI_COMPATIBLE
            : LlmRuntimeOptions.WireFormat.ANTHROPIC_NATIVE;
        LOG.infof("使用 BYOK 自定义 allowlist 端点: tenant=%s, provider=%s, host=%s",
            tenantId, provider, endpoint.canonicalHost());
        return new LlmRuntimeOptions(byok.apiKey(), provider, baseUrl,
            LlmRuntimeOptions.Source.BYOK, wireFormat, true,
            byok.hasModel() ? byok.model() : null);
    }
}
