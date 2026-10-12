package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语法错误中的关键词必须用作者所写语言的拼写；用户的名字原样保留。
 *
 * <p>背景：Canonicalizer 先把 zh/de 关键词归一成英文规范拼写再交给 ANTLR，
 * 于是错误里出现的是用户从未写过的 'Return'。本测试锁住：
 * <ul>
 *   <li>出错 token、expecting 集合（含裸符号名）、missing 'X' 里的关键词回译成 locale 拼写；</li>
 *   <li>带字面量名的关键词规则（如 TO_WORD: 'to'）同样被识别为关键词；</li>
 *   <li>与关键词同形的用户类型名（Given）不被回译；</li>
 *   <li>单行条件提示只在「If 条件, Return …」时出现，不误伤 Otherwise 分支与无逗号写法；</li>
 *   <li>en-US 文案不变。</li>
 * </ul>
 */
class CnlErrorLocalizedKeywordTest {

    private static final String ZH_HEADER = "模块 m。\n\n规则 r 给定 x 作为 整数 产出 整数：\n";
    private static final String INLINE_HINT = "单行条件请写成";

    private static String parseErr(String src, String locale) {
        try {
            InProcessCnlParser.parse(src, locale);
            return "<no error>";
        } catch (InProcessCnlParser.CnlParseException e) {
            return e.getMessage();
        }
    }

    private static Lexicon lexicon(String id) {
        return LexiconRegistry.getInstance().getOrThrow(id);
    }

    private static String kw(String locale, SemanticTokenKind kind) {
        return lexicon(locale).getKeyword(kind).orElseThrow();
    }

    // ---------------------------------------------------------------- 端到端

    @Test
    void zh_inlineCommaReturn_namesChineseKeyword_andSuggestsThen() {
        String err = parseErr(ZH_HEADER + "  如果 x 小于 0，返回 0 减去 x。\n  返回 x。", "zh-CN");
        assertThat(err)
            .contains("意外的 '返回'")
            .doesNotContain("'Return'")
            .contains("单行条件请写成「如果 条件 那么 返回 …」");
    }

    @Test
    void de_inlineCommaReturn_namesGermanKeyword() {
        String ret = kw("de-de", SemanticTokenKind.RETURN);
        String src = String.join("\n",
            kw("de-de", SemanticTokenKind.MODULE_DECL) + " m.",
            "",
            kw("de-de", SemanticTokenKind.FUNC_TO) + " r " + kw("de-de", SemanticTokenKind.FUNC_GIVEN)
                + " x " + kw("de-de", SemanticTokenKind.IMPORT_ALIAS) + " " + kw("de-de", SemanticTokenKind.INT_TYPE)
                + ", " + kw("de-de", SemanticTokenKind.FUNC_PRODUCE) + " " + kw("de-de", SemanticTokenKind.INT_TYPE) + ":",
            "  " + kw("de-de", SemanticTokenKind.IF) + " x < 0, " + ret + " 0.",
            "  " + ret + " x.");
        assertThat(parseErr(src, "de-DE"))
            .contains("意外的 '" + ret + "'")
            .doesNotContain("'Return'")
            .contains(kw("de-de", SemanticTokenKind.THEN));
    }

    @Test
    void en_inlineCommaReturn_unchangedKeyword_andSuggestsThen() {
        String err = parseErr(
            "Module m.\n\nRule r given x as Int, produce Int:\n  If x less than 0, Return 0 minus x.\n  Return x.",
            "en-US");
        assertThat(err).contains("意外的 'Return'").contains("「If 条件 then Return …」");
    }

    @Test
    void zh_otherwiseCommaReturn_getsNoInlineIfHint() {
        // else 分支写成「否则，返回 x。」时，提示「如果 … 那么」是错的
        String err = parseErr(ZH_HEADER + "  如果 x 小于 0：\n    返回 1。\n  否则，返回 x。", "zh-CN");
        assertThat(err).contains("'返回'").doesNotContain(INLINE_HINT);
    }

    @Test
    void en_otherwiseCommaReturn_getsNoInlineIfHint() {
        String err = parseErr(
            "Module m.\n\nRule r given x as Int, produce Int:\n  If x less than 0:\n    Return 1.\n  Otherwise, Return x.",
            "en-US");
        assertThat(err).contains("'Return'").doesNotContain(INLINE_HINT);
    }

    @Test
    void zh_ifReturnWithoutComma_getsNoInlineIfHint() {
        // 提示只针对「条件后逗号接返回」这一具体写法
        String err = parseErr(ZH_HEADER + "  如果 x 小于 0 返回 0。\n  返回 x。", "zh-CN");
        assertThat(err).startsWith("CNL 语法错误").doesNotContain(INLINE_HINT);
    }

    @Test
    void zh_literalNamedKeyword_toWord_isLocalized() {
        // TO_WORD: 'to' 是带字面量名的关键词规则，曾被「符号名 == 文本大写」判据漏掉
        String err = parseErr(ZH_HEADER + "  令 y 定义为 x 设为 1。\n  返回 y。", "zh-CN");
        assertThat(err).contains("'设为'").doesNotContain("'to'");
    }

    @Test
    void zh_typeIdentSameAsKeyword_isNotRewritten() {
        // Given 是 TYPE_IDENT（GIVEN 规则是区分大小写的 'given'），小写后与规范关键词同形，
        // 但它是用户写的名字，必须原样显示
        String err = parseErr("模块 m。\n\n规则 r Given x 作为 整数 产出 整数：\n  返回 x。", "zh-CN");
        assertThat(err).contains("'Given'").doesNotContain("'给定'");
    }

    // ---------------------------------------------------------------- 文案格式化单元

    private static final KeywordLocalizer ZH = KeywordLocalizer.forTarget(lexicon("zh-cn"));

    @Test
    void humanize_expectingSet_quotedKeywords_areLocalized() {
        String msg = CnlErrorListener.humanize("mismatched input 'x' expecting {'given', 'produce'}", false, ZH, false);
        assertThat(msg).contains("'给定'").contains("'产出'").doesNotContain("'given'");
    }

    @Test
    void humanize_expectingSet_bareSymbolicKeyword_isLocalized() {
        String msg = CnlErrorListener.humanize("mismatched input 'x' expecting RETURN", false, ZH, false);
        assertThat(msg).contains("'返回'").doesNotContain("RETURN");
    }

    @Test
    void humanize_missingKeyword_isLocalized() {
        String msg = CnlErrorListener.humanize("missing 'produce' at 'x'", false, ZH, false);
        assertThat(msg).contains("缺少 '产出'").contains("'x'");
    }

    @Test
    void humanize_english_isUnchanged() {
        String msg = CnlErrorListener.humanize("mismatched input 'x' expecting {'given', 'produce'}", false,
            KeywordLocalizer.IDENTITY, false);
        assertThat(msg).isEqualTo("意外的 'x'，此处期望 'given' 或 'produce'");
    }

    @Test
    void humanize_expectingSet_bareSymbolicNames_resolveThroughLexerTable() {
        // ELSE 的拼写是 Otherwise、MODULE_KW 是 Module、PLUS_WORD 是 plus：
        // 都不能靠「符号名小写」碰巧命中，必须经由符号名 → 规范拼写表
        String msg = CnlErrorListener.humanize(
            "mismatched input 'x' expecting {ELSE, MODULE_KW, PLUS_WORD}", false, ZH, false);
        assertThat(msg).isEqualTo("意外的 'x'，此处期望 '否则' 或 '模块' 或 '加上'");
    }

    @Test
    void humanize_sharedTokenType_hasDeterministicPreference() {
        // GREATER_THAN 与 MORE_THAN 等多个语义可能落到同一 token 类型；按枚举声明顺序，先声明者胜出
        assertThat(KeywordLocalizer.KeywordTokens.get().symbolicToCanonical)
            .containsEntry("GREATER_THAN_WORD", "greater than");
        String msg = CnlErrorListener.humanize("mismatched input 'x' expecting GREATER_THAN_WORD", false, ZH, false);
        assertThat(msg).isEqualTo("意外的 'x'，此处期望 '大于'");
    }

    @Test
    void inKindOrder_followsEnumDeclarationOrder() {
        var entries = KeywordLocalizer.inKindOrder(lexicon("en-us"));
        assertThat(entries).isNotEmpty();
        for (int i = 1; i < entries.size(); i++) {
            assertThat(entries.get(i - 1).getKey().ordinal()).isLessThan(entries.get(i).getKey().ordinal());
        }
    }

    @Test
    void humanize_english_bareSymbolicNames_areUnchanged() {
        // en-US 不经过符号名表：RETURN 照旧显示为 'RETURN'（不变成 'Return'）
        String msg = CnlErrorListener.humanize("mismatched input 'x' expecting RETURN", false,
            KeywordLocalizer.IDENTITY, false);
        assertThat(msg).isEqualTo("意外的 'x'，此处期望 'RETURN'");
    }
}
