package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语法错误中的关键词必须用作者所写语言的拼写。
 *
 * <p>背景：Canonicalizer 先把 zh/de 关键词归一成英文规范拼写再交给 ANTLR，
 * 于是错误里出现的是用户从未写过的 'Return'。zh 作者写的是「返回」，
 * 看到 'Return' 无从对应。本测试锁住：报错 token 与期望 token 都回译成 locale 拼写，
 * 且对「条件后直接逗号接返回」这一常见写法给出可操作的单行写法提示。
 */
class CnlErrorLocalizedKeywordTest {

    private static String parseErr(String src, String locale) {
        try {
            InProcessCnlParser.parse(src, locale);
            return "<no error>";
        } catch (InProcessCnlParser.CnlParseException e) {
            return e.getMessage();
        }
    }

    private static String kw(String locale, SemanticTokenKind kind) {
        Lexicon lexicon = LexiconRegistry.getInstance().getOrThrow(locale);
        return lexicon.getKeyword(kind).orElseThrow();
    }

    @Test
    void zh_inlineCommaReturn_namesChineseKeyword_andSuggestsThen() {
        String err = parseErr(
            "模块 m。\n\n规则 r 给定 x 作为 整数 产出 整数：\n  如果 x 小于 0，返回 0 减去 x。\n  返回 x。",
            "zh-CN");
        assertThat(err)
            .contains("'返回'")
            .doesNotContain("'Return'")
            // 单行条件的正确写法提示：如果 条件 那么 返回 …
            .contains("那么");
    }

    @Test
    void de_inlineCommaReturn_namesGermanKeyword() {
        String ret = kw("de-de", SemanticTokenKind.RETURN);
        String then = kw("de-de", SemanticTokenKind.THEN);
        String src = String.join("\n",
            kw("de-de", SemanticTokenKind.MODULE_DECL) + " m.",
            "",
            kw("de-de", SemanticTokenKind.FUNC_TO) + " r " + kw("de-de", SemanticTokenKind.FUNC_GIVEN)
                + " x " + kw("de-de", SemanticTokenKind.IMPORT_ALIAS) + " " + kw("de-de", SemanticTokenKind.INT_TYPE)
                + ", " + kw("de-de", SemanticTokenKind.FUNC_PRODUCE) + " " + kw("de-de", SemanticTokenKind.INT_TYPE) + ":",
            "  " + kw("de-de", SemanticTokenKind.IF) + " x < 0, " + ret + " 0.",
            "  " + ret + " x.");
        String err = parseErr(src, "de-DE");
        assertThat(err).startsWith("CNL 语法错误");
        if (!ret.equalsIgnoreCase("Return")) {
            assertThat(err).contains("'" + ret + "'").doesNotContain("'Return'");
        }
        assertThat(err).containsIgnoringCase(then);
    }

    @Test
    void en_unchanged_keepsCanonicalKeyword_andSuggestsThen() {
        String err = parseErr(
            "Module m.\n\nRule r given x as Int, produce Int:\n  If x less than 0, Return 0 minus x.\n  Return x.",
            "en-US");
        assertThat(err).contains("'Return'").contains("then");
    }

    @Test
    void zh_nonKeywordToken_isNotRewritten() {
        // 标识符/字面量不是关键词，不应被回译（避免把用户的名字改掉）
        String err = parseErr("模块 m。\n\n规则 r 给定 x 作为 整数 产出 整数：\n  返回 x x。", "zh-CN");
        assertThat(err).startsWith("CNL 语法错误").doesNotContain("'Return'");
    }
}
