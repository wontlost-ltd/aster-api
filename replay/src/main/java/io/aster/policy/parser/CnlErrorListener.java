package io.aster.policy.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.ArrayList;
import java.util.List;
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
 * 友好错误（根因），原始级联仅保留供调试。
 */
public class CnlErrorListener extends BaseErrorListener {

    /**
     * 结构化语法诊断：携带 1-based 行列 + 友好消息，供编译端点透传给前端
     * （Monaco 精确标错）。这是行列的唯一结构化真源——此前只被格式化进字符串。
     *
     * @param line        1-based 行号（ANTLR line 本就 1-based）
     * @param column      1-based 列号（ANTLR charPositionInLine 是 0-based，+1）
     * @param message     友好化后的中文消息（不含内部 token 名）
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
        String friendly = humanize(msg, isBeforeSymbol);
        rawErrors.add(String.format("行 %d:%d - %s", line, charPositionInLine, msg));
        if (isBeforeSymbol && diagnosableIndex < 0) {
            diagnosableIndex = friendlyErrors.size();
        }
        friendlyErrors.add(String.format("行 %d 第 %d 列：%s", line, charPositionInLine + 1, friendly));
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
            return first + "（另有 " + (friendlyErrors.size() - 1) + " 处后续错误，建议先修正此处）";
        }
        return first;
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
    // ANTLR 原始消息 → 友好中文
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

    /**
     * 把一条 ANTLR 消息翻译成面向 CNL 作者的提示。无法识别的模式原样返回。
     */
    /** 兼容旧签名（无 token 上下文时按「不是 is+符号」处理，即不作断言）。 */
    static String humanize(String msg) {
        return humanize(msg, false);
    }

    static String humanize(String msg, boolean isBeforeSymbol) {
        if (msg == null) {
            return "语法错误";
        }

        Matcher m;

        // 谓词失败：最常见于软关键字比较词（under/over）/可选 is 连接词处。
        // 例：rule softComparator failed predicate → 多半是用了未识别的比较词，
        // 或 `is` 后面跟了非比较词。给出可操作建议。
        if ((m = FAILED_PREDICATE.matcher(msg)).find()) {
            return "无法识别此处的运算符或关键词。"
                + "比较可写成文字（`is less than` / `is at least` / `is equal to`，`is` 可省略）"
                + "或裸符号（`<` `>` `>=`）。"
                + "★两种写法不能混用：`is < 18` 是语法错误，请写 `is less than 18` 或 `< 18`";
        }

        if ((m = MISMATCHED.matcher(msg)).find()) {
            String got = friendlyToken(m.group(1));
            String expecting = friendlyExpecting(m.group(2));
            return "意外的 " + got + (expecting.isEmpty() ? "" : "，此处期望 " + expecting);
        }

        if ((m = MISSING.matcher(msg)).find()) {
            String want = friendlyToken(m.group(1));
            String at = friendlyToken(m.group(2));
            return "缺少 " + want + "（在 " + at + " 之前）";
        }

        if ((m = EXTRANEOUS.matcher(msg)).find()) {
            String extra = m.group(1);
            if (extra.contains("INDENT")) {
                return "缩进层级不正确（多了一级缩进或缩进不一致，请用 2 空格对齐）";
            }
            return "多余的 " + friendlyToken(extra);
        }

        if ((m = NO_VIABLE.matcher(msg)).find()) {
            String tok = m.group(1);
            // ★只有当**上一个真实 token 就是 `is` 关键字**时才给这条专项提示。
            //   曾经改用正则扫拼接串，结果 `analysis <= 3` / `this is< 5` 之类
            //   含 "is" 的标识符全部误报——一条自信但错误的提示比没有提示更糟，
            //   它让用户去改一个正确的地方（实测浪费了整轮排查）。
            if (isBeforeSymbol) {
                return "`is` 后面不能直接跟符号（如 `is < 18`）。"
                    + "请改写成文字形式 `is less than 18`，或去掉 `is` 只用符号 `< 18`";
            }
            return "无法解析 " + friendlyToken(tok) + " 附近的语法";
        }

        if (msg.contains("token recognition error")) {
            return "存在无法识别的字符或符号";
        }

        // 兜底：原样（但去掉最内部的 expecting token 集，避免暴露 token 名）
        int idx = msg.indexOf(" expecting");
        return idx > 0 ? msg.substring(0, idx) : msg;
    }

    /** 把 token 文本/名翻译成友好描述。 */
    private static String friendlyToken(String tok) {
        if (tok == null || tok.isEmpty()) {
            return "内容";
        }
        switch (tok) {
            case "<INDENT>":
            case "INDENT":
                return "缩进";
            case "<DEDENT>":
            case "DEDENT":
                return "取消缩进";
            case "<EOF>":
                return "文件结尾";
            case "\\n":
            case "\n":
            case "NEWLINE":
                return "换行";
            case "TYPE_IDENT":
                return "类型名";
            case "IDENT":
                return "标识符";
            case "INT_LITERAL":
                return "整数";
            case "STRING_LITERAL":
                return "字符串";
            default:
                // no viable alternative 等错误的 group(1) 可能是多 token 串
                // （如 `Ifx<5\n<DEDENT>`），其中内嵌的 <INDENT>/<DEDENT> 是 lexer
                // 内部缩进标记，绝不能泄露给用户。先剥掉这些标记再加引号显示。
                String scrubbed = stripLayoutMarkers(tok);
                return "'" + scrubbed + "'";
        }
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
    private static String friendlyExpecting(String set) {
        if (set == null || set.isBlank()) {
            return "";
        }
        String[] parts = set.split(",");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String p : parts) {
            String t = friendlyToken(p.trim().replaceAll("^'|'$", ""));
            if (sb.indexOf(t) >= 0) {
                continue; // 去重
            }
            if (shown > 0) {
                sb.append(" 或 ");
            }
            sb.append(t);
            if (++shown >= 3) {
                break;
            }
        }
        if (parts.length > shown) {
            sb.append(" 等");
        }
        return sb.toString();
    }
}
