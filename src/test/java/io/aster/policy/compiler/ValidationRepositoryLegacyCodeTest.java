package io.aster.policy.compiler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 升级前落库、没有码的语法诊断，读取时按兼容映射补码（过渡一个车次）。 */
class ValidationRepositoryLegacyCodeTest {

    private static CompilationResult.Diagnostic legacy(String message) {
        return CompilationResult.Diagnostic.error(4, 11, message);
    }

    @Test
    void legacyEnglishSyntaxMessage_getsCode() {
        CompilationResult.Diagnostic d = ValidationRepository.withLegacySyntaxCode(
            legacy("Expected '.' at end of statement"));
        assertThat(d.code()).isEqualTo("E059");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.message()).isEqualTo("Expected '.' at end of statement");
    }

    @Test
    void legacyLocalizedSyntaxMessage_getsCode() {
        assertThat(ValidationRepository.withLegacySyntaxCode(legacy("语句末尾应为 '。'")).code()).isEqualTo("E059");
        assertThat(ValidationRepository.withLegacySyntaxCode(legacy("Am Ende der Anweisung wird '.' erwartet")).code())
            .isEqualTo("E059");
        assertThat(ValidationRepository.withLegacySyntaxCode(legacy("कथन के अंत में '।' अपेक्षित है")).code())
            .isEqualTo("E059");
    }

    @Test
    void existingCode_andUnknownMessage_areUntouched() {
        CompilationResult.Diagnostic coded = new CompilationResult.Diagnostic(1, 1, 1, 1, "Unexpected 'x'",
            "E802", "error", null, null, true);
        assertThat(ValidationRepository.withLegacySyntaxCode(coded)).isSameAs(coded);
        CompilationResult.Diagnostic other = legacy("名称解析失败");
        assertThat(ValidationRepository.withLegacySyntaxCode(other)).isSameAs(other);
    }
}
