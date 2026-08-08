package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 批次状态机与不变量（ADR 0034 §1.1 / §3.1）。
 *
 * <p><b>这个测试真正要守的东西</b>：上一版 Phase 4 死于「部分成功被当成全量」。
 * 在异步模型里那个错误会以「没跑完就标 COMPLETED」的形式重现，
 * 而这一步只要有一处代码写错就会发生。故这里穷举**全部** 5×5 迁移组合，
 * 并对「携带数字」的条件做正反两面断言。
 */
class ReplayBatchStateMachineTest {

    /** 合法迁移的完整集合——改这张表就等于改 ADR 0034 §3.1，应当是有意识的动作。 */
    private static final Set<String> LEGAL = Set.of(
        "PENDING->RUNNING",
        "PENDING->FAILED",      // 开跑前即失败：目标版本被删、窗口内零条可重跑
        "RUNNING->COMPLETED",
        "RUNNING->FAILED",
        "COMPLETED->EXPIRED",
        "FAILED->EXPIRED"
    );

    private static ReplayBatchEntity batch(int planned) {
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.tenantId = "t1";
        b.userId = "u1";
        b.policyId = "p1";
        b.baseVersionId = "v1";
        b.targetVersionId = "v2";
        b.windowKind = "LAST_MONTH";
        b.windowLabel = "最近一个月";
        b.plannedCount = planned;
        b.toolchainId = "tc";
        return b;
    }

    // ── 穷举迁移 ────────────────────────────────────────────────────────

    @Test
    void 穷举全部迁移组合_与规则表逐一比对() {
        for (ReplayBatchStatus from : ReplayBatchStatus.values()) {
            for (ReplayBatchStatus to : ReplayBatchStatus.values()) {
                boolean expected = LEGAL.contains(from + "->" + to);
                assertThat(from.canTransitionTo(to))
                    .as("%s → %s 应%s合法", from, to, expected ? "" : "**不**")
                    .isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ReplayBatchStatus.class)
    void 自迁移一律非法(ReplayBatchStatus s) {
        // ★幂等应由条件更新（WHERE status = ?）表达，不能让状态机默许——
        //   否则「重复标记完成」这类 bug 会被掩盖。
        assertThat(s.canTransitionTo(s)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReplayBatchStatus.class)
    void null目标一律非法(ReplayBatchStatus s) {
        assertThat(s.canTransitionTo(null)).isFalse();
    }

    @Test
    void PENDING不得直接跳到COMPLETED() {
        // 没跑过就没有「全量成功」可言——这是 §1.1 的直接推论
        assertThat(ReplayBatchStatus.PENDING.canTransitionTo(ReplayBatchStatus.COMPLETED))
            .isFalse();
    }

    @Test
    void EXPIRED是绝对终态() {
        for (ReplayBatchStatus to : ReplayBatchStatus.values()) {
            assertThat(ReplayBatchStatus.EXPIRED.canTransitionTo(to)).isFalse();
        }
    }

    @Test
    void requireTransitionTo非法时抛出且带上下文() {
        assertThatThrownBy(() ->
            ReplayBatchStatus.COMPLETED.requireTransitionTo(ReplayBatchStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED")
            .hasMessageContaining("RUNNING");
    }

    // ── 状态属性 ────────────────────────────────────────────────────────

    @Test
    void 只有COMPLETED允许携带数字() {
        for (ReplayBatchStatus s : ReplayBatchStatus.values()) {
            assertThat(s.allowsResult())
                .as("%s 是否允许携带聚合结果", s)
                .isEqualTo(s == ReplayBatchStatus.COMPLETED);
        }
    }

    @Test
    void 只有PENDING与RUNNING占并发额度() {
        // 并发上限（ADR 0034 §7.2）只数活跃批次；终态不该继续占额度
        assertThat(ReplayBatchStatus.PENDING.isActive()).isTrue();
        assertThat(ReplayBatchStatus.RUNNING.isActive()).isTrue();
        assertThat(ReplayBatchStatus.COMPLETED.isActive()).isFalse();
        assertThat(ReplayBatchStatus.FAILED.isActive()).isFalse();
        assertThat(ReplayBatchStatus.EXPIRED.isActive()).isFalse();
    }

    // ── 不变量：全成功才出数（§1.1 的应用层闸门）──────────────────────

    @Test
    void 有失败条目时不得标记COMPLETED() {
        ReplayBatchEntity b = batch(10);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.completedCount = 9;
        b.failedCount = 1;

        assertThatThrownBy(() -> b.markCompleted("{\"changed\":5}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1 条失败");

        assertThat(b.status).as("失败的尝试不得改变状态").isEqualTo(ReplayBatchStatus.RUNNING);
        assertThat(b.resultSummary).as("不得残留数字").isNull();
    }

    @Test
    void 未跑满planned时不得标记COMPLETED() {
        // ★这是选择偏差的入口：7/10 成功就出数 = 用子集冒充总体
        ReplayBatchEntity b = batch(10);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.completedCount = 7;
        b.failedCount = 0;

        assertThatThrownBy(() -> b.markCompleted("{\"changed\":5}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("7/10");

        assertThat(b.resultSummary).isNull();
    }

    @Test
    void COMPLETED必须携带聚合结果() {
        ReplayBatchEntity b = batch(3);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.completedCount = 3;

        assertThatThrownBy(() -> b.markCompleted(null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.markCompleted("  "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 全量成功才能标记COMPLETED() {
        ReplayBatchEntity b = batch(3);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.completedCount = 3;
        b.failedCount = 0;

        assertThatCode(() -> b.markCompleted("{\"changed\":2}")).doesNotThrowAnyException();
        assertThat(b.status).isEqualTo(ReplayBatchStatus.COMPLETED);
        assertThat(b.resultSummary).isEqualTo("{\"changed\":2}");
        assertThat(b.finishedAt).as("终态应记录完成时刻").isNotNull();
    }

    @Test
    void markFailed强制清空数字() {
        // 拒答的批次不得残留任何数字，否则读路径可能把它当结论呈现
        ReplayBatchEntity b = batch(5);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.resultSummary = "{\"changed\":99}";   // 模拟中途写入的脏值

        b.markFailed("{\"VOCAB_MISSING\":3}");

        assertThat(b.status).isEqualTo(ReplayBatchStatus.FAILED);
        assertThat(b.resultSummary).as("★FAILED 必须零数字").isNull();
        assertThat(b.failureReasons).contains("VOCAB_MISSING");
    }

    @Test
    void markExpired保留元数据但清空数字() {
        ReplayBatchEntity b = batch(2);
        b.transitionTo(ReplayBatchStatus.RUNNING);
        b.completedCount = 2;
        b.markCompleted("{\"changed\":1}");

        b.markExpired();

        assertThat(b.status).isEqualTo(ReplayBatchStatus.EXPIRED);
        assertThat(b.resultSummary).as("过期数字必须清掉——陈旧数字比没有更危险").isNull();
        assertThat(b.plannedCount).as("元数据保留：用户仍能看到「我当时跑过」").isEqualTo(2);
        assertThat(b.windowLabel).isEqualTo("最近一个月");
    }

    @Test
    void 时间戳随迁移维护() {
        ReplayBatchEntity b = batch(1);
        assertThat(b.startedAt).isNull();

        b.transitionTo(ReplayBatchStatus.RUNNING);
        assertThat(b.startedAt).as("进 RUNNING 记开始时刻").isNotNull();
        assertThat(b.finishedAt).isNull();

        b.completedCount = 1;
        b.markCompleted("{}");
        assertThat(b.finishedAt).as("进终态记完成时刻").isNotNull();
    }

    @Test
    void isAllProcessed同时计成功与失败() {
        ReplayBatchEntity b = batch(10);
        b.completedCount = 6;
        b.failedCount = 3;
        assertThat(b.isAllProcessed()).isFalse();

        b.failedCount = 4;
        assertThat(b.isAllProcessed()).isTrue();
    }
}
