package io.aster.policy.parser;

import io.aster.replay.core.module.ModuleGraphResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级解析额度（wontlost-ltd/aster-api#489）：一次请求内多次解析共用一份预算，而不是每次解析各一份
 * （跨模块 import 时一次请求的总解析时间可达 N × 预算）。慢解析同 {@link InProcessCnlParserParseBudgetTest}：
 * 浅规则堆叠的合法源码 + 周期检查停顿。
 *
 * <p>判别方式：先在嵌套的小额度里把一次慢解析中止掉（消耗掉外层额度的一大块），再在外层额度里做下一次慢解析，
 * 它必须在「外层剩余」附近中止，远早于单次预算——没有请求级额度时它会跑满单次预算。
 */
class ParseBudgetRequestAllowanceTest {

    private static final Duration SLOW_STEP = Duration.ofMillis(100);
    /** 中止相对预期的允许超出量（与 InProcessCnlParserParseBudgetTest 一致）。 */
    private static final long MARGIN_MS = 750;

    private static final String NORMAL = "Module m.\n\nRule f given x as Int, produce Int:\n  Return x.\n";

    @AfterEach
    void restoreDefault() {
        ParseBudget.configure(null);
        ParseBudget.slowChecksForTesting(null);
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    /** 慢解析必然被预算中止，返回中止前的耗时与诊断里报告的预算。 */
    private static long[] abortedSlowParse() {
        long t0 = System.nanoTime();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(InProcessCnlParserParseBudgetTest.slowSource(), "en-US"));
        long elapsed = millisSince(t0);
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        String budgetMs = e.getDiagnostics().stream().filter(d -> "E054".equals(d.code()))
            .findFirst().orElseThrow().params().get("budgetMs");
        return new long[]{elapsed, Long.parseLong(budgetMs)};
    }

    @Test
    void 额度内的多次解析共用一份预算() {
        ParseBudget.configure(Duration.ofMillis(2000));
        ParseBudget.slowChecksForTesting(SLOW_STEP);

        long[] second = ParseBudget.withinRequestAllowance(Duration.ofMillis(2000), () -> {
            // 第一次解析在内层 1500ms 额度处中止，同时从外层 2000ms 里扣掉约 1500ms
            long[] first = ParseBudget.withinRequestAllowance(Duration.ofMillis(1500), ParseBudgetRequestAllowanceTest::abortedSlowParse);
            assertTrue(first[0] >= 1400, "内层额度到点前不应中止，实际 " + first[0] + "ms");
            assertEquals(1500, first[1], "诊断报告约束它的额度总量");
            return abortedSlowParse();
        });

        assertTrue(second[0] < 500 + MARGIN_MS,
            "第二次解析只剩约 500ms 额度，应在其附近中止（没有请求级额度时会跑满 2000ms），实际 " + second[0] + "ms");
        assertEquals(2000, second[1], "报告请求级额度总量");
    }

    @Test
    void 额度外的解析各自按单次预算() {
        ParseBudget.configure(Duration.ofMillis(600));
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        long[] first = abortedSlowParse();
        long[] second = abortedSlowParse();
        assertTrue(first[0] >= 550 && second[0] >= 550, "不在额度内时每次解析都有完整的单次预算");
        assertEquals(600, second[1]);
    }

    @Test
    void 额度比单次预算紧时按额度中止并报告额度() {
        ParseBudget.configure(Duration.ofMillis(2000));
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        long[] r = ParseBudget.withinRequestAllowance(Duration.ofMillis(400), ParseBudgetRequestAllowanceTest::abortedSlowParse);
        assertTrue(r[0] >= 350 && r[0] < 400 + MARGIN_MS, "应在 400ms 额度附近中止，实际 " + r[0] + "ms");
        assertEquals(400, r[1]);
    }

    @Test
    void 额度宽于单次预算时单次预算照旧生效() {
        ParseBudget.configure(Duration.ofMillis(400));
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        long[] r = ParseBudget.withinRequestAllowance(Duration.ofSeconds(10), ParseBudgetRequestAllowanceTest::abortedSlowParse);
        assertTrue(r[0] < 400 + MARGIN_MS, "单次预算 400ms 仍是上限，实际 " + r[0] + "ms");
        assertEquals(400, r[1]);
    }

    @Test
    void 额度用尽后同一请求内的后续解析一律中止_额度退出后恢复() {
        ParseBudget.configure(Duration.ofMillis(2000));
        ParseBudget.slowChecksForTesting(SLOW_STEP);
        ParseBudget.withinRequestAllowance(Duration.ofMillis(300), () -> {
            abortedSlowParse();
            ParseBudget.slowChecksForTesting(null);
            // 额度已用尽：预算取 1ns，进入 adaptivePredict 即检查，正常的小模块同样中止（本请求的解析时间已花完）
            InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
                () -> InProcessCnlParser.parse(NORMAL, "en-US"));
            assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
            return null;
        });
        // 额度只在作用域内生效：退出后恢复单次预算，同一线程上的下一个请求不受影响
        assertEquals("m", InProcessCnlParser.parse(NORMAL, "en-US").moduleName());
        assertEquals(2000, ParseBudget.startDefault().budgetMillis());
    }

    @Test
    void 进程级预算为零时额度也不生效() {
        ParseBudget.configure(Duration.ZERO);
        ParseBudget budget = ParseBudget.withinRequestAllowance(Duration.ofMillis(300), ParseBudget::startDefault);
        assertEquals(0, budget.budgetMillis(), "0 是不限时总开关");
    }

    @Test
    void 额度为空或非正时不加额度() {
        ParseBudget.configure(Duration.ofMillis(2000));
        assertEquals(2000, ParseBudget.withinRequestAllowance(null, ParseBudget::startDefault).budgetMillis());
        assertEquals(2000, ParseBudget.withinRequestAllowance(Duration.ZERO, ParseBudget::startDefault).budgetMillis());
    }

    /**
     * 跨模块执行：入口源码与 import 的模块共用一份额度（= 进程级预算）。用假的模块解析器在同一线程上解析两个
     * 「慢模块」：第一个在它自己的小额度里被中止、消耗掉执行器额度的大部分，第二个只剩余量——没有执行器级额度时
     * 它会跑满单次预算。
     */
    @Test
    void 跨模块执行时入口与全部import共用一份额度() {
        ParseBudget.configure(Duration.ofMillis(2000));
        AtomicLong secondImportMs = new AtomicLong(-1);
        AtomicReference<String> reported = new AtomicReference<>();
        ModuleGraphResolver resolver = (tenantId, rootCore, rootImports, locale) -> {
            ParseBudget.slowChecksForTesting(SLOW_STEP);
            try {
                ParseBudget.withinRequestAllowance(Duration.ofMillis(1500), ParseBudgetRequestAllowanceTest::abortedSlowParse);
                long[] second = abortedSlowParse();
                secondImportMs.set(second[0]);
                reported.set(String.valueOf(second[1]));
                throw new InProcessCnlParser.CnlParseException("import 解析超出预算");
            } finally {
                ParseBudget.slowChecksForTesting(null);
            }
        };
        DynamicCnlExecutor executor = new DynamicCnlExecutor(resolver, true);
        String root = "Module app.Root.\nUse lib.A version 1 as A.\n\nRule run given x as Int, produce Int:\n  Return x.\n";

        RuntimeException e = assertThrows(RuntimeException.class, () -> executor.executeWithTenantContext(
            "tenant-489", root, new Object[]{1}, "run", "en-US", null, false));
        assertNotNull(e);
        assertTrue(secondImportMs.get() >= 0, "假解析器应被调用");
        assertTrue(secondImportMs.get() < 500 + MARGIN_MS,
            "第二个 import 只剩约 500ms 额度，实际 " + secondImportMs.get() + "ms（没有请求级额度时会跑满 2000ms）");
        assertEquals("2000", reported.get());
    }

    @Test
    void 额度不泄漏到线程上的后续工作() {
        ParseBudget.configure(Duration.ofMillis(2000));
        assertThrows(IllegalStateException.class, () -> ParseBudget.withinRequestAllowance(Duration.ofMillis(100), () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(2000, ParseBudget.startDefault().budgetMillis());
        List<Long> seen = ParseBudget.withinRequestAllowance(Duration.ofMillis(100),
            () -> List.of(ParseBudget.startDefault().budgetMillis()));
        assertEquals(List.of(100L), seen);
    }
}
