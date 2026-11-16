package io.aster.policy.parser;

import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
 * <p>慢解析的来源：一条合法的长布尔链（{@link #slowSource()}）加上 {@link ParseBudget#slowChecksForTesting}
 * 的周期检查停顿。此前用的 {@code sll-pathological-types.aster}（混合类型嵌套，SLL 约 46s）依赖 core 1.0.47
 * 及更早版本的预测缺陷；core 1.0.48 修复了类型预测（aster-lang-core#270），该输入几毫秒内就报语法错误，
 * 再也到不了预算。测试验证的是预算机制本身（按时中止、带码 E054、计数、释放线程、不污染共享 DFA），
 * 而不是某个引擎版本的性能缺陷——aster-api 的 pin 由列车只改两处版本号（ADR 0053 修订 A1），
 * 测试必须对 pin 前后的引擎都成立。
 *
 * <p>core#254 报告的 {@code result of} 交替嵌套在生产 SLL 下一直是毫秒级，作为「正常输入不受影响」的用例保留。
 */
class InProcessCnlParserParseBudgetTest {

    /** 中止相对预算的允许超出量：检查间隔为每 1024 次 closure 调用，实测超出 0–3ms，留足 CI 抖动余量。 */
    private static final long MARGIN_MS = 750;

    /** 每次周期检查的测试停顿：{@link #slowSource()} 在新旧引擎、冷热 DFA 上都有远多于「预算 / 停顿」次的周期检查。 */
    private static final Duration SLOW_STEP = Duration.ofMillis(100);

    @AfterEach
    void restoreDefault() {
        ParseBudget.configure(null);
        ParseBudget.slowChecksForTesting(null);
    }

    /** {@code result of} 成功 / 失败分量交替嵌套 {@code depth} 层，放在参数类型位置（后接逗号，预测最贵）。 */
    static String alternatingResult(int depth) {
        String type = "Int";
        for (int i = 0; i < depth; i++) {
            type = i % 2 == 0 ? "result of " + type + " and Text" : "result of Int and " + type;
        }
        return "Module m.\n\nRule f given x as " + type + ", produce Int:\n  Return 1.\n";
    }

    /**
     * 慢解析输入：一条约 12KB 的合法布尔链（在匿名编译端点 16KB 的源码上限之内）。预测要做数千次 adaptivePredict，
     * 新旧引擎上都有数十次以上的周期检查（DFA 变热后按 adaptivePredict 计），配合 {@link #SLOW_STEP} 稳定超出预算。
     */
    static String slowSource() {
        StringBuilder b = new StringBuilder("Module m.\n\nRule f given x as Int, produce Bool:\n  Return x greater than 0");
        for (int i = 1; i < 600; i++) {
            b.append(i % 2 == 0 ? " and " : " or ").append("x less than ").append(i);
        }
        return b.append(".\n").toString();
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
        assertBudgetE054(e, budgetMs, 4);   // slowSource() 共 4 行
    }

    private static void assertBudgetE054(InProcessCnlParser.CnlParseException e, long budgetMs, int line) {
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
        assertTrue(d.line() >= 1 && d.line() <= line, "位置取中止时的当前 token（源码范围内），实际第 " + d.line() + " 行");
    }

    @Test
    void slowParseIsAbortedAtBudgetWithCodedE054UnderBothPredictionModes() {
        String source = slowSource();
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        for (PredictionMode mode : new PredictionMode[]{PredictionMode.LL, PredictionMode.SLL}) {
            long before = ParseBudget.exceededCount();
            long t0 = System.nanoTime();
            InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
                () -> parseWith(source, Duration.ofMillis(500), mode));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
            assertBudgetE054(e, 500);
            assertTrue(elapsed >= 450 && elapsed < 500 + MARGIN_MS,
                mode + "：应在预算附近中止（不提前、不明显超出），实际 " + elapsed + "ms");
            assertEquals(before + 1, ParseBudget.exceededCount(), "每次中止计数恰加一");
        }
    }

    @Test
    void slowInputOnProductionEntryIsAbortedAtConfiguredBudget() {
        String source = slowSource();
        ParseBudget.configure(Duration.ofMillis(500));
        ParseBudget.slowChecksForTesting(SLOW_STEP);

        long t0 = System.nanoTime();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(source, "en-US"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e, "生产入口（SLL）上的慢解析必须被预算中止");
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
    void normalPolicyIsUnaffectedAndSharedDfaIsNotPoisonedByAnAbort() {
        // 先让一次解析在预测中途被中止（LL 与 SLL 各一次）……
        String slow = slowSource();
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class,
            () -> parseWith(slow, Duration.ofMillis(200), PredictionMode.LL));
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class,
            () -> parseWith(slow, Duration.ofMillis(200), PredictionMode.SLL));
        ParseBudget.slowChecksForTesting(null);

        // ……之后共享 DFA 缓存上的正常解析与中止前完全一致；被中止的同一输入不带停顿也能照常解析完
        assertDoesNotThrow(() -> InProcessCnlParser.parse(slow, "en-US"), "中止过的同一输入在共享 DFA 上仍能解析");
        InProcessCnlParser.ParseResult normal = InProcessCnlParser.parse(NORMAL, "en-US");
        assertEquals("loan", normal.moduleName());
        assertEquals(List.of("approve"), normal.functionNames());
        assertDoesNotThrow(() -> InProcessCnlParser.parse(alternatingResult(32), "en-US"),
            "被中止过的形态在 SLL 下仍能照常解析");
    }

    @Test
    void abortedParseReleasesItsWorkerThread() throws Exception {
        // 单线程池：被中止的解析必须让出线程，后续正常解析才能在同一线程上完成
        String source = slowSource();
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> slow = single.submit(() -> {
                try {
                    parseWith(source, Duration.ofMillis(300), PredictionMode.LL);
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
    void normalPolicyIsNotSlowedByTheTestStep() {
        // 端点并发用例假设「停顿开着时正常请求基本不受影响」（它断言正常请求 < 1000ms）：十几行的正常策略
        // 约百次 adaptivePredict，至多碰到一两次周期检查（实测一次）。这里把该假设钉住：停顿 100ms 时不超过 3 个停顿。
        InProcessCnlParser.parse(NORMAL, "en-US");   // 预热 DFA 与类加载，计时只量解析
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        long t0 = System.nanoTime();
        assertEquals("loan", InProcessCnlParser.parse(NORMAL, "en-US").moduleName());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsed < 3 * SLOW_STEP.toMillis(), "正常策略至多触发一两次周期检查停顿，实际 " + elapsed + "ms");
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
