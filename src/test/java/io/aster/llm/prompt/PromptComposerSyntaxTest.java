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
            // 每种语言的"正确示例"都必须带类型（英/中/德本地化标签不同，示例行相同）
            assertThat(template).contains("Define User has name as Text, age as Int.")
                .doesNotContain("Define User has name, age.");
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
    void syntaxSectionsKeepFirstTwoSectionsForEveryLocale() throws Exception {
        for (String locale : new String[]{"en", "zh", "de"}) {
            String template = resource("prompts/developer/policy_gen_" + locale + ".txt");
            String[] headings = template.split("\\n## ");
            String kept = PromptComposer.syntaxSections(template);
            // 前两节（语法约束、常见错误）保留，后两节（生成规则、输出格式）截掉
            assertThat(kept).as(locale).contains("## " + headings[1].split("\\n")[0])
                .contains("## " + headings[2].split("\\n")[0])
                .doesNotContain("## " + headings[3].split("\\n")[0])
                .doesNotContain("## " + headings[4].split("\\n")[0]);
        }
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
