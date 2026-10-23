package io.aster.llm.tenant;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.security.LlmEndpointPolicy;
import io.aster.security.net.ValidatedEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmRuntimeOptionsResolver 单测（Phase 2 BYOK）：平台/BYOK 解析、SSRF allowlist、失败不回退。
 */
class LlmRuntimeOptionsResolverTest {

    private LlmConfig config;
    private TenantLlmKeyProvider keyProvider;
    private LlmEndpointPolicy endpointPolicy;
    private LlmRuntimeOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        config = new FakeLlmConfig();
        keyProvider = (tenantId, provider) -> "tenant-1".equals(tenantId) && "rightcode".equals(provider)
            ? "platform-key"
            : null;
        endpointPolicy = new FakeEndpointPolicy();

        resolver = new LlmRuntimeOptionsResolver();
        resolver.config = config;
        resolver.keyProvider = keyProvider;
        resolver.endpointPolicy = endpointPolicy;
    }

    @Test
    @DisplayName("无 BYOK → 平台凭证（provider/baseUrl 来自 config，key 来自 keyProvider）")
    void resolvesPlatformWhenNoByok() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", null);
        assertThat(opts.source()).isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
        assertThat(opts.provider()).isEqualTo("rightcode");
        assertThat(opts.baseUrl()).isEqualTo("https://right.codes/codex/v1");
        assertThat(opts.apiKey()).isEqualTo("platform-key");
        assertThat(opts.usedByok()).isFalse();
        assertThat(opts.wireFormat()).isEqualTo(LlmRuntimeOptions.WireFormat.OPENAI_COMPATIBLE);
        assertThat(opts.customEndpoint()).isFalse();
    }

    @Test
    @DisplayName("非法 BYOK（provider/key 空）→ 回退平台（isValid=false 不算 BYOK）")
    void invalidByokFallsBackToPlatform() {
        assertThat(resolver.resolve("tenant-1", new ByokOverride(null, "k")).source())
            .isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
        assertThat(resolver.resolve("tenant-1", new ByokOverride("openai", "")).source())
            .isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
    }

    @Test
    @DisplayName("BYOK openai → 固定 baseUrl api.openai.com（不信外部 URL）")
    void resolvesByokOpenAi() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user"));
        assertThat(opts.source()).isEqualTo(LlmRuntimeOptions.Source.BYOK);
        assertThat(opts.provider()).isEqualTo("openai");
        assertThat(opts.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(opts.apiKey()).isEqualTo("sk-user");
        assertThat(opts.usedByok()).isTrue();
        assertThat(opts.wireFormat()).isEqualTo(LlmRuntimeOptions.WireFormat.OPENAI_NATIVE);
        assertThat(opts.customEndpoint()).isFalse();
    }

    @Test
    @DisplayName("BYOK anthropic → 固定 baseUrl api.anthropic.com")
    void resolvesByokAnthropic() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("anthropic", "sk-ant"));
        assertThat(opts.provider()).isEqualTo("anthropic");
        assertThat(opts.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(opts.wireFormat()).isEqualTo(LlmRuntimeOptions.WireFormat.ANTHROPIC_NATIVE);
    }

    @Test
    @DisplayName("BYOK provider 大小写/空格规范化")
    void normalizesProvider() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("  OpenAI ", "sk"));
        assertThat(opts.provider()).isEqualTo("openai");
        assertThat(opts.baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("BYOK baseUrl 为空 → 零行为变化，仍走官方硬编码端点")
    void blankBaseUrlKeepsOfficialEndpoint() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1",
            new ByokOverride("openai", "sk-user", "  "));

        assertThat(opts.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(opts.customEndpoint()).isFalse();
    }

    @Test
    @DisplayName("BYOK 自定义 baseUrl 命中管理员 allowlist → 使用 custom endpoint + OpenAI-compatible wire format")
    void resolvesAllowedCustomEndpoint() {
        String custom = "https://llm-gateway.example.com/v1";
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user", custom));

        assertThat(opts.baseUrl()).isEqualTo(custom);
        assertThat(opts.customEndpoint()).isTrue();
        assertThat(opts.wireFormat()).isEqualTo(LlmRuntimeOptions.WireFormat.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("BYOK 自定义 baseUrl 未命中 allowlist → 抛错且不回退平台")
    void rejectsCustomEndpointOutsideAllowlist() {
        String custom = "https://evil.example.net/v1";
        assertThatThrownBy(() -> resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user", custom)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("★SSRF 防护：未知 provider（含 vertex）→ 抛错，绝不回退平台 key")
    void rejectsUnknownProviderNoFallback() {
        assertThatThrownBy(() -> resolver.resolve("tenant-1", new ByokOverride("vertex", "k")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vertex");
        assertThatThrownBy(() -> resolver.resolve("tenant-1", new ByokOverride("evil-host.internal", "k")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BYOK 指定 model → 透传到 LlmRuntimeOptions.model")
    void resolverPassesByokModel() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1",
            new ByokOverride("openai", "sk-user", null, "gpt-4o"));
        assertThat(opts.model()).isEqualTo("gpt-4o");
        assertThat(opts.usedByok()).isTrue();
    }

    @Test
    @DisplayName("★旧 cloud 不带 model → model=null，平台/BYOK 行为与今天一致")
    void resolverKeepsNullModel() {
        assertThat(resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user")).model()).isNull();
        assertThat(resolver.resolve("tenant-1", null).model()).isNull();
    }

    @Test
    @DisplayName("BYOK 自定义端点 + 指定 model → 两者同时保留")
    void customEndpointKeepsModel() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1",
            new ByokOverride("openai", "sk-user", "https://llm-gateway.example.com/v1", "gw-alias-1"));
        assertThat(opts.customEndpoint()).isTrue();
        assertThat(opts.model()).isEqualTo("gw-alias-1");
    }

    private static class FakeEndpointPolicy extends LlmEndpointPolicy {
        @Override
        public ValidatedEndpoint validateByokEndpoint(String provider, String baseUrl) {
            if ("openai".equals(provider) && "https://llm-gateway.example.com/v1".equals(baseUrl)) {
                return new ValidatedEndpoint(URI.create(baseUrl), "llm-gateway.example.com", 443, "/v1",
                    Optional.empty(), true);
            }
            throw new IllegalArgumentException("BYOK baseUrl 未命中管理员 allowlist");
        }
    }

    private static class FakeLlmConfig implements LlmConfig {
        @Override public boolean enabled() { return true; }
        @Override public String provider() { return "rightcode"; }
        @Override public String baseUrl() { return "https://right.codes/codex/v1"; }
        @Override public String model() { return "m"; }
        @Override public String anthropicDefaultModel() { return "claude-m"; }
        @Override public double temperature() { return 0.2; }
        @Override public int maxTokens() { return 100; }
        @Override public int suggestMaxTokens() { return 100; }
        @Override public int assistantMaxTokens() { return 100; }
        @Override public Duration timeout() { return Duration.ofSeconds(30); }
        @Override public Duration readTimeout() { return Duration.ofSeconds(120); }
        @Override public Optional<String> apiKey() { return Optional.empty(); }
        @Override public String keySource() { return "config"; }
        @Override public Validation validation() { return () -> 5; }
        @Override public Prompt prompt() {
            return new Prompt() {
                @Override public String basePath() { return "prompts"; }
                @Override public String defaultLocale() { return "zh"; }
            };
        }
        @Override public Cache cache() {
            return new Cache() {
                @Override public boolean enabled() { return true; }
                @Override public Duration ttl() { return Duration.ofMinutes(10); }
                @Override public int maxSize() { return 1000; }
            };
        }
    }
}
