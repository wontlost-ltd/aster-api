package io.aster.llm.config;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * suggest / assistant 专属输出上限的默认值与覆盖。
 *
 * <p>不启动 Quarkus：直接用 SmallRye 构建 {@link LlmConfig} 映射，验证 @WithDefault 与
 * application.properties 里 {@code ${ASTER_LLM_SUGGEST_MAX_TOKENS:8192}} 这类环境变量占位的展开。
 */
class LlmConfigMaxTokensTest {

    private static LlmConfig build(Map<String, String> props) {
        // Quarkus 的 Duration 写法（10m / 120s）与 ${VAR:default} 展开需显式启用
        SmallRyeConfig cfg = new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withConverter(java.time.Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
            .withMapping(LlmConfig.class)
            .withSources(new MapBackedConfigSource("test", props, 500) {})
            .build();
        return cfg.getConfigMapping(LlmConfig.class);
    }

    @Test
    void 默认值_专属上限8192_全局仍为2048() {
        LlmConfig cfg = build(Map.of());
        assertThat(cfg.suggestMaxTokens()).isEqualTo(8192);
        assertThat(cfg.assistantMaxTokens()).isEqualTo(8192);
        assertThat(cfg.maxTokens()).isEqualTo(2048);
    }

    @Test
    void 可单独覆盖_且不影响全局上限() {
        LlmConfig cfg = build(Map.of(
            "aster.llm.suggest-max-tokens", "16000",
            "aster.llm.assistant-max-tokens", "3000"));
        assertThat(cfg.suggestMaxTokens()).isEqualTo(16000);
        assertThat(cfg.assistantMaxTokens()).isEqualTo(3000);
        assertThat(cfg.maxTokens()).isEqualTo(2048);
    }

    @Test
    void 环境变量占位_与application_properties写法一致() {
        // 与 application.properties 同形：属性值引用环境变量名，变量缺省时回落默认值
        LlmConfig unset = build(Map.of(
            "aster.llm.suggest-max-tokens", "${ASTER_LLM_SUGGEST_MAX_TOKENS_UNSET_FOR_TEST:8192}"));
        assertThat(unset.suggestMaxTokens()).isEqualTo(8192);

        LlmConfig set = build(Map.of(
            "ASTER_LLM_SUGGEST_MAX_TOKENS", "12000",
            "aster.llm.suggest-max-tokens", "${ASTER_LLM_SUGGEST_MAX_TOKENS:8192}"));
        assertThat(set.suggestMaxTokens()).isEqualTo(12000);
    }
}
