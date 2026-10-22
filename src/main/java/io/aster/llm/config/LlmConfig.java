package io.aster.llm.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * LLM 集成配置
 *
 * 通过 aster.llm.* 前缀的配置项管理 LLM 连接和行为。
 * 支持多提供商（OpenAI / Claude / 自定义兼容端点）。
 */
@ConfigMapping(prefix = "aster.llm")
public interface LlmConfig {

    @WithDefault("true")
    boolean enabled();

    /** LLM 提供商标识：openai / anthropic / custom */
    @WithDefault("openai")
    String provider();

    @WithDefault("https://api.openai.com")
    String baseUrl();

    @WithDefault("gpt-4o-mini")
    String model();

    /**
     * BYOK 走 Anthropic 时、请求模型又非 claude 系（如上面的平台默认 OpenAI 模型）时改用的模型。
     *
     * <p>Anthropic 会下线旧模型：此前写死的 claude-3-5-sonnet-20241022 下线后，所有 Anthropic BYOK
     * 调用都被 provider 以 400 拒绝（aster-api#360）。故做成配置，下线时改环境变量即可。
     */
    @WithDefault("claude-sonnet-5-5")
    String anthropicDefaultModel();

    @WithDefault("0.2")
    double temperature();

    /** 全局输出上限（generate 等未单独配置的场景）。 */
    @WithDefault("2048")
    int maxTokens();

    /**
     * suggest（策略优化建议）的输出上限。建议是带改进片段的长文本，沿用全局 2048 时
     * 常被拦腰截断；单独配置，与全局上限互不影响。
     */
    @WithDefault("8192")
    int suggestMaxTokens();

    /** 站内助手问答的输出上限；与 suggest 同理，不与全局上限共用。 */
    @WithDefault("8192")
    int assistantMaxTokens();

    /** 连接超时 */
    @WithDefault("30s")
    Duration timeout();

    /** 读超时（SSE 流式场景需要较长） */
    @WithDefault("120s")
    Duration readTimeout();

    /** 全局 API Key（多租户场景可被租户专属 Key 覆盖） */
    Optional<String> apiKey();

    /** Key 来源：config / db */
    @WithDefault("config")
    String keySource();

    Validation validation();

    Prompt prompt();

    Cache cache();

    interface Validation {
        /** 编译校验最大重试次数（含首次生成） */
        @WithDefault("5")
        int maxAttempts();
    }

    interface Prompt {
        @WithDefault("prompts")
        String basePath();

        @WithDefault("zh")
        String defaultLocale();
    }

    interface Cache {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("10m")
        Duration ttl();

        @WithDefault("1000")
        int maxSize();
    }
}
