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
    void plannedCount必须由冻结窗口派生且冻结须独立提交() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★plannedCount 必须由**冻结窗口的大小**派生——"
                + "创建时写 0 且从不回填会让任何批次都跑不完")
            .contains("batch.plannedCount = window.size()");

        // ★这条断言此前写成「persist() 紧跟在赋值之后」，而那**锁住了错误的事务语义**：
        //   当时那段代码在 replayAll 里，由 @Transactional runBatch 调用、加入同一长事务，
        //   persist() 根本不提交，worker 崩溃即回滚。测试却因为「文本相邻」而全绿——
        //   审查者称之为假绿，属实。
        //   真正的不变量是：冻结必须在**自己的事务**里提交（REQUIRES_NEW），
        //   否则它不是崩溃后仍可见的检查点。
        int freeze = src.indexOf("public int freezeWindow(");
        assertThat(freeze).as("★必须有独立的冻结方法").isGreaterThan(0);

        String beforeFreeze = src.substring(0, freeze);
        assertThat(beforeFreeze)
            .as("★freezeWindow 必须标注 REQUIRES_NEW——"
                + "并进调用方的长事务就不是检查点，崩溃会一起回滚")
            .containsPattern("(?s)@Transactional\\(Transactional\\.TxType\\.REQUIRES_NEW\\)\\s*$");
    }

    /**
     * 总体必须冻结**成员**，不只是数量。
     *
     * <p>只存数量时，回收重跑会重新拉窗口——上游若已变化（迟到写入、删除、
     * replayable 状态变更），跑的就不是同一个总体了，
     * 而「样本即某个总体的全量」正是 §1.1 的前提。
     */
    @Test
    void 必须冻结execution_id集合而非只存数量() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★冻结时必须把 execution id 与基线落表")
            .contains("new ReplayBatchItemEntity(");
        assertThat(src)
            .as("★重跑必须读**冻结集合**，不得重新拉窗口决定成员")
            .contains("ReplayBatchItemEntity")
            .contains("batchId = ?1");
        // ★断言**调用点真的传了冻结值**，而不是「文件里出现过这个词」。
        //   只断言词出现是空的：把调用点改回 `e.baseApproved()`（读上游当前值）
        //   时该断言仍绿——实测过，这正是本仓反复出现的假绿形态。
        assertThat(src)
            .as("★基线必须取**冻结时**的值并传入 replayOne——"
                + "上游 decision 可能因数据订正而变，"
                + "读当前值会让「变化了多少条」随时间漂移")
            .contains("replayOne(e, item.baseApproved,");
        assertThat(src)
            .as("★不得在重跑时读上游的当前基线")
            .doesNotContain("replayOne(e, e.baseApproved()");
    }

    /**
     * ★批次规模上限必须**被真的执行**，而不只是声明一个常量（§10.3）。
     *
     * <p>本仓有前科：{@code MAX_BATCH_SIZE} 一度只在 Javadoc 里被引用，
     * 没有任何判定代码——那就是死常量，跟没有一样。
     * 同理 {@code PermitLease} 曾整条是死代码而 13 条测试全绿。
     */
    @Test
    void 规模上限必须在冻结时判定且拒答() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★上限必须被真的比较，而不是只声明一个常量")
            .contains("window.size() > MAX_BATCH_SIZE");
        assertThat(src)
            .as("★超限要拒答并给出**可操作**的类别——"
                + "告诉用户缩小时间窗，而不是让他去排查数据")
            .contains("ReplayFailureKind.WINDOW_TOO_LARGE");

        int check = src.indexOf("window.size() > MAX_BATCH_SIZE");
        int persistItems = src.indexOf("new ReplayBatchItemEntity(");
        assertThat(check)
            .as("★判定必须在冻结落表**之前**——超限批次不该先写一万行再失败")
            .isLessThan(persistItems);
    }

    /** 空窗口是正当业务结果，不得走 decide 的异常路径（那是给编程错误用的）。 */
    @Test
    void 空窗口必须在decide之前分流() throws Exception {
        String src = serviceSource();

        // 值对象化后（§11.1 全程 detached）判定表达式从 batch.plannedCount
        // 变成 snap.plannedCount()；不变量本身没变：空窗口必须先于 decide 分流。
        assertThat(src)
            .as("★空窗口必须在调用 decide 之前分流——"
                + "否则 plannedCount=0 会抛异常，把「这段时间没有执行」伪装成系统故障")
            .contains("if (snap.plannedCount() == 0)");

        int emptyBranch = src.indexOf("if (snap.plannedCount() == 0)");
        int decideCall = src.indexOf("ReplayBatchRunner.decide(");
        assertThat(emptyBranch)
            .as("★空窗口分流必须在 decide 调用之前")
            .isLessThan(decideCall);
    }
}
