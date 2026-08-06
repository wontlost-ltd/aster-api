package io.aster.policy.analysis;

import io.aster.policy.parser.DynamicCnlExecutor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADR 0033 S0 Spike：量化「按需重求值」的真实成本。
 *
 * <p>ADR 把「不落对照表、每次现场重跑」定为方案，前提是**重跑足够快**。
 * 这个前提没量过就不该往下走，故本 Spike 只做一件事：给出真实数字。
 *
 * <p>出口判据（ADR §5）：单条 &gt;100ms 就该回去重新评估预计算表。
 *
 * <p>★这不是回归测试，是一次性测量。它不断言性能阈值（那会在 CI 上变成
 * 环境噪音驱动的 flaky），只把数字打出来供决策。
 */
class WhatIfReplaySpikeTest {

    /** 基线版本：门槛 600。 */
    private static final String V1 = """
        Module m.

        Rule assess given score as Number produce Text:
          If score is greater than 600:
            Return "APPROVED".
          Return "REJECTED".
        """;

    /** 目标版本：门槛收紧到 700 —— 正是 What-if 要回答的那类改动。 */
    private static final String V2 = """
        Module m.

        Rule assess given score as Number produce Text:
          If score is greater than 700:
            Return "APPROVED".
          Return "REJECTED".
        """;

    private static Object ctx(int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        return m;
    }

    @Test
    void spike_measure_cross_version_replay_cost() {
        final int N = 200; // ADR §4 建议的单次上限

        // 预热：首次调用含类加载/解析器初始化，不计入
        for (int i = 0; i < 20; i++) {
            DynamicCnlExecutor.executeWithContext(V2, ctx(650 + i), "assess", "en");
        }

        int ok = 0;
        int failed = 0;
        int changed = 0;
        long totalNanos = 0;
        long maxNanos = 0;

        for (int i = 0; i < N; i++) {
            int score = 550 + (i % 200); // 覆盖 600 与 700 两个门槛
            Object c = ctx(score);

            var baseResult = DynamicCnlExecutor.executeWithContext(V1, c, "assess", "en");

            long t0 = System.nanoTime();
            var targetResult = DynamicCnlExecutor.executeWithContext(V2, c, "assess", "en");
            long dt = System.nanoTime() - t0;

            totalNanos += dt;
            maxNanos = Math.max(maxNanos, dt);

            if (targetResult == null || targetResult.result() == null) {
                failed++;
                continue;
            }
            ok++;
            if (baseResult != null && baseResult.result() != null
                    && !String.valueOf(baseResult.result()).equals(String.valueOf(targetResult.result()))) {
                changed++;
            }
        }

        double avgMs = totalNanos / 1_000_000.0 / N;
        double maxMs = maxNanos / 1_000_000.0;
        double totalMs = totalNanos / 1_000_000.0;

        System.out.printf(
            "SPIKE N=%d ok=%d failed=%d changed=%d avg=%.2fms max=%.2fms total(%d条)=%.0fms 判据(<100ms)=%s%n",
            N, ok, failed, changed, avgMs, maxMs, N, totalMs, avgMs < 100 ? "PASS" : "FAIL");

        // 只断言「跑得通」，不断言性能数字——见类注释
        org.junit.jupiter.api.Assertions.assertTrue(ok > 0, "重放应当能成功执行");
    }
}
