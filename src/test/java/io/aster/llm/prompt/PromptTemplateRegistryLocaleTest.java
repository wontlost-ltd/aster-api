package io.aster.llm.prompt;

import io.aster.llm.config.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptTemplateRegistry locale 归一化的安全校验单测（java/path-injection）。
 *
 * <p>不启动 Quarkus（@QuarkusTest 需 aster_policy DB）——直接 new + 反射调用私有
 * {@code normalizeLocale}，用轻量动态代理提供 {@link LlmConfig}（只实现 prompt().defaultLocale()）。
 * 校验：合法 locale 归一化为短码；含路径穿越字符的非法 locale 回退默认 locale，绝不拼入原始输入。
 */
@DisplayName("PromptTemplateRegistry locale 安全归一化")
class PromptTemplateRegistryLocaleTest {

    /** 默认 locale（与生产配置 @WithDefault("zh") 对齐）。 */
    private static final String DEFAULT_LOCALE = "zh";

    /** 用动态代理造一个只关心 prompt().defaultLocale() 的 LlmConfig。 */
    private static LlmConfig stubConfig() {
        LlmConfig.Prompt prompt = (LlmConfig.Prompt) Proxy.newProxyInstance(
            PromptTemplateRegistryLocaleTest.class.getClassLoader(),
            new Class<?>[]{LlmConfig.Prompt.class},
            (InvocationHandler) (proxy, method, args) -> {
                if ("defaultLocale".equals(method.getName())) return DEFAULT_LOCALE;
                if ("basePath".equals(method.getName())) return "prompts";
                return null;
            });
        return (LlmConfig) Proxy.newProxyInstance(
            PromptTemplateRegistryLocaleTest.class.getClassLoader(),
            new Class<?>[]{LlmConfig.class},
            (InvocationHandler) (proxy, method, args) -> {
                if ("prompt".equals(method.getName())) return prompt;
                return null;
            });
    }

    private static String normalize(String locale) throws Exception {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        // 注入 stub config（字段包内可见）。
        java.lang.reflect.Field cfg = PromptTemplateRegistry.class.getDeclaredField("config");
        cfg.setAccessible(true);
        cfg.set(registry, stubConfig());

        Method m = PromptTemplateRegistry.class.getDeclaredMethod("normalizeLocale", String.class);
        m.setAccessible(true);
        return (String) m.invoke(registry, locale);
    }

    @Test
    @DisplayName("合法 locale 归一化为短码（zh-CN→zh, en_US→en, de→de）")
    void normalizesValidLocales() throws Exception {
        assertThat(normalize("zh-CN")).isEqualTo("zh");
        assertThat(normalize("en_US")).isEqualTo("en");
        assertThat(normalize("de-DE")).isEqualTo("de");
        assertThat(normalize("en")).isEqualTo("en");
        assertThat(normalize("hi-IN")).isEqualTo("hi");
    }

    @Test
    @DisplayName("null/空 locale → 默认 locale")
    void nullOrBlankFallsBackToDefault() throws Exception {
        assertThat(normalize(null)).isEqualTo(DEFAULT_LOCALE);
        assertThat(normalize("")).isEqualTo(DEFAULT_LOCALE);
        assertThat(normalize("   ")).isEqualTo(DEFAULT_LOCALE);
    }

    @Test
    @DisplayName("路径穿越 locale → 回退默认 locale（拒绝拼入 ../ 等，java/path-injection）")
    void pathTraversalFallsBackToDefault() throws Exception {
        // 归一化后绝不包含路径穿越字符：非法输入一律回退默认 locale。
        assertThat(normalize("../../../etc/passwd")).isEqualTo(DEFAULT_LOCALE);
        assertThat(normalize("..")).isEqualTo(DEFAULT_LOCALE);
        assertThat(normalize("en/../secret")).isEqualTo(DEFAULT_LOCALE); // split('-'/'_') 不含分隔符 → 整体带 /
        assertThat(normalize("zh.CN")).isEqualTo(DEFAULT_LOCALE);        // 点号非法
        assertThat(normalize("en\\us")).isEqualTo(DEFAULT_LOCALE);       // 反斜杠非法
    }
}
