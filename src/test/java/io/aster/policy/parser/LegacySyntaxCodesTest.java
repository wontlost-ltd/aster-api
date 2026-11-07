package io.aster.policy.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 过渡期兼容映射（一个车次）：旧句型 key / 旧英文原句 → 码，用于读取升级前落库、没有码的语法诊断。 */
class LegacySyntaxCodesTest {

    /** 改码前语法目录覆盖的四种语言（与 LegacySyntaxCodes.LANGUAGES 同一份）。 */
    private static final List<String> LANGUAGES = List.of("en", "zh", "de", "hi");

    /**
     * 改码前全部 18 个诊断句型 key 及其码（ADR 0051 §16 兼容表）。期望句子不在这里写死，而是用
     * classpath 上 aster-lang-locales（1.0.42）的 {@code syntax-diagnostics/<locale>.json} 旧句型模板现场渲染，
     * 目录变了测试会跟着变。
     */
    private static final Map<String, String> TABLE = Map.ofEntries(
        Map.entry("unrecognizedCharacter", "E050"),
        Map.entry("indentation", "E052"),
        Map.entry("expected", "E055"),
        Map.entry("expectedAfter", "E056"),
        Map.entry("expectedIn", "E057"),
        Map.entry("expectedAfterIn", "E058"),
        Map.entry("expectedAtEndOfStatement", "E059"),
        Map.entry("expectedBefore", "E060"),
        Map.entry("extraneous", "E061"),
        Map.entry("unexpected", "E061"),
        Map.entry("unexpectedExpecting", "E062"),
        Map.entry("isBeforeSymbol", "E063"),
        Map.entry("unknownOperator", "E063"),
        Map.entry("noViable", "E063"),
        Map.entry("syntaxError", "E063"),
        Map.entry("unknownStatement", "E064"),
        Map.entry("unexpectedAtTopLevel", "E064"),
        Map.entry("unexpectedExpression", "E064"));

    /** 目录里不是诊断的 key：外框句型、提示、名词与排版开关，补码时一律不认。 */
    private static final Set<String> FRAME_KEYS = Set.of("summary", "located", "moreErrors.one", "moreErrors.other",
        "withHint", "or", "more", "inlineIfHint", "style.spaceQuotesAfterHan");

    /**
     * 占位符样本：每组依次为 token / expected / before / after / context。前五组是各语言改码前真实会出现的
     * 引号片段与 token 名词；最后一组故意让参数本身是其它句型的字面词，验证不同码的模板不会因参数内容互相吞掉。
     */
    private static final List<String[]> SAMPLES = List.of(
        new String[] {"'.'", "'.' or ':'", "'Return'", "'given'", "rule header"},
        new String[] {"'。'", "'。' 或 '：'", "'返回'", "'给定'", "规则头"},
        new String[] {"Zeilenumbruch", "'.' oder ':'", "'Return'", "'produce'", "Regelkopf"},
        new String[] {"'।'", "'।' या ':'", "'Return'", "'given'", "नियम शीर्ष"},
        new String[] {"end of file", "identifier or type name", "'If'", "'Otherwise'", "data definition"},
        new String[] {"'expected'", "'at end of statement'", "'Unexpected'", "'erwartet'", "'अपेक्षित है'"});

    private static String render(SyntaxMessages catalog, String key, String[] s) {
        return catalog.text(key, "token", s[0], "expected", s[1], "before", s[2], "after", s[3], "context", s[4]);
    }

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

    /**
     * 反向断言：参数本身就是分隔词（en 的 in / after / before，zh 的 之前 / 之后 / 中，de 的 Vor / Nach / In / wird，
     * hi 的 से पहले / के बाद / में）时，不被更宽泛的句型抢先分类；含两个占位符都是分隔词的 E058 句型。
     */
    @Test
    void separatorWordsAsParameters_doNotLeakIntoBroaderTemplates() {
        // en
        assertThat(LegacySyntaxCodes.codeForMessage("Expected 'in'")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected 'after' before 'in'")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected 'in' after 'before'")).isEqualTo("E056");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected 'before' in 'after'")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected 'before' after 'after' in 'in'")).isEqualTo("E058");
        assertThat(LegacySyntaxCodes.codeForMessage("Unexpected 'expected'; expected 'in'")).isEqualTo("E062");
        // zh（模板靠字面锚「之前应为 / 之后应为 / 中应为 / 中，」，分隔字出现在引号里不构成锚）
        assertThat(LegacySyntaxCodes.codeForMessage("此处应为 '中'")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("'之后' 之前应为 '中'")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("'之前' 之后应为 '中'")).isEqualTo("E056");
        assertThat(LegacySyntaxCodes.codeForMessage("'之后' 中应为 '之前'")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("'中' 中，'之后' 之后应为 '之前'")).isEqualTo("E058");
        // de
        assertThat(LegacySyntaxCodes.codeForMessage("'erwartet' erwartet")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("Vor 'In' wird 'Nach' erwartet")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Nach 'Vor' wird 'In' erwartet")).isEqualTo("E056");
        assertThat(LegacySyntaxCodes.codeForMessage("In 'Nach' wird 'wird' erwartet")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("In 'wird' wird nach 'nach' 'In' erwartet")).isEqualTo("E058");
        // hi
        assertThat(LegacySyntaxCodes.codeForMessage("'में' अपेक्षित है")).isEqualTo("E055");
        assertThat(LegacySyntaxCodes.codeForMessage("'के बाद' से पहले 'में' अपेक्षित है")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("'से पहले' के बाद 'में' अपेक्षित है")).isEqualTo("E056");
        assertThat(LegacySyntaxCodes.codeForMessage("'के बाद' में 'से पहले' अपेक्षित है")).isEqualTo("E057");
        assertThat(LegacySyntaxCodes.codeForMessage("'में' में 'के बाद' के बाद 'से पहले' अपेक्षित है")).isEqualTo("E058");
    }

    // ---------------------------------------------------------------- 完整四语映射表（aster-api#458 ①）

    /** 兼容表的 key 集合与码一一对应；外框 / 名词 key 不是诊断。 */
    @Test
    void keyTable_coversAllLegacyDiagnosticKeys_andNothingElse() {
        TABLE.forEach((key, code) -> assertThat(LegacySyntaxCodes.codeForKey(key)).as(key).isEqualTo(code));
        for (String frame : FRAME_KEYS) {
            assertThat(LegacySyntaxCodes.codeForKey(frame)).as(frame).isNull();
        }
        assertThat(LegacySyntaxCodes.codeForKey("token.newline")).isNull();
        CODED_ONLY_KEYS.forEach(key -> assertThat(LegacySyntaxCodes.codeForKey(key)).as(key).isNull());
    }

    /**
     * 只有带码路径才用的句子模板（locales 1.0.43 #127 补齐 E051 / E053 / E054 / E065–E069）：这些码从来没有
     * 旧英文句型，不进兼容表，旧句型补码也绝不能映射到它们。
     */
    private static final Set<String> CODED_ONLY_KEYS = Set.of("unterminatedString", "literalOutOfRange",
        "nestingTooDeep", "misplaced", "emptyBlock", "invalidValue", "callArity", "unknownName");

    /** 四语目录（locales 1.0.43）仍保留全部旧句型 key，且除诊断、外框、带码模板与 token.* 之外没有别的 key。 */
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh", "de", "hi"})
    void catalog_stillCarriesEveryLegacyKey(String lang) {
        Set<String> keys = SyntaxMessages.forLanguage(lang).keys();
        assertThat(keys).as(lang).containsAll(TABLE.keySet()).containsAll(FRAME_KEYS);
        Set<String> unknown = new TreeSet<>(keys);
        unknown.removeAll(TABLE.keySet());
        unknown.removeAll(FRAME_KEYS);
        unknown.removeAll(CODED_ONLY_KEYS);
        unknown.removeIf(k -> k.startsWith("token."));
        assertThat(unknown).as("%s 目录里既不是诊断也不是外框的 key", lang).isEmpty();
    }

    /**
     * 完整映射表：四种语言 × 18 个旧句型 × 6 组参数样本，按目录模板现场渲染的原句必须映射回该句型的码。
     * 同时覆盖跨码模板重叠——同一语言里 E055 / E056 / E057 / E058 / E059 / E060 的模板互为前缀或后缀
     * （Expected … / … erwartet / … अपेक्षित है），E061 与 E062 的模板互为前缀（Unexpected …）。
     */
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh", "de", "hi"})
    void everyLegacySentence_mapsBackToItsOwnCode(String lang) {
        SyntaxMessages catalog = SyntaxMessages.forLanguage(lang);
        for (Map.Entry<String, String> row : TABLE.entrySet()) {
            for (String[] sample : SAMPLES) {
                String sentence = render(catalog, row.getKey(), sample);
                assertThat(LegacySyntaxCodes.codeForMessage(sentence))
                    .as("%s / %s: %s", lang, row.getKey(), sentence)
                    .isEqualTo(row.getValue());
            }
        }
    }

    /**
     * 跨语言重叠：某一语言的原句不得被另一语言的模板先认走。四语模板串成一张表按声明顺序匹配，
     * 英文在前；这里确认 zh / de / hi 的每句都得到正确的码（上面的用例已逐语言断言，这里的意义是
     * 把「换一种语言的参数塞进另一种语言的句型」也走一遍）。
     */
    @Test
    void sentencesWithForeignParameters_stillMapToOwnCode() {
        for (String lang : LANGUAGES) {
            SyntaxMessages catalog = SyntaxMessages.forLanguage(lang);
            for (String other : LANGUAGES) {
                if (other.equals(lang)) {
                    continue;
                }
                SyntaxMessages foreign = SyntaxMessages.forLanguage(other);
                // 用另一种语言的 token 名词与整句「语法错误」作参数
                String[] sample = {foreign.token("newline"), foreign.token("eof"), foreign.text("syntaxError"),
                    foreign.token("identifier"), foreign.token("dataDefinition")};
                for (Map.Entry<String, String> row : TABLE.entrySet()) {
                    String sentence = render(catalog, row.getKey(), sample);
                    assertThat(LegacySyntaxCodes.codeForMessage(sentence))
                        .as("%s 句型 %s 带 %s 参数: %s", lang, row.getKey(), other, sentence)
                        .isEqualTo(row.getValue());
                }
            }
        }
    }

    /**
     * 历史原句锚点：从 aster-lang-locales v1.0.41 的 {@code syntax-diagnostics/<locale>.json} 模板逐字填出的句子
     * 写死在这里，不经目录渲染——目录日后被改写时，上面按目录生成的用例会跟着漂移，这里不会。
     */
    @Test
    void historicalSentences_fromLocales_1_0_41_mapToCode() {
        // en-US 1.0.41
        assertThat(LegacySyntaxCodes.codeForMessage("Unexpected 'Return'; expected newline")).isEqualTo("E062");
        assertThat(LegacySyntaxCodes.codeForMessage("Expected '.' before 'Rule'")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Unknown statement")).isEqualTo("E064");
        // zh-CN 1.0.41
        assertThat(LegacySyntaxCodes.codeForMessage("'规则' 之前应为 '。'")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("无法解析 '返回 n' 附近的语法")).isEqualTo("E063");
        assertThat(LegacySyntaxCodes.codeForMessage("缩进层级不正确（多了一级缩进或缩进不一致，请用 2 空格对齐）")).isEqualTo("E052");
        // de-DE 1.0.41
        assertThat(LegacySyntaxCodes.codeForMessage("Unerwartet: 'Return'; erwartet wird Zeilenumbruch")).isEqualTo("E062");
        assertThat(LegacySyntaxCodes.codeForMessage("Vor 'Rule' wird '.' erwartet")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("Unbekannte Anweisung")).isEqualTo("E064");
        // hi-IN 1.0.41
        assertThat(LegacySyntaxCodes.codeForMessage("अप्रत्याशित 'Return'; यहाँ नई पंक्ति अपेक्षित है")).isEqualTo("E062");
        assertThat(LegacySyntaxCodes.codeForMessage("'Rule' से पहले '.' अपेक्षित है")).isEqualTo("E060");
        assertThat(LegacySyntaxCodes.codeForMessage("अतिरिक्त 'of'")).isEqualTo("E061");
    }

    /** 外框句型渲染出的整句不是诊断原句，不得补码（summary / located 带前缀，改码前只有裸句落库）。 */
    @Test
    void frameSentences_areNotDiagnostics() {
        for (String lang : LANGUAGES) {
            SyntaxMessages catalog = SyntaxMessages.forLanguage(lang);
            assertThat(LegacySyntaxCodes.codeForMessage(catalog.text("summary", "detail", "x"))).as(lang).isNull();
            assertThat(LegacySyntaxCodes.codeForMessage(catalog.text("moreErrors.other", "count", "2",
                "message", "x"))).as(lang).isNull();
        }
    }
}
