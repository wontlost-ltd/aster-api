package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析边界的纵深防御（aster-api#472）：core 的嵌套深度守卫若在线程栈溢出之后才触发
 * （阈值高于真实栈容量，1.0.42 的 300 在 1MB 栈上即如此），{@code StackOverflowError}
 * 是 Error 级，会穿透一切 {@code catch (Exception)} 变成 500。解析器必须把它归为带码 E054
 * 的用户源码错误（{@link InProcessCnlParser.CnlParseException}），与守卫触发时同一出口。
 *
 * <p>为了不依赖运行平台的默认栈大小（macOS 2MB、Linux 1MB），在显式 256K 栈的线程上解析：
 * 实测该栈上括号约 28 层即溢出，60 层既低于任何版本的守卫阈值（64 / 300）又必然溢出，
 * 两种 core 版本下都稳定走到 StackOverflowError 路径。
 */
class InProcessCnlParserStackOverflowTest {

    private static final long SMALL_STACK = 256L * 1024;

    private static String nestedParens(int depth) {
        return "Module M.\n\nRule p given x as Int, produce Int:\n  Return "
            + "(".repeat(depth) + "x" + ")".repeat(depth) + ".";
    }

    /** 在指定栈大小的线程上解析；返回抛出的 Throwable（null 表示解析成功）。 */
    private static Throwable parseOn(long stackBytes, String source) throws InterruptedException {
        Throwable[] out = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                InProcessCnlParser.parse(source, "en-US");
            } catch (Throwable e) {
                out[0] = e;
            }
        }, "cnl-parse-small-stack", stackBytes);
        t.start();
        t.join();
        return out[0];
    }

    @Test
    void stackOverflowDuringParseIsCodedE054NotError() throws InterruptedException {
        Throwable thrown = parseOn(SMALL_STACK, nestedParens(60));

        assertNotNull(thrown, "256K 栈上 60 层括号必须失败（否则本测试没有覆盖溢出路径）");
        InProcessCnlParser.CnlParseException e = assertInstanceOf(InProcessCnlParser.CnlParseException.class, thrown,
            "StackOverflowError 必须在解析边界归为 CnlParseException，实际: " + thrown);
        List<CnlErrorListener.Diagnostic> diags = e.getDiagnostics();
        assertEquals(1, diags.size(), "恰一条带码诊断");
        assertEquals("E054", diags.get(0).code());
        assertEquals(String.valueOf(InProcessCnlParser.NESTING_DEPTH_LIMIT), diags.get(0).params().get("limit"));
        assertEquals("Nesting is too deep (limit " + InProcessCnlParser.NESTING_DEPTH_LIMIT + ")", diags.get(0).message());
        // 语法分析期溢出取当前 token（第 4 行）；构建 AST 等后续阶段溢出位置不可知，按 1:1 报——两者都是合法出口
        assertTrue(diags.get(0).line() == 4 || diags.get(0).line() == 1,
            "行号应为 4（语法分析期）或 1（位置未知），实际: " + diags.get(0).line());
    }

    @Test
    void limitHintMatchesCoreGuard() {
        // 1.0.43 起 api 直接引用 core 的 public 常量；这里锁住它确实是 core 的值（而不是又被改回硬编码）
        assertEquals(aster.core.parser.AsterCustomLexer.MAX_NESTING_DEPTH, InProcessCnlParser.NESTING_DEPTH_LIMIT,
            "api 侧的上限提示必须与 core 守卫阈值一致");
    }

    @Test
    void stackOverflowOutsideParseStagesIsInternalFailureNotE054() {
        // 必修项（Codex 审查 #474）：只有词法 / 语法分析 / 构建 AST 三个按输入嵌套递归的阶段的溢出才是 E054；
        // 名称解析 / 降级等阶段溢出是实现缺陷，不得伪装成用户源码错误。在名称解析接缝注入 StackOverflowError。
        String source = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x.";
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseUnsafeWithAliases(source, "en-US", null, null,
                (ast, scopes) -> { throw new StackOverflowError("lowering 深递归"); }));

        assertInstanceOf(StackOverflowError.class, e.getCause(), "内部故障必须保留原始 Error 作为 cause");
        assertTrue(e.getDiagnostics().isEmpty(), "不得产出带码诊断，实际: " + e.getDiagnostics());
        assertFalse(e.getMessage().contains("E054") || e.getMessage().contains("嵌套"),
            "不得标为嵌套过深，实际: " + e.getMessage());
    }

    @Test
    void shallowNestingStillParsesOnSmallStack() throws InterruptedException {
        // 反向护栏：没有这条，把解析一律报 E054 也能让上面的用例变绿。
        assertEquals(null, parseOn(SMALL_STACK, nestedParens(8)));
    }
}
