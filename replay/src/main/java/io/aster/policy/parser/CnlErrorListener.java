package io.aster.policy.parser;

import aster.core.lexicon.SemanticTokenKind;
import aster.core.parser.ParseDiagnostic;
import aster.core.parser.ParseDiagnosticException;
import aster.core.parser.SyntaxErrorCollector;
import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ANTLR 错误监听器
 *
 * 收集 CNL 解析过程中的语法错误，产出带共享码表 E050–E069 错误码与命名参数的结构化诊断，
 * 并按作者所用语言渲染面向 CNL 作者的文案（ADR 0013 错误信息友好化、ADR 0051 §16 R56）。
 *
 * <p>分工：ANTLR 事件 → 码 + 参数由 core 的 {@link SyntaxErrorCollector} 判定（与 aster-lang-ts 同一份
 * 映射清单，本类不再自己用正则解析 ANTLR 原文）；本类负责 ①按码取 overlay 模板（{@link SyntaxMessages}），
 * 参数里的 {@code kw.*} 按作者的写法回译（{@link KeywordLocalizer}）、{@code token.*} 取语法目录的名词；
 * ②对外只暴露<b>第一个</b>可确诊错误（根因），原始级联仅保留供调试。
 */
public class CnlErrorListener extends BaseErrorListener {

    /**
     * 结构化语法诊断：携带 1-based 行列、错误码、命名参数与友好消息，供编译端点透传给前端
     * （Monaco 精确标错、按码取文案）。
     *
     * @param line    1-based 行号（ANTLR line 本就 1-based）
     * @param column  1-based 列号（ANTLR charPositionInLine 是 0-based，+1）
     * @param message 友好化后的消息（策略 lexicon 的语言，不含内部 token 名）
     * @param code    错误码（语法错误为 E050–E069；名称解析为 E8xx）；来源无码时为 null
     * @param params  命名参数（core 约定：关键词 {@code kw.*}、抽象 token {@code token.*}、字面片段带单引号）
     */
    public record Diagnostic(int line, int column, String message, String code, Map<String, String> params) {

        public Diagnostic {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        /** 无码诊断（兼容旧调用方）。 */
        public Diagnostic(int line, int column, String message) {
            this(line, column, message, null, Map.of());
        }
    }

    /** 友好化后的错误（带「第 N 行第 M 列」外框）。 */
    private final List<String> friendlyErrors = new ArrayList<>();
    /** 原始 ANTLR 错误（供日志/调试，不展示给用户）。 */
    private final List<String> rawErrors = new ArrayList<>();
    /** 结构化诊断（含 1-based 行列 + 码 + 参数）。 */
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    /** core 的分类器：每个 ANTLR 事件先交给它判定码与参数。 */
    private final SyntaxErrorCollector classifier = new SyntaxErrorCollector();
    /**
     * 首条**可确诊**错误的下标（-1 = 无）。
     *
     * <p>ANTLR 的第一条错误往往不是根因：`x is < 18` 会先在 `18` 上报
     * 「no viable alternative」，真正能确诊的 `<` 排在第二条。只展示第一条
     * 就把可操作的提示丢了，用户只能看到「无法解析 'Ifxis<18' 附近的语法」。
     */
    private int diagnosableIndex = -1;
    /** 第一条错误所在行（-1 = 尚无错误），用于限定「缺句末标点」的提升范围。 */
    private int firstErrorLine = -1;

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
        classifier.syntaxError(recognizer, offendingSymbol, line, charPositionInLine, msg, e);
        List<ParseDiagnostic> classified = classifier.diagnostics();
        ParseDiagnostic d = classified.get(classified.size() - 1);
        // no viable 的出错片段按源码间隔重排（ANTLR 把 `Return n` 拼成 `Returnn`），比单个 token 更易定位
        String viableInput = e instanceof NoViableAltException nva ? renderInput(recognizer, nva, localizer) : null;
        String friendly = render(d.code(), d.params(), localizer, viableInput);
        boolean offendingIsKeyword = offendingSymbol instanceof Token tok
            && KeywordLocalizer.KeywordTokens.get().types.contains(tok.getType());
        if (isInlineIfReturnAfterComma(recognizer, offendingSymbol, offendingIsKeyword)) {
            friendly = messages.text("withHint", "hint", inlineIfHint(localizer), "message", friendly);
        }
        rawErrors.add(String.format("line %d:%d - %s", line, charPositionInLine, msg));
        boolean isBeforeSymbol = "hint.isBeforeSymbol".equals(d.params().get("hint"));
        if (diagnosableIndex < 0 && (isBeforeSymbol || isMissingStatementEndNear(d.code(), line))) {
            diagnosableIndex = friendlyErrors.size();
        }
        add(d.line(), d.column(), friendly, d.code(), d.params());
    }

    /**
     * AstBuilder / 字面量解析抛出的带码解析错误（语法已通过、构建 AST 时发现）：与 ANTLR 事件同样记为
     * 用户源码错误。位置未知（0）时落在 1:1。
     */
    void report(ParseDiagnosticException e) {
        rawErrors.add(String.format("ast %d:%d - %s", e.line(), e.column(), e.getMessage()));
        add(Math.max(1, e.line()), Math.max(1, e.column()), render(e.code(), e.params(), localizer, null),
            e.code(), e.params());
    }

    private void add(int line, int column, String friendly, ErrorCode code, Map<String, String> params) {
        if (firstErrorLine < 0) {
            firstErrorLine = line;
        }
        friendlyErrors.add(messages.text("located",
            "line", String.valueOf(line), "column", String.valueOf(column), "message", friendly));
        diagnostics.add(new Diagnostic(line, column, friendly, code == null ? null : code.code(), params));
    }

    /** 结构化诊断（含 1-based 行列 + 码 + 参数 + 友好消息）。 */
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
            return messages.moreErrors(friendlyErrors.size() - 1, first);
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
    // 码 + 参数 → 友好文案（策略 lexicon 的语言）
    // ==========================================================

    /** 期望集合最多列出的项数，其余以「, …」省略。 */
    private static final int MAX_EXPECTED = 3;

    /** 语法目录里 E054「时间预算超时」变体的句型 key（aster-lang-core#259）。 */
    static final String TIME_BUDGET_KEY = "parseTimeBudget";

    /**
     * 是否为语法分析超出时间预算的 E054（{@link InProcessCnlParser} 带 {@code reason=time-budget}）。
     * 它与嵌套过深同码、参数不变，只是文案按 {@code reason} 换成超时句型，不再显示误导的「嵌套过深」。
     */
    private static boolean isTimeBudget(ErrorCode code, Map<String, String> params) {
        return code == ErrorCode.PARSE_NESTING_TOO_DEEP
            && InProcessCnlParser.BudgetExceededFailure.REASON.equals(params.get("reason"))
            && params.get("budgetMs") != null;
    }

    /** 按码渲染（无 no viable 片段）。 */
    static String render(ErrorCode code, Map<String, String> params, KeywordLocalizer localizer) {
        return render(code, params, localizer, null);
    }

    /**
     * 按码取 {@code localizer} 语言的模板并填参数。
     *
     * <p>带 {@code hint} 参数的 E063（`is` 后直接跟符号、未识别的比较词）直接给出提示句：提示本身就指明了
     * 原因与改法，比「无法解析 … 附近的语法」更可操作（与改码前对外文案一致）。同理，{@code reason=time-budget}
     * 的 E054 取语法目录的 {@link #TIME_BUDGET_KEY} 句型；目录未收录该 key（locales 尚未升级）时照旧按码渲染。
     *
     * @param viableInput no viable alternative 的出错片段（按源码间隔重排、关键词已回译）；null 时用 {@code token} 参数
     */
    static String render(ErrorCode code, Map<String, String> params, KeywordLocalizer localizer, String viableInput) {
        SyntaxMessages text = localizer.messages();
        String hint = params.get("hint");
        if (hint != null && hint.startsWith("hint.") && text.has(hint.substring("hint.".length()))) {
            return text.text(hint.substring("hint.".length()));
        }
        if (isTimeBudget(code, params) && text.has(TIME_BUDGET_KEY)) {
            return text.text(TIME_BUDGET_KEY, "budgetMs", params.get("budgetMs"));
        }
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> p : params.entrySet()) {
            String value;
            if ("expected".equals(p.getKey()) && code == ErrorCode.PARSE_UNEXPECTED_EXPECTING) {
                value = renderExpected(p.getValue(), localizer);
            } else if ("token".equals(p.getKey()) && code == ErrorCode.PARSE_NO_VIABLE
                    && viableInput != null && !viableInput.isBlank()) {
                value = "'" + viableInput + "'";
            } else {
                value = renderParam(p.getValue(), localizer);
            }
            args.add(p.getKey());
            args.add(value);
        }
        return text.diagnostic(code, args.toArray(String[]::new));
    }

    /**
     * 单个参数值的显示：{@code kw.*} 回译成作者语言的关键词并加引号，{@code token.*} 取名词，
     * {@code hint.*} 取提示句，带引号的字面片段剥掉缩进标记、标点按作者的写法显示，其余（数字、类型名）原样。
     */
    static String renderParam(String value, KeywordLocalizer localizer) {
        SyntaxMessages text = localizer.messages();
        if (value == null || value.isEmpty()) {
            return text.token("empty");
        }
        if (value.startsWith("kw.")) {
            return "'" + localizer.keywordParam(value.substring("kw.".length())) + "'";
        }
        if (value.startsWith("token.")) {
            return text.tokenName(value.substring("token.".length()));
        }
        if (value.startsWith("hint.")) {
            return text.text(value.substring("hint.".length()));
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            String inner = stripLayoutMarkers(value.substring(1, value.length() - 1));
            if (inner.isEmpty()) {
                return text.token("empty");
            }
            return "'" + (localizer.isPunctuation(inner) ? localizer.localize(inner) : inner) + "'";
        }
        return value;
    }

    /** 期望集合（core 以 {@code " | "} 连接）：逐项渲染、去重，只列前几项，用该语言的「或」连接。 */
    private static String renderExpected(String set, KeywordLocalizer localizer) {
        SyntaxMessages text = localizer.messages();
        List<String> items = new ArrayList<>();
        String[] parts = set.split(" \\| ");
        for (String part : parts) {
            String rendered = renderParam(part, localizer);
            if (!items.contains(rendered)) {
                items.add(rendered);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(items.size(), MAX_EXPECTED); i++) {
            if (i > 0) {
                sb.append(text.text("or"));
            }
            sb.append(items.get(i));
        }
        if (items.size() > MAX_EXPECTED) {
            sb.append(text.text("more"));
        }
        return sb.toString();
    }

    /**
     * 是否为可提升的「行尾缺句末标点」（E059）：ANTLR 常先在<b>下一行</b>报一条泛化的 no viable，真正的原因排在其后。
     * 只有缺句点位于第一条错误的同一行或上一行时才提升；更早或更远处已有别的错误时保持第一条，
     * 不让常见的缺句点盖过文件前面无关的真错误。
     */
    private boolean isMissingStatementEndNear(ErrorCode code, int line) {
        boolean near = firstErrorLine < 0 || line == firstErrorLine || line == firstErrorLine - 1;
        return near && code == ErrorCode.PARSE_EXPECTED_STATEMENT_END;
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
     * 字面换行，避免在友好错误里泄露 lexer 内部 token 名。
     */
    private static String stripLayoutMarkers(String tok) {
        return tok
            .replace("<INDENT>", "")
            .replace("<DEDENT>", "")
            .replace("\\n", " ")
            .replace("\n", " ")
            .trim();
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
