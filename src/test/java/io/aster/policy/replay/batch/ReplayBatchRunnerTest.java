package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次结局判定（ADR 0034 §1.1 的核心）。
 *
 * <p><b>这个测试守的是 Phase 4 的死因</b>：上一版允许「200 条发起、30 条成功」
 * 就对这 30 条出完整业务数字。重跑失败与输入/词汇/策略路径**相关**，
 * 剩下的成功样本不是随机子集——据此算出的数字可能方向对而幅度全错。
 *
 * <p>故这里的核心断言只有一条，但要从各个角度钉死：
 * <b>只要有一条失败，就不得出现任何数字。</b>
 */
class ReplayBatchRunnerTest {

    private static ReplayBatchRunner.ItemResult ok(String id, boolean base, boolean target) {
        return ReplayBatchRunner.ItemResult.ok(id, base, target, null);
    }

    private static ReplayBatchRunner.ItemResult okWithValue(
        String id, boolean base, boolean target, String delta) {
        return ReplayBatchRunner.ItemResult.ok(id, base, target, new BigDecimal(delta));
    }

    private static List<ReplayBatchRunner.ItemResult> allOk(int n) {
        List<ReplayBatchRunner.ItemResult> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(ok("e" + i, true, true));
        }
        return l;
    }

    // ── 核心：任一失败即整批拒答 ────────────────────────────────────────

    @Test
    void 单条失败即整批拒答且零数字() {
        // ★这正是 Phase 4 的场景：199 条成功、1 条失败。
        //   上一版会对那 199 条出数字；本设计必须拒答。
        List<ReplayBatchRunner.ItemResult> results = allOk(199);
        results.add(ReplayBatchRunner.ItemResult.failed("e199", ReplayFailureKind.INPUT_INCOMPATIBLE));

        ReplayBatchOutcome outcome = ReplayBatchRunner.decide(200, results);

        assertThat(outcome)
            .as("★199/200 成功也必须拒答——成功子集不是随机样本")
            .isInstanceOf(ReplayBatchOutcome.Rejected.class);

        ReplayBatchOutcome.Rejected r = (ReplayBatchOutcome.Rejected) outcome;
        assertThat(r.failuresByKind()).containsEntry(ReplayFailureKind.INPUT_INCOMPATIBLE, 1);
    }

    @Test
    void 拒答结局在类型层面就拿不出业务数字() {
        // ★Rejected 这个 record 根本没有 changed/newlyApproved 之类的字段——
        //   不是「有字段但填 0」，是**类型上不存在**。
        //   这是刻意的：给了前端就会自行算比率，那正是 §1.1 要防的。
        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(allOk(4));
        results.add(ReplayBatchRunner.ItemResult.failed("x", ReplayFailureKind.TIMEOUT));

        ReplayBatchOutcome outcome = ReplayBatchRunner.decide(5, results);

        java.util.Set<String> fields = new java.util.HashSet<>();
        for (var c : ReplayBatchOutcome.Rejected.class.getRecordComponents()) {
            fields.add(c.getName());
        }
        assertThat(fields)
            .as("拒答结局不得含任何会被读成结论的数字字段")
            .doesNotContain("changed", "newlyApproved", "newlyRejected",
                "totalSampled", "estimatedValueDelta", "successCount", "partialCount");
        assertThat(outcome).isInstanceOf(ReplayBatchOutcome.Rejected.class);
    }

    @ParameterizedTest
    @EnumSource(ReplayFailureKind.class)
    void 任何一类失败都导致拒答(ReplayFailureKind kind) {
        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(allOk(9));
        results.add(ReplayBatchRunner.ItemResult.failed("bad", kind));

        assertThat(ReplayBatchRunner.decide(10, results))
            .isInstanceOf(ReplayBatchOutcome.Rejected.class);
    }

    @Test
    void 多类失败的分布如实回报() {
        // 「失败了」不够——用户要知道是自己数据的问题还是服务端繁忙
        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(allOk(5));
        results.add(ReplayBatchRunner.ItemResult.failed("a", ReplayFailureKind.INPUT_INCOMPATIBLE));
        results.add(ReplayBatchRunner.ItemResult.failed("b", ReplayFailureKind.INPUT_INCOMPATIBLE));
        results.add(ReplayBatchRunner.ItemResult.failed("c", ReplayFailureKind.TIMEOUT));

        var r = (ReplayBatchOutcome.Rejected) ReplayBatchRunner.decide(8, results);

        assertThat(r.failuresByKind())
            .containsEntry(ReplayFailureKind.INPUT_INCOMPATIBLE, 2)
            .containsEntry(ReplayFailureKind.TIMEOUT, 1);
        assertThat(r.totalFailures()).isEqualTo(3);
        assertThat(r.allRetryable()).as("含 INPUT_INCOMPATIBLE 则非全可重试").isFalse();
    }

    @Test
    void 全部可重试类失败才标记为可重试() {
        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(allOk(2));
        results.add(ReplayBatchRunner.ItemResult.failed("a", ReplayFailureKind.TIMEOUT));
        results.add(ReplayBatchRunner.ItemResult.failed("b", ReplayFailureKind.THROTTLED));

        var r = (ReplayBatchOutcome.Rejected) ReplayBatchRunner.decide(4, results);
        assertThat(r.allRetryable()).isTrue();
    }

    // ── 完整性：少一条都不算跑完 ────────────────────────────────────────

    @Test
    void 结果条数少于计划时抛出而非降级() {
        // ★不降级为 Rejected：那会把「worker 有 bug」伪装成「用户数据有问题」，
        //   掩盖真正的缺陷。宁可炸掉让人看见。
        assertThatThrownBy(() -> ReplayBatchRunner.decide(10, allOk(7)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("7")
            .hasMessageContaining("10");
    }

    @Test
    void 结果条数多于计划同样抛出() {
        assertThatThrownBy(() -> ReplayBatchRunner.decide(3, allOk(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空结果不得被判成功() {
        // 「零条执行也算全量成功」是最危险的一种假成功
        assertThatThrownBy(() -> ReplayBatchRunner.decide(5, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 全量成功路径 ───────────────────────────────────────────────────

    @Test
    void 全部成功时样本即全量() {
        List<ReplayBatchRunner.ItemResult> results = List.of(
            ok("e1", true, true),
            ok("e2", true, false),    // 通过 → 拒绝
            ok("e3", false, true),    // 拒绝 → 通过
            ok("e4", false, false));

        var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(4, results);

        assertThat(c.totalSampled()).as("样本数必须等于计划数——全量").isEqualTo(4);
        assertThat(c.changed()).isEqualTo(2);
        assertThat(c.newlyRejected()).isEqualTo(1);
        assertThat(c.newlyApproved()).isEqualTo(1);
    }

    @Test
    void 无金额基线时保持null而非0() {
        // ★「无法估算」与「估算为零」是两回事。渲染成 0 会被读成
        //   「换版本没有金额影响」——一个没有依据的结论。
        var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(2,
            List.of(ok("e1", true, false), ok("e2", true, true)));

        assertThat(c.estimatedValueDelta())
            .as("无金额基线必须是 null，不得兜底成 0")
            .isNull();
    }

    @Test
    void 有金额基线时累加变化条目() {
        var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(3, List.of(
            okWithValue("e1", true, false, "-100.50"),
            okWithValue("e2", false, true, "200.25"),
            okWithValue("e3", true, true, "999")));   // 未变化 → 不计入

        assertThat(c.estimatedValueDelta()).isEqualByComparingTo(new BigDecimal("99.75"));
        assertThat(c.changed()).isEqualTo(2);
    }

    // ── 不变量 ────────────────────────────────────────────────────────

    @Test
    void Completed的计数不变量() {
        assertThatThrownBy(() ->
            new ReplayBatchOutcome.Completed(5, 3, 3, 4, null))
            .as("翻转之和不得超过变化总数")
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            new ReplayBatchOutcome.Completed(10, 0, 0, 5, null))
            .as("变化条数不得超过样本总数")
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            new ReplayBatchOutcome.Completed(0, 0, 0, 0, null))
            .as("样本数必须为正")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 拒答必须带原因() {
        // ★空 Map 与 null 要分开测：null 会被后续的 EnumMap 构造抛 NPE，
        //   于是「断言抛异常」这种宽松写法在守卫被删掉后**依然通过**（实测假绿）。
        //   故必须断言**具体异常类型 + 消息**，否则测不到守卫本身。
        assertThatThrownBy(() -> new ReplayBatchOutcome.Rejected(java.util.Map.of()))
            .as("空原因：不能只说「失败了」")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("失败原因");

        assertThatThrownBy(() -> new ReplayBatchOutcome.Rejected(null))
            .as("null 原因同样要被守卫拦下，而不是靠下游 NPE")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("失败原因");
    }

    @Test
    void 失败条目必须带分类() {
        assertThatThrownBy(() -> ReplayBatchRunner.ItemResult.failed("x", null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
