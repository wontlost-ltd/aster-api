package io.aster.llm.prompt;

import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.config.LlmConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class PromptComposerSyntaxTest {
    @Test
    void generateTemplatesAlignFieldAndRuleTypesAcrossLocales() throws Exception {
        for (String locale : new String[]{"en", "zh", "de"}) {
            String template = resource("prompts/developer/policy_gen_" + locale + ".txt");
            assertThat(template).contains("as Int").doesNotContain("RIGHT: Define User has name, age.");
        }
    }

    @Test
    void suggestSystemPromptCarriesSharedSyntaxRules() throws Exception {
        PromptComposer composer = new PromptComposer();
        set(composer, "templates", new PromptTemplateRegistry() {
            @Override public String load(String category, String name, String locale) {
                try { return resource("prompts/" + category + "/" + name + "_" + locale + ".txt"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        set(composer, "config", Proxy.newProxyInstance(LlmConfig.class.getClassLoader(), new Class<?>[]{LlmConfig.class},
            (proxy, method, args) -> method.getName().equals("model") ? "stub" : method.getName().equals("suggestMaxTokens") ? 1000 : 0));
        PromptContext context = composer.buildSuggestContext("t", new SuggestRequest("Module m.", "en", null, null));
        assertThat(context.getSystemPrompt()).contains("Use `as`").contains("Rule r given application as LoanApplication");
        assertThat(context.getSystemPrompt()).doesNotContain("RIGHT: Define User has name, age.");
        // 生成规则/输出格式两节会把建议模式压成纯代码输出，不得注入 Suggest
        assertThat(context.getSystemPrompt()).doesNotContain("## Generation Rules").doesNotContain("## Output Format");
    }

    @Test
    void repairTemplatesNoLongerTellTheModelToDropFieldTypes() throws Exception {
        for (String locale : new String[]{"en", "zh", "de"}) {
            String template = resource("prompts/developer/policy_repair_" + locale + ".txt");
            assertThat(template).contains("as Text").doesNotContain("去掉类型").doesNotContain("Typ entfernen")
                .doesNotContain("remove type");
        }
    }

    private static String resource(String name) throws Exception {
        try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
