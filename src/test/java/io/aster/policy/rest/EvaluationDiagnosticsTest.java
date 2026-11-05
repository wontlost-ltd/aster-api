package io.aster.policy.rest;

import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.rest.model.EvaluationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

    @Test
    void 各语言目录key齐全() {
        var en = EvaluationDiagnostics.keys("en");
        assertThat(en).isNotEmpty();
        for (String lang : List.of("zh", "de", "hi")) {
            assertThat(EvaluationDiagnostics.keys(lang)).as(lang).isEqualTo(en);
        }
    }
}
