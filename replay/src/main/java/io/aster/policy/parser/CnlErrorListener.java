package io.aster.policy.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

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

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        Object offendingSymbol,
        int line,
        int charPositionInLine,
        String msg,
        RecognitionException e
    ) {
        String friendly = humanize(msg);
        rawErrors.add(String.format("行 %d:%d - %s", line, charPositionInLine, msg));
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
        String first = friendlyErrors.get(0);
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
    /** `is` 与符号比较词相邻——ANTLR 拼接后形如 `...is<18`。 */
    private static final Pattern IS_BEFORE_SYMBOL =
        Pattern.compile("is\\s*[<>!=]");
    private static final Pattern FAILED_PREDICATE =
        Pattern.compile("rule (\\w+) failed predicate");

    /**
     * 把一条 ANTLR 消息翻译成面向 CNL 作者的提示。无法识别的模式原样返回。
     */
    static String humanize(String msg) {
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
            // ★`is` 紧跟符号是本仓最高频的手写/AI 生成错误（见 system_base_*.txt）。
            //   ANTLR 把已匹配 token **无分隔**拼接，故这里按 "is<" / "is>" 检测，
            //   而不是按源码文本——源码里它们中间通常有空格。
            if (IS_BEFORE_SYMBOL.matcher(tok).find()) {
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
