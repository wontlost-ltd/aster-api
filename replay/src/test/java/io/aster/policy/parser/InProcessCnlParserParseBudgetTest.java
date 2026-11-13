package io.aster.policy.parser;

import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语法分析时间预算（aster-cloud/aster-lang-core#254 兜底）。
 *
 * <p>两类病态输入：
 * <ul>
 *   <li>core 报告的 {@code result of} 成功 / 失败分量交替嵌套：只在 LL 预测下指数级（实测 24 层 0.7s、
 *       28 层 2.4s、32 层约 9–11s），生产用 SLL 时为毫秒级；用测试接缝注入 LL 复现它，验证预算能在
 *       一次 {@code adaptivePredict} 内部中止。</li>
 *   <li>{@code sll-pathological-types.aster}：随机生成的混合类型嵌套（4.6KB、含语法错误），走生产
 *       SLL 路径不限时实测约 46s——证明 SLL 本身拦不住，生产入口必须带预算。</li>
 * </ul>
 */
class InProcessCnlParserParseBudgetTest {

    /** 中止相对预算的允许超出量：检查间隔为每 1024 次 closure 调用，实测超出 0–3ms，留足 CI 抖动余量。 */
    private static final long MARGIN_MS = 750;

    @AfterEach
    void restoreDefault() {
        ParseBudget.configure(null);
    }

    /** {@code result of} 成功 / 失败分量交替嵌套 {@code depth} 层，放在参数类型位置（后接逗号，预测最贵）。 */
    static String alternatingResult(int depth) {
        String type = "Int";
        for (int i = 0; i < depth; i++) {
            type = i % 2 == 0 ? "result of " + type + " and Text" : "result of Int and " + type;
        }
        return "Module m.\n\nRule f given x as " + type + ", produce Int:\n  Return 1.\n";
    }

    static String sllPathological() throws IOException {
        try (InputStream in = InProcessCnlParserParseBudgetTest.class
                .getResourceAsStream("/parse-budget/sll-pathological-types.aster")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String NORMAL = """
        Module loan.

        Define Applicant has creditScore as Int, income as Int.

        Rule approve given applicant as Applicant, produce Bool:
          If applicant.creditScore greater than 700:
            Return true.
          Return applicant.income greater than 5000.
        """;

    private static InProcessCnlParser.ParseResult parseWith(String source, Duration budget, PredictionMode mode) {
        return InProcessCnlParser.parseUnsafeWithAliases(source, "en-US", null, null,
            (ast, scopes) -> NameResolution.resolveAndLower(ast, new ResolutionContext(scopes, ImportedDeclarations.NONE)),
            ParseBudget.start(budget), mode);
    }

    private static void assertBudgetE054(InProcessCnlParser.CnlParseException e, long budgetMs) {
        List<CnlErrorListener.Diagnostic> e054 = e.getDiagnostics().stream()
            .filter(d -> "E054".equals(d.code())).toList();
        assertEquals(1, e054.size(), "恰一条 E054，实际: " + e.getDiagnostics());
        CnlErrorListener.Diagnostic d = e054.get(0);
        assertEquals(InProcessCnlParser.BudgetExceededFailure.REASON, d.params().get("reason"));
        assertEquals(String.valueOf(budgetMs), d.params().get("budgetMs"));
        assertEquals(String.valueOf(InProcessCnlParser.NESTING_DEPTH_LIMIT), d.params().get("limit"),
            "limit 照旧填嵌套上限（按码模板引用它），码与参数对客户端不变");
        assertEquals("Parsing took too long (budget " + budgetMs + " ms); simplify the policy", d.message(),
            "文案按 reason 取超时句型，不再是误导的「嵌套过深」（aster-lang-core#259）");
        assertTrue(e.getMessage().contains(d.message()), "异常摘要（getSummary）沿用同一条超时文案，实际: " + e.getMessage());
        assertEquals(3, d.line(), "位置取中止时的当前 token（类型所在的第 3 行）");
    }

    @Test
    void alternatingResultUnderLlIsAbortedAtBudgetWithCodedE054() {
        for (int depth : new int[]{28, 32}) {
            long before = ParseBudget.exceededCount();
            long t0 = System.nanoTime();
            InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
                () -> parseWith(alternatingResult(depth), Duration.ofMillis(500), PredictionMode.LL));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
            assertBudgetE054(e, 500);
            assertTrue(elapsed >= 450 && elapsed < 500 + MARGIN_MS,
                depth + " 层：应在预算附近中止（不提前、不明显超出），实际 " + elapsed + "ms");
            assertEquals(before + 1, ParseBudget.exceededCount(), "每次中止计数恰加一");
        }
    }

    @Test
    void sllPathologicalInputOnProductionEntryIsAbortedAtConfiguredBudget() throws IOException {
        String source = sllPathological();
        ParseBudget.configure(Duration.ofMillis(500));

        long t0 = System.nanoTime();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(source, "en-US"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e,
            "生产入口（SLL）在该输入上不限时约 46s，必须被预算中止");
        assertBudgetE054(e, 500);
        assertTrue(elapsed < 500 + MARGIN_MS, "应在预算附近中止，实际 " + elapsed + "ms");
    }

    @Test
    void alternatingResultOnProductionEntryIsFastAndUnaffected() {
        // 生产 SLL 下 core#254 报告的形态是线性的：默认预算下照常解析成功，远低于预算
        for (int depth : new int[]{28, 32}) {
            long t0 = System.nanoTime();
            assertDoesNotThrow(() -> InProcessCnlParser.parse(alternatingResult(depth), "en-US"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(elapsed < 1000, depth + " 层在 SLL 下应为毫秒级，实际 " + elapsed + "ms");
        }
    }

    @Test
    void normalPolicyIsUnaffectedAndSharedDfaIsNotPoisonedByAnAbort() throws IOException {
        // 先让一次解析在预测中途被中止（LL 与 SLL 各一次）……
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class,
            () -> parseWith(alternatingResult(32), Duration.ofMillis(200), PredictionMode.LL));
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class,
            () -> parseWith(sllPathological(), Duration.ofMillis(200), PredictionMode.SLL));

        // ……之后共享 DFA 缓存上的正常解析与中止前完全一致
        InProcessCnlParser.ParseResult normal = InProcessCnlParser.parse(NORMAL, "en-US");
        assertEquals("loan", normal.moduleName());
        assertEquals(List.of("approve"), normal.functionNames());
        assertDoesNotThrow(() -> InProcessCnlParser.parse(alternatingResult(32), "en-US"),
            "被中止过的形态在 SLL 下仍能照常解析");
    }

    @Test
    void abortedParseReleasesItsWorkerThread() throws Exception {
        // 单线程池：被中止的解析必须让出线程，后续正常解析才能在同一线程上完成
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> slow = single.submit(() -> {
                try {
                    parseWith(alternatingResult(32), Duration.ofMillis(300), PredictionMode.LL);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<InProcessCnlParser.ParseResult> normal = single.submit(() -> InProcessCnlParser.parse(NORMAL, "en-US"));

            assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, slow.get(5, TimeUnit.SECONDS));
            assertEquals("loan", normal.get(5, TimeUnit.SECONDS).moduleName());
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void nonPositiveBudgetMeansUnlimited() {
        ParseBudget.configure(Duration.ZERO);
        assertEquals(Duration.ZERO, ParseBudget.configured());
        ParseBudget unlimited = ParseBudget.startDefault();
        assertDoesNotThrow(unlimited::check);
        assertEquals(0, unlimited.budgetMillis());

        ParseBudget.configure(null);
        assertEquals(ParseBudget.DEFAULT_BUDGET, ParseBudget.configured(), "null 恢复默认 2s");
    }
}
