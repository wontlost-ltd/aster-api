package io.aster.policy.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANTLR 错误监听器
 *
 * 收集 CNL 解析过程中的语法错误，并把 ANTLR 的原始（面向编译器实现者的）消息
 * 翻译成面向 CNL 作者的友好提示。
 *
 * <p>背景：ANTLR 默认错误形如
 * {@code mismatched input '.' expecting {':', ',', NEWLINE}} /
 * {@code rule softComparator failed predicate: {...}?} /
 * {@code extraneous input '<INDENT>' expecting ...}，并且一个真实语法错误常引发
 * 几十条级联错误。直接抛给用户既冗长又难懂（见 ADR 0013 错误信息友好化）。
 * 本监听器：①把内部 token 名 / 谓词名翻译成人话；②对外只暴露<b>第一个</b>
 * 友好错误（根因），原始级联仅保留供调试；③文案用策略 lexicon 的语言
 * （{@link SyntaxMessages}），关键词与标点按作者的写法显示（{@link KeywordLocalizer}）。
 */
public class CnlErrorListener extends BaseErrorListener {

    /**
     * 结构化语法诊断：携带 1-based 行列 + 友好消息，供编译端点透传给前端
     * （Monaco 精确标错）。这是行列的唯一结构化真源——此前只被格式化进字符串。
     *
     * @param line        1-based 行号（ANTLR line 本就 1-based）
     * @param column      1-based 列号（ANTLR charPositionInLine 是 0-based，+1）
     * @param message     友好化后的消息（策略 lexicon 的语言，不含内部 token 名）
     */
    public record Diagnostic(int line, int column, String message) {}

    /** 友好化后的错误（去内部术语）。 */
    private final List<String> friendlyErrors = new ArrayList<>();
    /** 原始 ANTLR 错误（供日志/调试，不展示给用户）。 */
    private final List<String> rawErrors = new ArrayList<>();
    /** 结构化诊断（含 1-based 行列），供编译端点映射为前端 diagnostics。 */
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    /**
     * 首条**可确诊**错误的下标（-1 = 无）。
     *
     * <p>ANTLR 的第一条错误往往不是根因：`x is < 18` 会先在 `18` 上报
     * 「no viable alternative」，真正能确诊的 `<` 排在第二条。只展示第一条
     * 就把可操作的提示丢了，用户只能看到「无法解析 'Ifxis<18' 附近的语法」。
     */
    private int diagnosableIndex = -1;

    /** 规范关键词 → 作者 locale 拼写的回译器（英文为恒等）。 */
    private final KeywordLocalizer localizer;
    /** 作者所用语言的文案目录。 */
    private final SyntaxMessages messages;

    /** 不回译（英文 / 未知 locale）。 */
    public CnlErrorListener() {
        this(KeywordLocalizer.IDENTITY);
    }

    /** 报错中的关键词按 {@code localizer} 回译成作者所写语言的拼写。 */
    CnlErrorListener(KeywordLocalizer localizer) {
        this.localizer = localizer == null ? KeywordLocalizer.IDENTITY : localizer;
        this.messages = this.localizer.messages();
    }

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        Object offendingSymbol,
        int line,
        int charPositionInLine,
        String msg,
        RecognitionException e
    ) {
        // ★用 token 流做判定，而不是靠 ANTLR 消息里的**无分隔拼接串**：
        //   那个串把 `analysis <= 3` 拼成 `analysis<=3`，与真正的 `x is < 18`
        //   拼出的 `xis<18` 在正则眼里毫无区别——实测 `analysis <= 3` 会被
        //   报成「`is` 后面不能直接跟符号」，把用户引向一个根本不存在的问题。
        boolean isBeforeSymbol = precededByIsKeyword(recognizer, offendingSymbol);
        // ★只有 ANTLR 词表里的关键词 token 与规范标点才回译；标识符/字面量即使碰巧与英文关键词同形也不改。
        boolean offendingIsKeyword = isKeywordToken(recognizer, offendingSymbol)
            || (offendingSymbol instanceof Token tok && localizer.isPunctuation(tok.getText()));
        String viableInput = e instanceof NoViableAltException nva ? renderInput(recognizer, nva, localizer) : null;
        String friendly = humanize(msg, isBeforeSymbol, localizer, offendingIsKeyword, viableInput);
        if (isInlineIfReturnAfterComma(recognizer, offendingSymbol, offendingIsKeyword)) {
            friendly = messages.text("withHint", "hint", inlineIfHint(localizer), "message", friendly);
        }
        rawErrors.add(String.format("line %d:%d - %s", line, charPositionInLine, msg));
        if ((isBeforeSymbol || isMissingStatementEnd(msg)) && diagnosableIndex < 0) {
            diagnosableIndex = friendlyErrors.size();
        }
        friendlyErrors.add(messages.text("located",
            "line", String.valueOf(line), "column", String.valueOf(charPositionInLine + 1), "message", friendly));
        // 行列归一到 1-based（Monaco/前端契约）。charPositionInLine 是 0-based → +1。
        diagnostics.add(new Diagnostic(line, charPositionInLine + 1, friendly));
    }

    /** 结构化诊断（含 1-based 行列 + 友好消息）。 */
    public List<Diagnostic> getDiagnostics() {
        return new ArrayList<>(diagnostics);
    }

    /**
     * 是否有解析错误
     */
    public boolean hasErrors() {
        return !friendlyErrors.isEmpty();
    }

    /**
     * 面向用户的错误信息：只返回<b>第一个</b>友好错误（根因），避免级联噪声。
     */
    public String getErrors() {
        if (friendlyErrors.isEmpty()) {
            return "";
        }
        // ★优先展示**可确诊**的那条，而不是机械取第一条：
        //   ANTLR 常常先在下一个 token 上报一条泛化错误，真正指明原因的排在后面。
        int idx = diagnosableIndex >= 0 ? diagnosableIndex : 0;
        String first = friendlyErrors.get(idx);
        if (friendlyErrors.size() > 1) {
            return messages.text("moreErrors", "count", String.valueOf(friendlyErrors.size() - 1), "message", first);
        }
        return first;
    }

    /** 带「CNL 语法错误 — 」前缀的整句（异常消息用），语言同 {@link #getErrors()}。 */
    public String getSummary() {
        return messages.text("summary", "detail", getErrors());
    }

    /**
     * 原始 ANTLR 错误全集，供日志/调试，不展示给最终用户。
     */
    public String getRawErrors() {
        return String.join("; ", rawErrors);
    }

    /**
     * 获取错误列表（友好化）
     */
    public List<String> getErrorList() {
        return new ArrayList<>(friendlyErrors);
    }

    // ==========================================================
    // ANTLR 原始消息 → 友好文案（策略 lexicon 的语言）
    // ==========================================================

    private static final Pattern MISMATCHED =
        Pattern.compile("mismatched input '(.+?)' expecting \\{?(.+?)\\}?$");
    private static final Pattern MISSING =
        Pattern.compile("missing '?(.+?)'? at '(.+?)'");
    private static final Pattern EXTRANEOUS =
        Pattern.compile("extraneous input '(.+?)' expecting");
    private static final Pattern NO_VIABLE =
        Pattern.compile("no viable alternative at input '(.+?)'");
    private static final Pattern FAILED_PREDICATE =
        Pattern.compile("rule (\\w+) failed predicate");

    /**
     * 出错 token 的**前一个** token 是否就是 `is` 关键字，且自身以符号开头。
     *
     * <p>这是「`is` 后面直接跟符号」的<b>权威</b>判定：走 token 流，
     * 不受 ANTLR 消息里无分隔拼接串的干扰。
     */
    private static boolean precededByIsKeyword(Recognizer<?, ?> recognizer, Object offendingSymbol) {
        if (!(offendingSymbol instanceof Token bad)) {
            return false;
        }
        String text = bad.getText();
        if (text == null || text.isEmpty() || "<>!=".indexOf(text.charAt(0)) < 0) {
            return false;   // 出错 token 不是符号，谈不上「is 后跟符号」
        }
        if (!(recognizer instanceof Parser parser)) {
            return false;
        }
        TokenStream stream = parser.getInputStream();
        if (stream == null) {
            return false;
        }
        for (int i = bad.getTokenIndex() - 1; i >= 0; i--) {
            Token prev = stream.get(i);
            if (prev.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;   // 跳过空白/注释通道
            }
            return "is".equalsIgnoreCase(prev.getText());
        }
        return false;
    }

    /** 兼容旧签名（无 token 上下文时按「不是 is+符号」处理，即不作断言；英文文案）。 */
    static String humanize(String msg) {
        return humanize(msg, false);
    }

    static String humanize(String msg, boolean isBeforeSymbol) {
        return humanize(msg, isBeforeSymbol, KeywordLocalizer.IDENTITY, false);
    }

    static String humanize(String msg, boolean isBeforeSymbol, KeywordLocalizer localizer,
            boolean offendingIsKeyword) {
        return humanize(msg, isBeforeSymbol, localizer, offendingIsKeyword, null);
    }

    /**
     * 把一条 ANTLR 消息翻译成面向 CNL 作者的提示（{@code localizer} 决定语言）。无法识别的模式原样返回。
     *
     * @param viableInput no viable alternative 的出错片段（按源码间隔重排、关键词已回译）；
     *                    null 时退回 ANTLR 消息里的无分隔拼接串
     */
    static String humanize(String msg, boolean isBeforeSymbol, KeywordLocalizer localizer,
            boolean offendingIsKeyword, String viableInput) {
        SyntaxMessages text = localizer.messages();
        if (msg == null) {
            return text.text("syntaxError");
        }

        Matcher m;

        // 谓词失败：最常见于软关键字比较词（under/over）/可选 is 连接词处。
        // 例：rule softComparator failed predicate → 多半是用了未识别的比较词，
        // 或 `is` 后面跟了非比较词。给出可操作建议。
        if ((m = FAILED_PREDICATE.matcher(msg)).find()) {
            return text.text("unknownOperator");
        }

        if ((m = MISMATCHED.matcher(msg)).find()) {
            String got = friendlyToken(offendingIsKeyword ? localizer.localize(m.group(1)) : m.group(1), text);
            String expecting = friendlyExpecting(m.group(2), localizer);
            return expecting.isEmpty()
                ? text.text("unexpected", "token", got)
                : text.text("unexpectedExpecting", "expected", expecting, "token", got);
        }

        if ((m = MISSING.matcher(msg)).find()) {
            // missing 'X' 的 X 来自 ANTLR 字面量名，必是关键词/标点；at 是实际 token，按类型判定
            String want = friendlyToken(localizer.localize(m.group(1)), text);
            if (isStatementEnd(m.group(1)) && isLineEnd(m.group(2))) {
                // 与编辑器同一说法：「语句末尾应为 '。'」/ Expected '.' at end of statement
                return text.text("expectedAtEndOfStatement", "token", want);
            }
            String at = friendlyToken(offendingIsKeyword ? localizer.localize(m.group(2)) : m.group(2), text);
            return text.text("expectedBefore", "token", want, "before", at);
        }

        if ((m = EXTRANEOUS.matcher(msg)).find()) {
            String extra = m.group(1);
            if (extra.contains("INDENT")) {
                return text.text("indentation");
            }
            return text.text("extraneous", "token",
                friendlyToken(offendingIsKeyword ? localizer.localize(extra) : extra, text));
        }

        if ((m = NO_VIABLE.matcher(msg)).find()) {
            // ★只有当**上一个真实 token 就是 `is` 关键字**时才给这条专项提示。
            //   曾经改用正则扫拼接串，结果 `analysis <= 3` / `this is< 5` 之类
            //   含 "is" 的标识符全部误报——一条自信但错误的提示比没有提示更糟，
            //   它让用户去改一个正确的地方（实测浪费了整轮排查）。
            if (isBeforeSymbol) {
                return text.text("isBeforeSymbol");
            }
            String tok = viableInput != null && !viableInput.isBlank() ? viableInput : m.group(1);
            return text.text("noViable", "token", friendlyToken(tok, text));
        }

        if (msg.contains("token recognition error")) {
            return text.text("unrecognizedCharacter");
        }

        // 兜底：原样（但去掉最内部的 expecting token 集，避免暴露 token 名）
        int idx = msg.indexOf(" expecting");
        return idx > 0 ? msg.substring(0, idx) : msg;
    }

    /**
     * 是否为「行尾缺句末标点」：ANTLR 常先在下一行报一条泛化的 no viable，真正的原因排在其后，
     * 与 `is` 后跟符号一样按可确诊错误优先展示。
     */
    private static boolean isMissingStatementEnd(String msg) {
        Matcher m = msg == null ? null : MISSING.matcher(msg);
        return m != null && m.find() && isStatementEnd(m.group(1)) && isLineEnd(m.group(2));
    }

    /** ANTLR 报缺的是句末标点（规范写法 {@code .}）。 */
    private static boolean isStatementEnd(String canonical) {
        return ".".equals(canonical);
    }

    /** 缺失位置是行尾：换行、取消缩进或文件结尾。 */
    private static boolean isLineEnd(String at) {
        return switch (at) {
            case "\\n", "\n", "NEWLINE", "<DEDENT>", "DEDENT", "<EOF>" -> true;
            default -> false;
        };
    }

    /** 把 token 文本/名翻译成友好描述。 */
    private static String friendlyToken(String tok, SyntaxMessages text) {
        if (tok == null || tok.isEmpty()) {
            return text.token("empty");
        }
        switch (tok) {
            case "<INDENT>":
            case "INDENT":
                return text.token("indent");
            case "<DEDENT>":
            case "DEDENT":
                return text.token("dedent");
            case "<EOF>":
                return text.token("eof");
            case "\\n":
            case "\n":
            case "NEWLINE":
                return text.token("newline");
            case "TYPE_IDENT":
                return text.token("typeName");
            case "IDENT":
                return text.token("identifier");
            case "INT_LITERAL":
                return text.token("integer");
            case "STRING_LITERAL":
                return text.token("string");
            default:
                // no viable alternative 等错误的 group(1) 可能是多 token 串
                // （如 `Ifx<5\n<DEDENT>`），其中内嵌的 <INDENT>/<DEDENT> 是 lexer
                // 内部缩进标记，绝不能泄露给用户。先剥掉这些标记再加引号显示。
                String scrubbed = stripLayoutMarkers(tok);
                return "'" + scrubbed + "'";
        }
    }

    /**
     * no viable alternative 的出错片段，按源码里的间隔重排：ANTLR 消息把 token 无分隔拼接
     * （{@code Return n} → {@code Returnn}），这里在原文有空白处补一个空格，关键词与标点回译成作者的写法，
     * 跳过换行 / 缩进标记。拿不到 token 流时返回 null（调用方退回 ANTLR 的拼接串）。
     */
    private static String renderInput(Recognizer<?, ?> recognizer, NoViableAltException e, KeywordLocalizer localizer) {
        Token start = e.getStartToken();
        Token stop = e.getOffendingToken();
        if (!(recognizer instanceof Parser parser) || parser.getInputStream() == null
                || start == null || stop == null || start.getTokenIndex() < 0) {
            return null;
        }
        TokenStream stream = parser.getInputStream();
        Set<Integer> keywordTypes = KeywordLocalizer.KeywordTokens.get().types;
        StringBuilder sb = new StringBuilder();
        Token prev = null;
        for (int i = start.getTokenIndex(); i <= stop.getTokenIndex(); i++) {
            Token t = stream.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL || t.getType() == Token.EOF || isLayout(recognizer, t)) {
                continue;
            }
            if (prev != null && t.getStartIndex() > prev.getStopIndex() + 1) {
                sb.append(' ');
            }
            boolean lexiconWord = keywordTypes.contains(t.getType()) || localizer.isPunctuation(t.getText());
            sb.append(lexiconWord ? localizer.localize(t.getText()) : t.getText());
            prev = t;
        }
        return sb.toString();
    }

    private static boolean isLayout(Recognizer<?, ?> recognizer, Token t) {
        String name = symbolicName(recognizer, t);
        return "INDENT".equals(name) || "DEDENT".equals(name) || "NEWLINE".equals(name);
    }

    /**
     * 剥掉 token 文本里内嵌的缩进标记（{@code <INDENT>}/{@code <DEDENT>}）与
     * 字面换行，避免在友好错误里泄露 lexer 内部 token 名。多 token 串场景
     * （no viable alternative 的 group(1)）会命中。
     */
    private static String stripLayoutMarkers(String tok) {
        return tok
            .replace("<INDENT>", "")
            .replace("<DEDENT>", "")
            .replace("\\n", " ")
            .replace("\n", " ")
            .trim();
    }

    /** 简化 expecting 集合（ANTLR 常列十几个 token），只取前几个友好项。 */
    private static String friendlyExpecting(String set, KeywordLocalizer localizer) {
        if (set == null || set.isBlank()) {
            return "";
        }
        SyntaxMessages text = localizer.messages();
        List<String> parts = expectingItems(set);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String p : parts) {
            // expecting 集合里的项来自 ANTLR 词表：带引号的是字面量名（关键词/标点），
            // 不带引号的是符号名（RETURN、ELSE…）。两者都不是用户文本，可安全回译。
            String raw = p.replaceAll("^'|'$", "");
            if (localizer.isLocalizing()) {
                raw = KeywordLocalizer.KeywordTokens.get().symbolicToCanonical.getOrDefault(raw, raw);
            }
            String t = friendlyToken(localizer.localize(raw), text);
            if (sb.indexOf(t) >= 0) {
                continue; // 去重
            }
            if (shown > 0) {
                sb.append(text.text("or"));
            }
            sb.append(t);
            if (++shown >= 3) {
                break;
            }
        }
        if (parts.size() > shown) {
            sb.append(text.text("more"));
        }
        return sb.toString();
    }

    /** ANTLR expecting 集合的项：带引号的字面量（可能就是 {@code ','}）或裸符号名。按逗号切会把 {@code ','} 拆碎。 */
    private static final Pattern EXPECTING_ITEM = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|[^,\\s{}]+");

    private static List<String> expectingItems(String set) {
        List<String> items = new ArrayList<>();
        Matcher m = EXPECTING_ITEM.matcher(set);
        while (m.find()) {
            items.add(m.group());
        }
        return items;
    }

    /**
     * offendingSymbol 是否为关键词 token：类型属于 {@link KeywordLocalizer.KeywordTokens}
     * （以词法器对规范关键词拼写的实际判定为准，覆盖带/不带字面量名的所有关键词规则）。
     * 用户的标识符与类型名（含与关键词同形的 Given、Profile）不在其中，永远不会被回译。
     */
    private static boolean isKeywordToken(Recognizer<?, ?> recognizer, Object offendingSymbol) {
        if (!(offendingSymbol instanceof Token tok)) {
            return false;
        }
        return KeywordLocalizer.KeywordTokens.get().types.contains(tok.getType());
    }

    /**
     * 是否是「If 条件, Return …」——zh/de/en 作者都常这样写单行条件，但语法要求
     * then（或换行缩进块）。三个条件缺一不可：
     * ① 出错 token 是 Return 关键词；② 它前一个 token 是逗号；③ 该行第一个 token 是 If。
     * ③ 防止误伤 {@code Otherwise, Return x.}：那是 else 分支，提示「If … then」是错的。
     */
    private static boolean isInlineIfReturnAfterComma(Recognizer<?, ?> recognizer, Object offendingSymbol,
            boolean offendingIsKeyword) {
        if (!offendingIsKeyword || !(offendingSymbol instanceof Token bad)
                || !"RETURN".equals(symbolicName(recognizer, bad))
                || !(recognizer instanceof Parser parser) || parser.getInputStream() == null) {
            return false;
        }
        TokenStream stream = parser.getInputStream();
        Token prev = previousSignificant(recognizer, stream, bad.getTokenIndex());
        if (prev == null || !",".equals(prev.getText())) {
            return false;
        }
        Token lineStart = bad;
        for (Token t = prev; t != null && t.getLine() == bad.getLine();
                t = previousSignificant(recognizer, stream, t.getTokenIndex())) {
            lineStart = t;
        }
        return "IF".equals(symbolicName(recognizer, lineStart));
    }

    /** 默认通道上、且不是缩进/换行标记（INDENT/DEDENT/NEWLINE）的前一个 token；没有时返回 null。 */
    private static Token previousSignificant(Recognizer<?, ?> recognizer, TokenStream stream, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Token t = stream.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            String name = symbolicName(recognizer, t);
            if ("INDENT".equals(name) || "DEDENT".equals(name) || "NEWLINE".equals(name)) {
                continue;
            }
            return t;
        }
        return null;
    }

    private static String symbolicName(Recognizer<?, ?> recognizer, Token tok) {
        Vocabulary vocab = recognizer == null ? null : recognizer.getVocabulary();
        return vocab == null ? null : vocab.getSymbolicName(tok.getType());
    }

    /** 单行条件的正确写法，关键词用作者 locale 的拼写。 */
    private static String inlineIfHint(KeywordLocalizer localizer) {
        String ifWord = localizer.keyword(SemanticTokenKind.IF, "If");
        String thenWord = localizer.keyword(SemanticTokenKind.THEN, "then");
        String returnWord = localizer.keyword(SemanticTokenKind.RETURN, "Return");
        return localizer.messages().text("inlineIfHint", "if", ifWord, "then", thenWord, "return", returnWord);
    }
}
