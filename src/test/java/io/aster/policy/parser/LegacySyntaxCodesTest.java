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
}
