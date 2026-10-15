package io.aster.llm.model;

import io.aster.llm.config.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/** LlmModelPolicy：BYOK 指定 > 请求 model > 平台默认；Anthropic 兜底只在非 BYOK 指定时生效。 */
class LlmModelPolicyTest {

    private static LlmConfig config() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "model" -> "gpt-4o-mini";
            case "anthropicDefaultModel" -> "claude-default";
            default -> null;
        };
        return (LlmConfig) Proxy.newProxyInstance(LlmConfig.class.getClassLoader(),
            new Class<?>[]{LlmConfig.class}, h);
    }

    private static LlmRuntimeOptions byok(String provider, String model) {
        return new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.BYOK,
            LlmRuntimeOptions.inferWireFormat(provider, "https://x"), false, model);
    }

    private static LlmRuntimeOptions platform(String provider) {
        return new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.PLATFORM);
    }

    @Test
    @DisplayName("BYOK 指定 model → 原样（openai）")
    void byokModelWins() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("openai", "gpt-4o"), config())).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("★BYOK anthropic 指定非 claude 名（网关别名）→ 原样发出，不替换")
    void byokExplicitModelIsNotReplaced() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("anthropic", "gw-sonnet-alias"), config()))
            .isEqualTo("gw-sonnet-alias");
    }

    @Test
    @DisplayName("★BYOK anthropic 未指定 model + 请求为 OpenAI 名 → 仍兜底 Anthropic 默认（与今天一致）")
    void byokWithoutModelStillFallsBack() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("anthropic", null), config())).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("平台 anthropic：非 claude → 兜底；claude* → 保留")
    void platformAnthropicRule() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", platform("anthropic"), config())).isEqualTo("claude-default");
        assertThat(LlmModelPolicy.wireModel("claude-3-7", platform("anthropic"), config())).isEqualTo("claude-3-7");
        assertThat(LlmModelPolicy.wireModel(null, platform("anthropic"), config())).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("非 anthropic：请求 model，缺省平台 config.model")
    void openaiRule() {
        assertThat(LlmModelPolicy.wireModel("gpt-4.1", platform("openai"), config())).isEqualTo("gpt-4.1");
        assertThat(LlmModelPolicy.wireModel(null, platform("openai"), config())).isEqualTo("gpt-4o-mini");
        assertThat(LlmModelPolicy.wireModel(null, null, config())).isEqualTo("gpt-4o-mini");
    }
}
