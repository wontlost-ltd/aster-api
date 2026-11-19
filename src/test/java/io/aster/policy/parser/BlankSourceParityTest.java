package io.aster.policy.parser;

import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostic;
import aster.core.parser.ParseDiagnostics;
import aster.core.typecheck.ErrorCode;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 「空源码」判定与 core 词法一致（aster-lang-ts#348）：core 只认 ASCII 空格与 tab 是词法空白（zh 另认 U+3000，
 * 由规范化转成半角空格）。aster-api 解析入口与编译入口曾用 {@link String#isBlank()}，把只含 U+3000 / U+2003 /
 * U+2028 等 Unicode 空白的源码当空源码拒掉，core 却对它们报 E050。
 *
 * <p>判定以 core 为准：core 对某份源码报诊断时，aster-api 必须报同码同行；core 不报诊断（词法上没有 token）时，
 * aster-api 按空源码拒绝。
 */
class BlankSourceParityTest {

    private static final List<String> LOCALES = List.of("en-US", "zh-CN", "de-DE", "hi-IN");

    /** 只含一种空白类字符的源码：ASCII 词法空白、其余 ASCII 控制空白、常见 Unicode 空白与格式字符。 */
    private static final List<String> SOURCES = List.of(
        "", " ", "  \n", "\t\n", "\r\n", "\n\n",
        "\u000B\n", "\f\n",
        "　\n", "　　", " \n", " \n", " \n", " \n", " \n",
        " \n", " \n", "\u0085\n", " \n", "​\n", "﻿\n", "\n﻿");

    @TestFactory
    Stream<DynamicTest> parserMatchesCoreOnWhitespaceOnlySources() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String locale : LOCALES) {
            for (String source : SOURCES) {
                tests.add(DynamicTest.dynamicTest(locale + " " + escape(source),
                    () -> assertParserMatchesCore(source, locale)));
            }
        }
        return tests.stream();
    }

    @Test
    void isBlankSource_onlyAsciiLexicalWhitespace() {
        assertThat(InProcessCnlParser.isBlankSource(null)).isTrue();
        assertThat(InProcessCnlParser.isBlankSource("")).isTrue();
        assertThat(InProcessCnlParser.isBlankSource(" \t\r\n")).isTrue();
        for (String s : List.of("　", " ", " ", " ", " ", "﻿", "x")) {
            assertThat(InProcessCnlParser.isBlankSource(" " + s + "\n")).as(escape(s)).isFalse();
        }
    }

    @Test
    void parser_asciiBlankSourceRejectedAsEmpty() {
        assertThatThrownBy(() -> InProcessCnlParser.parse("  \n\t\n", "en-US"))
            .isInstanceOf(InProcessCnlParser.CnlParseException.class)
            .hasMessage(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
    }

    @Test
    void parser_zhFullWidthSpaceOnlyIsEmptyAfterCanonicalization() {
        assertThatThrownBy(() -> InProcessCnlParser.parse("　\n　　\n", "zh-CN"))
            .isInstanceOf(InProcessCnlParser.CnlParseException.class)
            .hasMessage(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
    }

    @Test
    void compiler_fullWidthSpaceOnlySourceReportsE050InEnglish() {
        CompilationResult result = compiler().compile("　\n", "en-US");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).extracting(CompilationResult.Diagnostic::code).contains("E050");
        assertThat(result.getDiagnostics()).filteredOn(d -> "E050".equals(d.code()))
            .extracting(CompilationResult.Diagnostic::line).containsOnly(1);
    }

    @Test
    void compiler_asciiBlankSourceStillRejectedAsEmpty() {
        CompilationResult result = compiler().compile(" \n\t", "en-US");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).containsExactly(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
        assertThat(result.getDiagnostics()).isEmpty();
    }

    private static PolicyCompiler compiler() {
        // 源码编译路径不访问仓库，传 null 即可
        return new PolicyCompiler(null, new io.aster.policy.stability.StabilityEnforcement(),
            new io.aster.policy.analysis.RuleConflictDiagnostics());
    }

    private static void assertParserMatchesCore(String source, String locale) {
        Optional<ParseDiagnostic> core = corePrimary(source, locale);
        InProcessCnlParser.CnlParseException e = catchThrowableOfType(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(source, locale));
        assertThat(e).as("只含空白类字符的源码不应编译成功").isNotNull();
        if (core.isEmpty()) {
            assertThat(e.getDiagnostics()).as("core 不报诊断时按空源码拒绝：%s", e.getMessage()).isEmpty();
            return;
        }
        List<CnlErrorListener.Diagnostic> ds = e.getDiagnostics();
        assertThat(ds).as("core 报 %s，aster-api 必须带码：%s", core.get().code().code(), e.getMessage()).isNotEmpty();
        CnlErrorListener.Diagnostic primary = ds.stream()
            .filter(d -> !"E063".equals(d.code()))
            .findFirst()
            .orElse(ds.get(0));
        assertThat(List.of(primary.code(), primary.line()))
            .isEqualTo(List.of(core.get().code().code(), core.get().line()));
    }

    /** core 参考实现的主诊断（与 {@link ParseDiagnosticsParityTest} 同一选取规则）。 */
    private static Optional<ParseDiagnostic> corePrimary(String source, String locale) {
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(locale));
        return result.diagnostics().stream()
            .filter(d -> d.code() != ErrorCode.PARSE_NO_VIABLE)
            .findFirst()
            .or(() -> result.diagnostics().stream().findFirst());
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        s.chars().forEach(c -> sb.append(c < 0x20 || c > 0x7E ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return sb.isEmpty() ? "<empty>" : sb.toString();
    }
}
