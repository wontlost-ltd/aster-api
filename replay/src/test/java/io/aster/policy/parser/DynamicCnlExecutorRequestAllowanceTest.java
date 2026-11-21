package io.aster.policy.parser;

import aster.core.embed.InProcessCnlParser;
import aster.core.embed.ParseBudget;
import aster.core.embed.ParseBudgetTestHooks;
import io.aster.replay.core.module.ModuleGraphResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器的请求级解析额度（wontlost-ltd/aster-api#489）：入口源码与它 import 的全部模块共用一份额度（= 进程级预算）。
 * 额度本身的语义由 core 的 {@code ParseBudgetRequestAllowanceTest} 覆盖；这里只验证 {@link DynamicCnlExecutor}
 * 确实把跨模块编译包在一份额度里。
 *
 * <p>慢解析：浅规则堆叠的合法源码 + {@link ParseBudgetTestHooks#slowChecks} 的周期检查停顿（与 core 的预算测试同形）。
 * 用假的模块解析器在同一线程上解析两个「慢模块」：第一个在它自己的小额度里被中止、消耗掉执行器额度的大部分，
 * 第二个只剩余量——没有执行器级额度时它会跑满单次预算。
 */
class DynamicCnlExecutorRequestAllowanceTest {

    private static final Duration SLOW_STEP = Duration.ofMillis(100);
    /** 中止相对预期的允许超出量（与 core 的预算测试一致）。 */
    private static final long MARGIN_MS = 750;
    private static final int SLOW_RULES = 160;

    @AfterEach
    void restoreDefault() {
        ParseBudget.configure(null);
        ParseBudgetTestHooks.slowChecks(null);
    }

    private static String slowSource() {
        StringBuilder b = new StringBuilder("Module m.\n");
        for (int i = 0; i < SLOW_RULES; i++) {
            b.append("\nRule f").append(i).append(" given x as Int, produce Bool:\n  Return x greater than ")
                .append(i).append(" and x less than ").append(i + 10).append(".\n");
        }
        return b.toString();
    }

    /** 慢解析必然被预算中止，返回中止前的耗时与诊断里报告的预算。 */
    private static long[] abortedSlowParse() {
        long t0 = System.nanoTime();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(slowSource(), "en-US"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        String budgetMs = e.getDiagnostics().stream().filter(d -> "E054".equals(d.code()))
            .findFirst().orElseThrow().params().get("budgetMs");
        return new long[]{elapsed, Long.parseLong(budgetMs)};
    }

    @Test
    void 跨模块执行时入口与全部import共用一份额度() {
        ParseBudget.configure(Duration.ofMillis(2000));
        AtomicLong secondImportMs = new AtomicLong(-1);
        AtomicReference<String> reported = new AtomicReference<>();
        ModuleGraphResolver resolver = (tenantId, rootCore, rootImports, locale) -> {
            ParseBudgetTestHooks.slowChecks(SLOW_STEP);
            try {
                ParseBudget.withinRequestAllowance(Duration.ofMillis(1500),
                    DynamicCnlExecutorRequestAllowanceTest::abortedSlowParse);
                long[] second = abortedSlowParse();
                secondImportMs.set(second[0]);
                reported.set(String.valueOf(second[1]));
                throw new InProcessCnlParser.CnlParseException("import 解析超出预算");
            } finally {
                ParseBudgetTestHooks.slowChecks(null);
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
}
