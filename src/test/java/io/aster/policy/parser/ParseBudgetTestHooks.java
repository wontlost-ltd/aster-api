package io.aster.policy.parser;

import java.time.Duration;

/**
 * aster-api 测试访问 {@link ParseBudget} 包级测试接缝的唯一入口（与 replay 的 ParseBudget 同包，只在测试源里）。
 * 生产代码看不到这个类，也就调不到 {@link ParseBudget#slowChecksForTesting}。
 */
public final class ParseBudgetTestHooks {

    private ParseBudgetTestHooks() {
    }

    /** 见 {@link ParseBudget#slowChecksForTesting}；null 关闭。 */
    public static void slowChecks(Duration delay) {
        ParseBudget.slowChecksForTesting(delay);
    }
}
