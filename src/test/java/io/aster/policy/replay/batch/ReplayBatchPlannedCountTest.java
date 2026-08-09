package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code plannedCount} 必须由**冻结的窗口**派生并在开跑前落库（ADR 0034）。
 *
 * <h2>被修复的 bug</h2>
 *
 * <p>创建批次时写 {@code batch.plannedCount = 0} 并注释「worker 拉完窗口后回填」，
 * 但<b>全仓对该字段零赋值</b>——从来没有人回填。而
 * {@link ReplayBatchRunner#decide} 对 {@code plannedCount <= 0} 必抛异常，
 * 于是<b>任何批次都跑不完</b>：整个 What-If 功能在生产上不可能产出结果。
 *
 * <p>这是「注释声称 ≠ 实现如此」的又一例——注释把未完成的工作描述成已完成的。
 */
class ReplayBatchPlannedCountTest {

    private static String serviceSource() throws Exception {
        return Files.readString(
            Path.of("src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java"));
    }

    /**
     * ★行为断言：decide 拒绝非正的 plannedCount。
     *
     * <p>这条锁住的是「为什么 plannedCount=0 会让批次永远跑不完」，
     * 即被修复 bug 的<b>后果</b>。
     */
    @Test
    void decide对非正的plannedCount必须抛出而不是静默降级() {
        assertThatThrownBy(() -> ReplayBatchRunner.decide(0, java.util.List.of()))
            .as("plannedCount=0 必须炸出来，而不是被当成「零条全部成功」")
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ReplayBatchRunner.decide(-1, java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ★结果条数必须与 plannedCount 严格相等——这是 §1.1 的算术形式：
     * 少一条就说明样本不是总体的全量。
     */
    @Test
    void 结果条数与plannedCount不符必须抛出() {
        var oneOk = java.util.List.of(
            ReplayBatchRunner.ItemResult.ok("e1", true, true, null));

        assertThatThrownBy(() -> ReplayBatchRunner.decide(2, oneOk))
            .as("★1 条结果 vs 计划 2 条：样本不是全量，不得判定结局")
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * plannedCount 必须在 worker 里由窗口派生并落库。
     *
     * <p>★这条用源码断言，但**不切窗口**——切窗口的测试边界是人选的，
     * 而 bug 爱待在边界外（本仓已有实证：192 字符窗口漏掉了窗口外的泄漏）。
     * 这里断言的是整份文件里的两个事实，与位置无关。
     */
    @Test
    void plannedCount必须由窗口派生并在开跑前落库() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★plannedCount 必须由**冻结窗口的大小**派生——"
                + "创建时写 0 且从不回填会让任何批次都跑不完")
            .contains("batch.plannedCount = window.size()");

        assertThat(src)
            .as("★派生之后必须立即落库：worker 崩溃时总体量不能丢")
            .containsPattern("(?s)batch\\.plannedCount = window\\.size\\(\\);\\s*batch\\.persist\\(\\);");
    }

    /** 空窗口是正当业务结果，不得走 decide 的异常路径（那是给编程错误用的）。 */
    @Test
    void 空窗口必须在decide之前分流() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★空窗口必须在调用 decide 之前分流——"
                + "否则 plannedCount=0 会抛异常，把「这段时间没有执行」伪装成系统故障")
            .contains("if (batch.plannedCount == 0)");

        int emptyBranch = src.indexOf("if (batch.plannedCount == 0)");
        int decideCall = src.indexOf("ReplayBatchRunner.decide(");
        assertThat(emptyBranch)
            .as("★空窗口分流必须在 decide 调用之前")
            .isLessThan(decideCall);
    }
}
