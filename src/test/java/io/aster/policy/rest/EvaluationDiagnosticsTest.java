package io.aster.policy.rest;

import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.rest.model.EvaluationResponse;
import io.aster.policy.module.ModuleResolutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * evaluate 系列 400 诊断按请求语言出文案（aster-api#445）：code 稳定不变，message 跟随
 * 请求 locale（evaluate-source 的 body.locale）或 Accept-Language，未知语言退回英文。
 */
class EvaluationDiagnosticsTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "zh-CN, NULL, zh",
        "de-DE, 'zh-CN,zh;q=0.9', de",
        "NULL, 'de-DE,de;q=0.9,en;q=0.8', de",
        "NULL, 'hi-IN', hi",
        "'  ', 'zh', zh",
        "NULL, NULL, en",
        "fr-FR, NULL, en",
        "NULL, 'fr-FR,zh;q=0.5', zh",
        "NULL, '*', en",
    })
    void 语言解析_body优先其次AcceptLanguage(String bodyLocale, String acceptLanguage, String expected) {
        assertThat(EvaluationDiagnostics.language(bodyLocale, acceptLanguage)).isEqualTo(expected);
    }

    @Test
    void 入口歧义_英文请求不再返回中文且code稳定() {
        EvaluationResponse r = EvaluationDiagnostics.ambiguous(List.of("a", "b"), "en");
        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("ENTRY_AMBIGUOUS");
            assertThat(d.candidates()).containsExactly("a", "b");
            assertThat(d.message()).isEqualTo("No entry function specified and the candidate functions are ambiguous");
        });
        assertThat(r.error()).isEqualTo("Entry function is ambiguous");
    }

    @Test
    void 入口歧义_中文请求保持原中文文案() {
        EvaluationResponse r = EvaluationDiagnostics.ambiguous(List.of("a"), "zh");
        assertThat(r.error()).isEqualTo("入口函数不唯一");
        assertThat(r.diagnostics().get(0).message()).isEqualTo("未指定入口函数，候选函数不唯一");
    }

    @Test
    void 非法agent_按语言出文案并带字段名() {
        var e = catchThrowableOfType(IllegalArgumentException.class,
            () -> AgentIdentity.validated(new AgentIdentity(null, "m", null, null)));
        EvaluationResponse en = EvaluationDiagnostics.invalidAgent(e, "en");
        assertThat(en.diagnostics().get(0).code()).isEqualTo("invalid_agent");
        assertThat(en.error()).isEqualTo("agent.provider is required");
        assertThat(EvaluationDiagnostics.invalidAgent(e, "zh").error()).isEqualTo("agent.provider 必填");
        assertThat(EvaluationDiagnostics.invalidAgent(e, "de").error()).doesNotContain("必填").contains("agent.provider");

        var tooLong = catchThrowableOfType(IllegalArgumentException.class,
            () -> AgentIdentity.validated(new AgentIdentity("p", "x".repeat(AgentIdentity.MAX_TEXT + 1), null, null)));
        assertThat(EvaluationDiagnostics.invalidAgent(tooLong, "en").error())
            .isEqualTo("agent.model exceeds " + AgentIdentity.MAX_TEXT + " characters");

        var ctrl = catchThrowableOfType(IllegalArgumentException.class,
            () -> AgentIdentity.validated(new AgentIdentity("p", "m\u0001", null, null)));
        assertThat(EvaluationDiagnostics.invalidAgent(ctrl, "hi").error()).contains("agent.model").doesNotContain("控制");
    }

    // ---------------------------------------------------------------- MODULE_*（aster-api#452）

    private static String moduleMessage(ModuleResolutionException e, String lang) {
        return EvaluationDiagnostics.module(e, lang).diagnostics().get(0).message();
    }

    /** module.* 文案随 aster-lang-locales 1.0.42 发布；更早的目录没有时这些译文用例跳过（英文与回退用例照跑）。 */
    private static void assumeModuleCatalog() {
        assumeTrue(EvaluationDiagnostics.keys("zh").contains("module.NOT_VISIBLE"),
            "需要 aster-lang-locales ≥ 1.0.42 的 module.* 文案");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ModuleResolutionException.Reason.class,
        names = {"TENANT_REQUIRED", "NOT_VISIBLE", "ALIASES_UNSUPPORTED", "PARSE_FAILED", "RESOLVER_UNAVAILABLE"})
    void 模块不可见_五种原因对外一律NOT_VISIBLE与通用文案_ADR0015(ModuleResolutionException.Reason reason) {
        var e = new ModuleResolutionException(reason, "internal detail for " + reason);
        for (String lang : List.of("en", "zh", "de", "hi")) {
            EvaluationResponse r = EvaluationDiagnostics.module(e, lang);
            assertThat(r.diagnostics()).singleElement().satisfies(d -> {
                assertThat(d.code()).isEqualTo("MODULE_NOT_VISIBLE");
                assertThat(d.reason()).isEqualTo("NOT_VISIBLE");
                assertThat(d.candidates()).isEmpty();
                String expected = EvaluationDiagnostics.keys("en").contains("module.NOT_VISIBLE")
                    ? EvaluationDiagnostics.text(lang, "module.NOT_VISIBLE") : EvaluationDiagnostics.NOT_VISIBLE_FALLBACK;
                assertThat(d.message()).isEqualTo(expected).doesNotContain("internal detail");
            });
        }
        assertThat(moduleMessage(e, "en")).isEqualTo("Imported module is not visible");
        // 细分原因只进服务端日志
        assertThat(EvaluationDiagnostics.moduleLogFields(e))
            .contains("code=MODULE_NOT_VISIBLE").contains("reason=" + reason.name());
    }

    /** 400 响应体（与 PolicyEvaluationResource 的 entity 同一对象）按 Jackson 序列化后的 JSON 树。 */
    private static com.fasterxml.jackson.databind.JsonNode body(ModuleResolutionException e, String lang) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(EvaluationDiagnostics.module(e, lang));
    }

    @Test
    void 模块不可见与模块不存在_400响应体逐字段相同_ADR0015() {
        // 「不存在」：本租户没有该模块的任何可见版本（候选为空）——与 5 种可见性原因对外不可区分
        List<ModuleResolutionException> cases = List.of(
            new ModuleResolutionException(ModuleResolutionException.Reason.NOT_FOUND, "Imported module was not found"),
            new ModuleResolutionException(ModuleResolutionException.Reason.TENANT_REQUIRED, "a"),
            new ModuleResolutionException(ModuleResolutionException.Reason.NOT_VISIBLE, "b"),
            new ModuleResolutionException(ModuleResolutionException.Reason.ALIASES_UNSUPPORTED, "c"),
            new ModuleResolutionException(ModuleResolutionException.Reason.PARSE_FAILED, "d"),
            new ModuleResolutionException(ModuleResolutionException.Reason.RESOLVER_UNAVAILABLE, "e"));
        for (String lang : List.of("en", "zh", "de", "hi")) {
            var expected = body(cases.get(0), lang);
            assertThat(expected.at("/diagnostics/0/code").asText()).isEqualTo("MODULE_NOT_VISIBLE");
            assertThat(expected.at("/diagnostics/0/reason").asText()).isEqualTo("NOT_VISIBLE");
            assertThat(expected.at("/diagnostics/0/candidates")).isEmpty();
            for (ModuleResolutionException e : cases) {
                assertThat(body(e, lang)).as("%s %s", lang, e.reason()).isEqualTo(expected);
            }
        }
    }

    @Test
    void 模块不可见_目录缺NOT_VISIBLE时回退固定英文_不用异常原文() {
        var e = new ModuleResolutionException(ModuleResolutionException.Reason.PARSE_FAILED,
            "Imported module could not be parsed");
        EvaluationResponse r = EvaluationDiagnostics.module(e, "zh", (lang, key) -> null);
        assertThat(r.diagnostics().get(0).message()).isEqualTo("Imported module is not visible");
        assertThat(r.diagnostics().get(0).reason()).isEqualTo("NOT_VISIBLE");
        assertThat(r.error()).isEqualTo("Imported module is not visible");
    }

    @Test
    void 模块错误_非不可见类原因对外保留细分() {
        var path = new ModuleResolutionException(ModuleResolutionException.Reason.EMPTY_PATH,
            "Imported module path is empty");
        EvaluationResponse r = EvaluationDiagnostics.module(path, "en");
        assertThat(r.diagnostics().get(0).code()).isEqualTo("MODULE_VERSION_NOT_FOUND");
        assertThat(r.diagnostics().get(0).reason()).isEqualTo("EMPTY_PATH");
        assertThat(r.diagnostics().get(0).message()).isEqualTo("Imported module path is empty");
    }

    @Test
    void 模块错误_按请求语言本地化并填参数() {
        assumeModuleCatalog();
        var cycle = new ModuleResolutionException(ModuleResolutionException.Reason.IMPORT_CYCLE,
            "Module import cycle detected: a@1 -> b@1 -> a@1", Map.of("cycle", "a@1 -> b@1 -> a@1"), List.of(), null);
        assertThat(moduleMessage(cycle, "zh")).isEqualTo("检测到模块循环导入：a@1 -> b@1 -> a@1");
        assertThat(moduleMessage(cycle, "de")).isEqualTo("Zyklischer Modulimport erkannt: a@1 -> b@1 -> a@1");
        assertThat(moduleMessage(cycle, "hi")).isEqualTo("मॉड्यूल आयात चक्र मिला: a@1 -> b@1 -> a@1");
        var notFound = new ModuleResolutionException(ModuleResolutionException.Reason.VERSION_NOT_FOUND,
            "Imported module version was not found", Map.of(), List.of("1", "2"), null);
        EvaluationResponse zh = EvaluationDiagnostics.module(notFound, "zh");
        assertThat(zh.diagnostics().get(0).message()).isEqualTo("未找到导入的模块版本");
        assertThat(zh.diagnostics().get(0).candidates()).containsExactly("1", "2");
        assertThat(zh.error()).isEqualTo("未找到导入的模块版本");
    }

    @Test
    void 模块错误_不可见文案不含模块名() {
        assumeModuleCatalog();
        var e = new ModuleResolutionException(ModuleResolutionException.Reason.NOT_VISIBLE,
            "Imported module is not visible");
        for (String lang : List.of("en", "zh", "de", "hi")) {
            assertThat(moduleMessage(e, lang)).as(lang).doesNotContain("{").doesNotContain("@");
        }
    }

    @Test
    void 模块错误_缺key时退回英文原文() {
        // 旧构造器 + 目录里没有的 key（模拟引擎新增原因先于文案发布）：message 用 getMessage()
        var legacy = new ModuleResolutionException(ModuleResolutionException.Code.MODULE_CYCLE,
            "engine says: link failed");
        assertThat(EvaluationDiagnostics.moduleText(legacy, "zh", "module.__missing__"))
            .isEqualTo("engine says: link failed");
    }

    @Test
    void 各语言目录key齐全() {
        var en = EvaluationDiagnostics.keys("en");
        assertThat(en).isNotEmpty();
        for (String lang : List.of("zh", "de", "hi")) {
            assertThat(EvaluationDiagnostics.keys(lang)).as(lang).isEqualTo(en);
        }
    }
}
