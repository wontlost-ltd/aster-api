package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语法错误文案跟随策略 lexicon 的语言（en / zh / de / hi）。
 *
 * <p>背景（生产复测第 7 轮）：英文策略保存时，编译门禁返回中文诊断（「无法解析 'Returnn' 附近的语法」
 * 「缺少 '.'（在 换行 之前）」）；zh 策略句末写的是英文 '.'；ANTLR 的无分隔拼接串把 {@code Return n}
 * 粘成 {@code 'Returnn'}。本测试锁住：
 * <ul>
 *   <li>句子骨架、token 名称、汇总前缀都用 lexicon 的语言，en 策略不出现中文；</li>
 *   <li>句末缺标点的说法与编辑器一致，标点用该语言的写法（zh「。」、hi「।」）；</li>
 *   <li>出错片段按源码里的间隔显示，相邻 token 不粘连。</li>
 * </ul>
 */
class CnlErrorLocaleTest {

    private static final Pattern CJK = Pattern.compile("\\p{IsHan}");

    private static InProcessCnlParser.CnlParseException parseFailure(String src, String locale) {
        try {
            InProcessCnlParser.parse(src, locale);
        } catch (InProcessCnlParser.CnlParseException e) {
            return e;
        }
        throw new AssertionError("期望语法错误，实际解析成功");
    }

    private static List<String> diagnostics(String src, String locale) {
        return parseFailure(src, locale).getDiagnostics().stream()
            .map(CnlErrorListener.Diagnostic::message)
            .toList();
    }

    private static String kw(String locale, SemanticTokenKind kind) {
        Lexicon lexicon = LexiconRegistry.getInstance().getOrThrow(locale);
        return lexicon.getKeyword(kind).orElseThrow();
    }

    private static String end(String locale) {
        return LexiconRegistry.getInstance().getOrThrow(locale).getPunctuation().statementEnd();
    }

    private static String colon(String locale) {
        return LexiconRegistry.getInstance().getOrThrow(locale).getPunctuation().blockStart();
    }

    /** 用 lexicon 的关键词与标点拼一条「模块 + 规则 + 末行」的源码，末行由调用方给出。 */
    private static String rule(String locale, String lastLine) {
        String sep = "zh-CN".equals(locale) ? "，" : ", ";
        return kw(locale, SemanticTokenKind.MODULE_DECL) + " m" + end(locale) + "\n\n"
            + kw(locale, SemanticTokenKind.FUNC_TO) + " r " + kw(locale, SemanticTokenKind.FUNC_GIVEN)
            + " n " + kw(locale, SemanticTokenKind.IMPORT_ALIAS) + " " + kw(locale, SemanticTokenKind.INT_TYPE)
            + sep + kw(locale, SemanticTokenKind.FUNC_PRODUCE) + " " + kw(locale, SemanticTokenKind.INT_TYPE)
            + colon(locale) + "\n  " + lastLine;
    }

    /**
     * 末行「Return n」缺句末标点、其后换行：生产里出现 'Returnn' 与中文文案的那条源码。
     * （文件在缺标点处直接结束时 ANTLR 的补全恢复会因合成 token 没有来源而抛 NPE，由 aster-lang-core 修复。）
     */
    private static String missingEnd(String locale) {
        return rule(locale, kw(locale, SemanticTokenKind.RETURN) + " n") + "\n";
    }

    // ---------------------------------------------------------------- 句末标点（四语言）

    @Test
    void en_missingStatementEnd_isEnglish_andMatchesEditorWording() {
        List<String> msgs = diagnostics(missingEnd("en-US"), "en-US");
        assertThat(msgs).contains("Expected '.' at end of statement");
        assertThat(msgs).noneMatch(m -> CJK.matcher(m).find());
    }

    @Test
    void zh_missingStatementEnd_usesChineseFullStop() {
        List<String> msgs = diagnostics(missingEnd("zh-CN"), "zh-CN");
        assertThat(msgs).contains("语句末尾应为 '。'");
        assertThat(msgs).noneMatch(m -> m.contains("'.'"));
    }

    @Test
    void de_missingStatementEnd_isGerman() {
        List<String> msgs = diagnostics(missingEnd("de-DE"), "de-DE");
        assertThat(msgs).contains("Am Ende der Anweisung wird '.' erwartet");
        assertThat(msgs).noneMatch(m -> CJK.matcher(m).find());
    }

    @Test
    void hi_missingStatementEnd_isHindi_andUsesDanda() {
        List<String> msgs = diagnostics(missingEnd("hi-IN"), "hi-IN");
        assertThat(msgs).contains("कथन के अंत में '।' अपेक्षित है");
        assertThat(msgs).noneMatch(m -> CJK.matcher(m).find());
    }

    // ---------------------------------------------------------------- token 粘连（Returnn）

    @Test
    void missingStatementEnd_isTheReportedRootCause_overTheCascade() {
        // ANTLR 先在下一行报 no viable，再报缺句末标点；对外的整句应指向后者（与编辑器同一条）
        assertThat(parseFailure(missingEnd("en-US"), "en-US").getMessage())
            .contains("Expected '.' at end of statement");
        assertThat(parseFailure(missingEnd("zh-CN"), "zh-CN").getMessage())
            .contains("语句末尾应为 '。'");
    }

    @Test
    void noViableInput_keepsSourceSpacing_notReturnn() {
        for (String locale : List.of("en-US", "zh-CN", "de-DE", "hi-IN")) {
            String ret = kw(locale, SemanticTokenKind.RETURN);
            InProcessCnlParser.CnlParseException e = parseFailure(missingEnd(locale), locale);
            String all = e.getMessage() + "\n" + String.join("\n", diagnostics(missingEnd(locale), locale));
            assertThat(all).as(locale).doesNotContain("Returnn").doesNotContain(ret + "n'");
        }
    }

    @Test
    void humanize_noViable_usesRenderedInput_whenGiven() {
        String msg = CnlErrorListener.humanize("no viable alternative at input 'Returnn'", false,
            KeywordLocalizer.IDENTITY, false, "Return n");
        assertThat(msg).isEqualTo("Cannot parse the syntax near 'Return n'");
    }

    // ---------------------------------------------------------------- 句子骨架与汇总前缀

    @Test
    void en_extraneousToken_isEnglish() {
        // 生产实例：`Define Req has attrs as Map of Text to Int.` → 曾返回「多余的 'of'」
        String src = "Module m.\n\nDefine Req has attrs as Map of Text to Int.\n";
        InProcessCnlParser.CnlParseException e = parseFailure(src, "en-US");
        assertThat(e.getMessage()).startsWith("CNL syntax error — Line ");
        assertThat(e.getMessage()).doesNotContainPattern(CJK);
        assertThat(e.getDiagnostics()).isNotEmpty()
            .allSatisfy(d -> assertThat(d.message()).doesNotContainPattern(CJK));
    }

    @Test
    void summaryPrefix_followsLexiconLanguage() {
        assertThat(parseFailure(missingEnd("zh-CN"), "zh-CN").getMessage()).startsWith("CNL 语法错误 — 行 ");
        assertThat(parseFailure(missingEnd("de-DE"), "de-DE").getMessage()).startsWith("CNL-Syntaxfehler — Zeile ");
        assertThat(parseFailure(missingEnd("hi-IN"), "hi-IN").getMessage()).startsWith("CNL सिंटैक्स त्रुटि — पंक्ति ");
    }

    @Test
    void unknownLocale_fallsBackToEnglish() {
        InProcessCnlParser.CnlParseException e = parseFailure(missingEnd("en-US"), null);
        assertThat(e.getMessage()).startsWith("CNL syntax error — ").doesNotContainPattern(CJK);
    }

    @Test
    void humanize_mismatched_perLanguage() {
        String raw = "mismatched input 'x' expecting {'.', ':'}";
        assertThat(CnlErrorListener.humanize(raw, false, KeywordLocalizer.IDENTITY, false))
            .isEqualTo("Unexpected 'x'; expected '.' or ':'");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("zh-CN"), false))
            .isEqualTo("意外的 'x'，此处应为 '。' 或 '：'");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("de-DE"), false))
            .isEqualTo("Unerwartet: 'x'; erwartet wird '.' oder ':'");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("hi-IN"), false))
            .isEqualTo("अप्रत्याशित 'x'; यहाँ '।' या ':' अपेक्षित है");
    }

    @Test
    void humanize_missingBeforeToken_perLanguage() {
        String raw = "missing ':' at 'x'";
        assertThat(CnlErrorListener.humanize(raw, false, KeywordLocalizer.IDENTITY, false))
            .isEqualTo("Expected ':' before 'x'");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("zh-CN"), false))
            .isEqualTo("'x' 之前应为 '：'");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("de-DE"), false))
            .isEqualTo("Vor 'x' wird ':' erwartet");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("hi-IN"), false))
            .isEqualTo("'x' से पहले ':' अपेक्षित है");
    }

    @Test
    void expectingSet_commaLiteral_isOneItem_notSplit() {
        // 按逗号切会把 ',' 拆成两个空项（曾显示成「':' 或 input 或 newline, …」）
        String raw = "mismatched input '.' expecting {':', ',', NEWLINE}";
        assertThat(CnlErrorListener.humanize(raw, false, KeywordLocalizer.IDENTITY, false))
            .isEqualTo("Unexpected '.'; expected ':' or ',' or newline");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("zh-CN"), true))
            .isEqualTo("意外的 '。'，此处应为 '：' 或 '，' 或换行");
    }

    @Test
    void zh_offendingPunctuation_isShownInChinese() {
        // 作者写的是「。」，canonicalize 后 ANTLR 只见 '.'；报错里要还原成作者的写法
        assertThat(CnlErrorListener.humanize("mismatched input '.' expecting IDENT", false, localizer("zh-CN"), true))
            .isEqualTo("意外的 '。'，此处应为标识符");
    }

    @Test
    void tokenNames_perLanguage() {
        String raw = "extraneous input '<EOF>' expecting NEWLINE";
        assertThat(CnlErrorListener.humanize(raw, false, KeywordLocalizer.IDENTITY, false))
            .isEqualTo("Unexpected extra end of file");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("zh-CN"), false))
            .isEqualTo("多余的文件结尾");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("de-DE"), false))
            .isEqualTo("Überzählig: Dateiende");
        assertThat(CnlErrorListener.humanize(raw, false, localizer("hi-IN"), false))
            .isEqualTo("अतिरिक्त फ़ाइल का अंत");
    }

    // ---------------------------------------------------------------- 根因选择（多处错误）

    @Test
    void earlierUnrelatedError_isNotOverriddenByLaterMissingStatementEnd() {
        // 第 3 行 Map 写法错误 + 第 6 行缺句末标点：缺句点只修正「下一行先报 no viable」，不能盖过更早的真错误
        String src = "Module m.\n\nDefine Req has attrs as Map of Text to Int.\n\n"
            + "Rule r given n as Int, produce Int:\n  Return n\n";
        String msg = parseFailure(src, "en-US").getMessage();
        assertThat(msg).startsWith("CNL syntax error — Line 3, column 29: Unexpected extra 'of'");
        assertThat(diagnostics(src, "en-US")).contains("Expected '.' at end of statement");
    }

    // ---------------------------------------------------------------- 单复数

    @Test
    void oneMoreError_isSingular_perLanguage() {
        // 缺句点场景恰好是「根因 + 1 处后续错误」
        assertThat(parseFailure(missingEnd("en-US"), "en-US").getMessage())
            .endsWith("(1 more error follows; fix this one first)");
        assertThat(parseFailure(missingEnd("de-DE"), "de-DE").getMessage())
            .endsWith("(1 weiterer Folgefehler; beheben Sie zuerst diesen)");
        assertThat(parseFailure(missingEnd("hi-IN"), "hi-IN").getMessage())
            .endsWith("(इसके बाद 1 और त्रुटि है; पहले इसे ठीक करें)");
        assertThat(parseFailure(missingEnd("zh-CN"), "zh-CN").getMessage())
            .endsWith("（另有 1 处后续错误，建议先修正此处）");
    }

    @Test
    void severalMoreErrors_isPlural() {
        String src = "Module m.\n\nDefine Req has attrs as Map of Text to Int.\n\n"
            + "Rule r given n as Int, produce Int:\n  Return n\n";
        assertThat(parseFailure(src, "en-US").getMessage()).endsWith("(2 more errors follow; fix this one first)");
    }

    // ---------------------------------------------------------------- 输入形态

    @Test
    void crlfLineEndings_sameDiagnosticsAsLf() {
        for (String locale : List.of("en-US", "zh-CN", "de-DE", "hi-IN")) {
            String crlf = missingEnd(locale).replace("\n", "\r\n");
            assertThat(diagnostics(crlf, locale)).as(locale).isEqualTo(diagnostics(missingEnd(locale), locale));
        }
    }

    @Test
    void tabIndentation_givesLocalizedSyntaxError() {
        String src = "Module m.\n\nRule r given n as Int, produce Int:\n\tReturn n\n";
        InProcessCnlParser.CnlParseException e = parseFailure(src, "en-US");
        assertThat(e.getMessage()).startsWith("CNL syntax error — Line ").doesNotContainPattern(CJK);
        assertThat(e.getDiagnostics()).isNotEmpty()
            .allSatisfy(d -> assertThat(d.message()).doesNotContainPattern(CJK).doesNotContain("Returnn"));
    }

    @Test
    void unsupportedLocale_fallsBackToEnglishText() {
        InProcessCnlParser.CnlParseException e = parseFailure(missingEnd("en-US"), "fr-FR");
        assertThat(e.getMessage()).startsWith("CNL syntax error — ").doesNotContainPattern(CJK);
        assertThat(e.getDiagnostics()).extracting(CnlErrorListener.Diagnostic::message)
            .contains("Expected '.' at end of statement");
    }

    @Test
    void everyLanguage_definesEveryMessageKey() {
        SyntaxMessages en = SyntaxMessages.forLanguage("en");
        for (String lang : List.of("zh", "de", "hi")) {
            assertThat(SyntaxMessages.forLanguage(lang).keys()).as(lang).isEqualTo(en.keys());
        }
    }

    private static KeywordLocalizer localizer(String locale) {
        return KeywordLocalizer.forTarget(LexiconRegistry.getInstance().getOrThrow(locale));
    }
}
