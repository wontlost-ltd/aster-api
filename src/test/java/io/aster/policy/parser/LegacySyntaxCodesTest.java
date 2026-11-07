package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 过渡期兼容映射（一个车次）：旧句型 key / 旧英文原句 → 码，用于读取升级前落库、没有码的语法诊断。 */
class LegacySyntaxCodesTest {

    @Test
    void legacyKey_mapsToCode() {
        assertThat(LegacySyntaxCodes.codeForKey("expectedAtEndOfStatement")).isEqualTo("E059");
        assertThat(LegacySyntaxCodes.codeForKey("extraneous")).isEqualTo("E061");
        assertThat(LegacySyntaxCodes.codeForKey("isBeforeSymbol")).isEqualTo("E063");
        assertThat(LegacySyntaxCodes.codeForKey("summary")).isNull();
    }

    @Test
    void legacyEnglishSentence_mapsToCode() {
        assertThat(LegacySyntaxCodes.codeForMessage("Expected '.' at end of statement")).isEqualTo("E059");
        assertThat(LegacySyntaxCodes.codeForMessage("Unexpected extra 'of'")).isEqualTo("E061");
        assertThat(LegacySyntaxCodes.codeForMessage("Unexpected 'x'; expected '.' or ':'")).isEqualTo("E062");
        assertThat(LegacySyntaxCodes.codeForMessage("Cannot parse the syntax near 'Return n'")).isEqualTo("E063");
        assertThat(LegacySyntaxCodes.codeForMessage("Incorrect indentation: one level too deep or inconsistent. "
            + "Indent with 2 spaces")).isEqualTo("E052");
        assertThat(LegacySyntaxCodes.codeForMessage("E802 something else")).isNull();
        assertThat(LegacySyntaxCodes.codeForMessage(null)).isNull();
    }

    @Test
    void legacyLocalizedSentence_mapsToCode() {
        // 落库诊断的 message 按策略 locale 渲染（typecheckVersion → compile(content, version.locale)），
        // 升级前的 zh/de/hi 原句同样要能补码；zh 旧排版在引号两侧补过空格
        assertThat(LegacySyntaxCodes.codeForMessage("语句末尾应为 '。'")).isEqualTo("E059");
        assertThat(LegacySyntaxCodes.codeForMessage("意外的 'x'，此处应为 '。' 或 '：'")).isEqualTo("E062");
        assertThat(LegacySyntaxCodes.codeForMessage("多余的文件结尾")).isEqualTo("E061");
        assertThat(LegacySyntaxCodes.codeForMessage("Am Ende der Anweisung wird '.' erwartet")).isEqualTo("E059");
        assertThat(LegacySyntaxCodes.codeForMessage("Überzählig: 'of'")).isEqualTo("E061");
        assertThat(LegacySyntaxCodes.codeForMessage("कथन के अंत में '।' अपेक्षित है")).isEqualTo("E059");
        assertThat(LegacySyntaxCodes.codeForMessage("'x' के पास का सिंटैक्स समझा नहीं जा सका")).isEqualTo("E063");
    }

    /**
     * aster-api#467：英文模板去空白后 {@code expectedIn} 曾变成 {@code ^Expected.+in.+$}，参数里含 {@code in}
     * 子串的原句（newline / string …）被它先认走。模板字面里的空白必须真的对应原句里的词边界。
     */
    @Test
    void englishTemplateOverlap_parametersContainingIn_mapToOwnCode() {
        assertThat(LegacySyntaxCodes.codeForMessage("Expected newline")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected string")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected newline before identifier")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected newline before 'x'")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected '.' after 'string'")).isEqualTo("E056");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected newline after 'string'")).isEqualTo("E056");
        // 真正的 E057 / E058 句子不受影响
        assertThat(LegacySyntaxCodes.codeForMessage("Expected '.' in rule header")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected newline in function body")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected '.' after 'string' in rule header")).isEqualTo("E058");
    }
}
