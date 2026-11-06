package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.typecheck.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语法诊断带共享码表 E050–E069 的码与命名参数（ADR 0051 §16 R56，aster-lang-ts#255 的 aster-api 侧）。
 *
 * <p>锁住：①每条结构化诊断都有码、参数与 1-based 行列；②文案按码从语言包 overlay
 * （{@code overlays/<locale>/diagnostic-messages.json}）取模板，缺译回退英文码表；
 * ③参数里的 {@code kw.*} 按作者语言回译，{@code token.*} 取语法目录的名词；
 * ④AstBuilder 抛出的 {@code ParseDiagnosticException} 也作为带码的用户源码错误返回，而不是内部故障。
 */
class CnlErrorCodeTest {

    private static InProcessCnlParser.CnlParseException parseFailure(String src, String locale) {
        try {
            InProcessCnlParser.parse(src, locale);
        } catch (InProcessCnlParser.CnlParseException e) {
            return e;
        }
        throw new AssertionError("期望语法错误，实际解析成功");
    }

    private static String kw(String locale, SemanticTokenKind kind) {
        Lexicon lexicon = LexiconRegistry.getInstance().getOrThrow(locale);
        return lexicon.getKeyword(kind).orElseThrow();
    }

    private static String punct(String locale) {
        return LexiconRegistry.getInstance().getOrThrow(locale).getPunctuation().statementEnd();
    }

    /** 第 4 行「Return n」缺句末标点（与 CnlErrorLocaleTest 同一条源码）。 */
    private static String missingEnd(String locale) {
        String sep = "zh-CN".equals(locale) ? "，" : ", ";
        Lexicon lx = LexiconRegistry.getInstance().getOrThrow(locale);
        return kw(locale, SemanticTokenKind.MODULE_DECL) + " m" + punct(locale) + "\n\n"
            + kw(locale, SemanticTokenKind.FUNC_TO) + " r " + kw(locale, SemanticTokenKind.FUNC_GIVEN)
            + " n " + kw(locale, SemanticTokenKind.IMPORT_ALIAS) + " " + kw(locale, SemanticTokenKind.INT_TYPE)
            + sep + kw(locale, SemanticTokenKind.FUNC_PRODUCE) + " " + kw(locale, SemanticTokenKind.INT_TYPE)
            + lx.getPunctuation().blockStart() + "\n  " + kw(locale, SemanticTokenKind.RETURN) + " n\n";
    }

    private static CnlErrorListener.Diagnostic statementEnd(String locale) {
        return parseFailure(missingEnd(locale), locale).getDiagnostics().stream()
            .filter(d -> "E059".equals(d.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(locale + " 没有 E059 诊断"));
    }

    // ---------------------------------------------------------------- 码 + 行列 + 文案（四语言）

    @Test
    void en_missingStatementEnd_isE059_withPosition() {
        CnlErrorListener.Diagnostic d = statementEnd("en-US");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.column()).isEqualTo(11);
        assertThat(d.params()).containsEntry("token", "'.'");
        assertThat(d.message()).isEqualTo("Expected '.' at end of statement");
    }

    @Test
    void zh_missingStatementEnd_isE059_fromOverlay() {
        CnlErrorListener.Diagnostic d = statementEnd("zh-CN");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.message()).isEqualTo("语句末尾应为 '。'");
    }

    @Test
    void de_missingStatementEnd_isE059_fromOverlay() {
        CnlErrorListener.Diagnostic d = statementEnd("de-DE");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.message()).isEqualTo("'.' am Ende der Anweisung erwartet");
    }

    @Test
    void hi_missingStatementEnd_isE059_fromOverlay() {
        CnlErrorListener.Diagnostic d = statementEnd("hi-IN");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.message()).isEqualTo("कथन के अंत में '।' अपेक्षित");
    }

    @Test
    void everySyntaxDiagnostic_carriesACode() {
        for (String locale : List.of("en-US", "zh-CN", "de-DE", "hi-IN")) {
            assertThat(parseFailure(missingEnd(locale), locale).getDiagnostics()).as(locale)
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.code()).matches("E0[56]\\d"));
        }
    }

    // ---------------------------------------------------------------- 关键词参数回译

    @Test
    void zh_keywordParam_isLocalized_endToEnd() {
        String src = "模块 m。\n\n规则 r 给定 x 作为 整数 产出 整数：\n  如果 x 小于 0 返回 0。\n  返回 x。";
        InProcessCnlParser.CnlParseException e = parseFailure(src, "zh-CN");
        assertThat(e.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E060");
            assertThat(d.line()).isEqualTo(4);
            assertThat(d.column()).isEqualTo(12);
            assertThat(d.params()).containsEntry("before", "kw.return").containsEntry("token", "'.'");
            assertThat(d.message()).isEqualTo("'返回' 之前应为 '。'");
        });
    }

    @Test
    void isBeforeSymbol_isE063_withHint() {
        String src = "Module m.\n\nRule r given x as Int, produce Bool:\n  If x is < 18 then Return true.\n  Return false.\n";
        InProcessCnlParser.CnlParseException e = parseFailure(src, "en-US");
        assertThat(e.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E063");
            assertThat(d.params()).containsEntry("hint", "hint.isBeforeSymbol");
            assertThat(d.message()).startsWith("`is` cannot be followed directly by a symbol");
        });
        assertThat(e.getMessage()).contains("`is` cannot be followed directly by a symbol");
    }

    // ---------------------------------------------------------------- 渲染（参数 → 文案）

    @Test
    void render_expectedSet_isJoinedAndLocalized() {
        Map<String, String> params = Map.of("token", "'x'", "expected", "'.' | ':' | token.newline");
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_EXPECTING, params, KeywordLocalizer.IDENTITY))
            .isEqualTo("Unexpected 'x'; expected '.' or ':' or newline");
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_EXPECTING, params, localizer("zh-CN")))
            .isEqualTo("意外的 'x'；期望 '。' 或 '：' 或换行");
    }

    @Test
    void render_keywordParam_usesCanonicalSpelling_inEnglish() {
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_TOKEN, Map.of("token", "kw.return"),
            KeywordLocalizer.IDENTITY)).isEqualTo("Unexpected 'Return'");
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_TOKEN, Map.of("token", "kw.else"),
            KeywordLocalizer.IDENTITY)).isEqualTo("Unexpected 'Otherwise'");
    }

    @Test
    void render_tokenName_perLanguage() {
        Map<String, String> params = Map.of("token", "token.eof");
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_TOKEN, params, KeywordLocalizer.IDENTITY))
            .isEqualTo("Unexpected end of file");
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_UNEXPECTED_TOKEN, params, localizer("de-DE")))
            .isEqualTo("Unerwartetes Dateiende");
    }

    @Test
    void render_unknownTokenName_fallsBackToReadableWords_notTheKey() {
        assertThat(CnlErrorListener.render(ErrorCode.PARSE_CALL_ARITY,
            Map.of("callee", "token.someFutureConstruct", "expected", "2", "actual", "3"), KeywordLocalizer.IDENTITY))
            .isEqualTo("some future construct takes exactly 2 argument(s), got 3");
    }

    // ---------------------------------------------------------------- AstBuilder 的带码异常

    @Test
    void astBuilderDiagnostic_isUserError_withCode_notInternalFailure() {
        String src = "Module m.\n\nRule r given x as Int, produce Int:\n  Return 99999999999999999999.\n";
        InProcessCnlParser.CnlParseException e = parseFailure(src, "en-US");
        assertThat(e.getCause()).isNull();
        assertThat(e.getDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("E053");
            assertThat(d.line()).isEqualTo(4);
            assertThat(d.params()).containsEntry("literal", "99999999999999999999");
        });
        assertThat(e.getMessage()).startsWith("CNL syntax error — Line 4");
    }

    private static KeywordLocalizer localizer(String locale) {
        return KeywordLocalizer.forTarget(LexiconRegistry.getInstance().getOrThrow(locale));
    }
}
