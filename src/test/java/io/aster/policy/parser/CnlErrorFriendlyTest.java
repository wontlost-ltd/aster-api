package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CNL 解析错误友好化（ADR 0013 错误信息友好化）。
 *
 * 锁住：用户拿到的错误是人话、只报根因（首个错误），不再暴露 ANTLR 内部术语
 * （softComparator failed predicate / mismatched input / token 名 / &lt;INDENT&gt;）。
 */
class CnlErrorFriendlyTest {

    private static String parseErr(String src) {
        try {
            InProcessCnlParser.parse(src, "en-US");
            return "<no error>";
        } catch (InProcessCnlParser.CnlParseException e) {
            return e.getMessage();
        }
    }

    @Test
    void unknownComparator_givesActionableHint_notPredicateName() {
        // 此前：rule softComparator failed predicate: {...}? —— 对用户毫无意义
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is wobbly 5\n    Return 1.");
        assertThat(err)
            .doesNotContain("failed predicate")
            .doesNotContain("softComparator")
            .contains("无法识别")
            .contains("is less than"); // 给出可用的比较词建议
    }

    @Test
    void indentError_translated_notExtraneousDedent() {
        String err = parseErr("Module m.\nRule r given x, produce:\n      If x < 5\n  Return 1.");
        assertThat(err)
            .doesNotContain("<INDENT>")
            .doesNotContain("<DEDENT>")
            .doesNotContain("extraneous input");
    }

    @Test
    void noViableAlternative_stripsEmbeddedLayoutMarkers() {
        // ADR 0019 G2a 内联 if grammar 让畸形 if（缩进但缺 then/冒号）产生
        // `no viable alternative at input 'Ifx<5\n<DEDENT>'`——group(1) 是多 token
        // 串，内嵌 <DEDENT> 必须被剥掉，不能泄露给用户。
        String err = parseErr("Module m.\nRule r given x, produce:\n      If x < 5\n  Return 1.");
        assertThat(err)
            .doesNotContain("<INDENT>")
            .doesNotContain("<DEDENT>")
            .doesNotContain("DEDENT")
            .doesNotContain("INDENT");
    }

    @Test
    void onlyFirstRootCause_notCascadeOfDozens() {
        // 一个真实错误常引发几十条级联；用户只该看到根因。
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is wobbly 5\n    Return 1.");
        // 友好消息以"行 N 第 M 列"开头，且不应包含十几条用 ; 串起来的原始错误
        assertThat(err).startsWith("CNL 语法错误 — 行 ");
        long rawSeparators = err.chars().filter(c -> c == ';').count();
        assertThat(rawSeparators).isZero();
    }

    @Test
    void humanize_directUnit() {
        assertThat(CnlErrorListener.humanize("rule softComparator failed predicate: {x}?"))
            .contains("无法识别");
        assertThat(CnlErrorListener.humanize("mismatched input '.' expecting {':', ',', NEWLINE}"))
            .contains("意外的")
            .doesNotContain("NEWLINE");
        assertThat(CnlErrorListener.humanize("extraneous input '<INDENT>' expecting {<EOF>}"))
            .contains("缩进");
        assertThat(CnlErrorListener.humanize("no viable alternative at input 'Definehas'"))
            .contains("无法解析");
    }

    /**
     * ★{@code is} 后面直接跟符号必须给出**可操作**提示。
     *
     * <p>CNL 里 {@code is} 是可选连接词，只能接**文字**比较词
     * （{@code is less than} / {@code is at least} / {@code is equal to}）；
     * 接符号（{@code is < 18}）是语法错误。
     *
     * <p>这条是真实生产事故：AI 生成的策略写了 {@code is < 18} 并存进库，
     * 用户在执行页才看到「行 15 第 25 列：无法解析 'Ifapplicant.ageis&lt;18'」——
     * 而当时的提示还反过来建议「用 `is less than` 等形式**或** `<` `>` 符号」，
     * 没说两者不能拼在一起，等于在失败点上给了导致失败的建议。
     */
    @Test
    void isBeforeSymbol_tellsUserTheTwoStylesCannotBeMixed() {
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is < 18\n    Return 1.");

        assertThat(err)
            .as("★必须点名 `is` 不能接符号，而不是只说「无法解析 ... 附近」")
            .contains("is");
        assertThat(err)
            .as("★必须给出两条可用的改法之一")
            .containsAnyOf("less than", "< 18");
        assertThat(err)
            .as("★不得再出现 ANTLR 的无分隔 token 串")
            .doesNotContain("xis<18");
    }

    /** 文字比较词（含省略 is）必须照常通过——修提示不能改变可接受的语法。 */
    @Test
    void wordComparators_remainValid() {
        assertThat(parseErr("Module m.\nRule r given x, produce:\n  If x is less than 18\n    Return 1."))
            .isEqualTo("<no error>");
        assertThat(parseErr("Module m.\nRule r given x, produce:\n  If x less than 18\n    Return 1."))
            .isEqualTo("<no error>");
        assertThat(parseErr("Module m.\nRule r given x, produce:\n  If x < 18\n    Return 1."))
            .isEqualTo("<no error>");
    }

    /**
     * ★<b>误报回归</b>：标识符里含 "is" 不得被报成「`is` 后面跟了符号」。
     *
     * <p>此前的判定是拿正则扫 ANTLR 消息里的**无分隔拼接串**
     * （{@code no viable alternative at input 'Ifanalysis<=3'}）。
     * ANTLR 把 token 无空格拼起来，于是 {@code analysis <= 3} 与真正的
     * {@code x is < 18} 在正则眼里完全一样，双双被报成 `is` 的问题。
     *
     * <p>这条误报的代价是实打实的：用户源码里根本没有 `is`，
     * 却被这条自信的提示引去反复修改一个正确的地方。
     * <b>一条自信但错误的诊断，比没有诊断更糟。</b>
     *
     * <p>现改为走 token 流判定（出错 token 的前一个 token 必须**就是** `is`）。
     */
    @Test
    void identifierContainingIs_mustNotBeReportedAsIsBeforeSymbol() {
        String err = parseErr("Module m.\nRule r given analysis, produce:\n  If analysis <= 3 foo\n    Return 1.");

        assertThat(err)
            .as("★源码里没有 `is` 关键词，不得声称是 `is` 的问题")
            .doesNotContain("`is` 后面不能直接跟符号");
    }

    /** 真正的 `is` + 符号仍必须给出可操作提示（不能为了消误报把功能也砍掉）。 */
    @Test
    void realIsBeforeSymbol_stillGivesActionableHint() {
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is < 18\n    Return 1.");

        assertThat(err)
            .as("★真正的 `is <` 必须仍被诊断出来")
            .containsAnyOf("两种写法不能混用", "`is` 后面不能直接跟符号");
        assertThat(err)
            .as("★必须给出可用的改法")
            .containsAnyOf("less than", "< 18");
    }

    /**
     * ★`is` 与文字比较词的组合是**合法**的，必须照常通过。
     *
     * <p>用户报「以前带 is 是能跑的」——属实，且现在依然能跑。
     * 之前那轮排查里，是**误报的提示**让人误以为 `is` 坏掉了。
     */
    @Test
    void isWithWordComparators_remainsValid() {
        for (String cmp : new String[] {
            "is less than 18", "is greater than 18", "is at least 18",
            "is at most 18", "is equal to 18",
        }) {
            assertThat(parseErr("Module m.\nRule r given x, produce:\n  If x " + cmp + "\n    Return 1."))
                .as("★`%s` 是合法 CNL", cmp)
                .isEqualTo("<no error>");
        }
    }
}
