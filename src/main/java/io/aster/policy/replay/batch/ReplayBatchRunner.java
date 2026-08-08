package io.aster.policy.replay.batch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 批次执行的**纯逻辑**部分：给定逐条重跑结果，判定批次结局。
 *
 * <p><b>为什么把这段抽成不依赖任何注入的纯类</b>：ADR 0034 §1.1 的全部约束
 * 都落在这里——「全部成功才出数字，任一失败即整批拒答」。这条逻辑必须能被
 * 纯 JUnit 穷举覆盖，而不是只能通过 {@code @QuarkusTest} + 真库间接触及。
 *
 * <p>这是从上一版的教训里直接来的：Phase 4 时期把 lease 生命周期内联在
 * REST 方法里，导致测试只能在测试文件里手写一份副本来测，
 * 生产逻辑整条改坏也不报红（见 aster-api #222）。同样的错误不重犯。
 */
public final class ReplayBatchRunner {

    private ReplayBatchRunner() {
    }

    /** 单条重跑的结果。{@code failureKind} 非空即表示这一条失败。 */
    public record ItemResult(
        String sourceExecutionId,
        boolean baseApproved,
        boolean targetApproved,
        java.math.BigDecimal valueDelta,
        ReplayFailureKind failureKind
    ) {

        public static ItemResult ok(String id, boolean baseApproved, boolean targetApproved,
                                    java.math.BigDecimal valueDelta) {
            return new ItemResult(id, baseApproved, targetApproved, valueDelta, null);
        }

        public static ItemResult failed(String id, ReplayFailureKind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("失败必须带分类——「失败了」不足以让用户决策");
            }
            return new ItemResult(id, false, false, null, kind);
        }

        public boolean isFailure() {
            return failureKind != null;
        }
    }

    /**
     * 由逐条结果判定批次结局。
     *
     * <p><b>判定规则（不可放宽）</b>：
     * <ol>
     *   <li>结果条数必须等于 {@code plannedCount}——少一条都不算跑完。
     *       这堵住的是「worker 提前退出后被当成完成」这条路径。</li>
     *   <li>任一条失败 → {@link ReplayBatchOutcome.Rejected}，<b>不出任何数字</b>。</li>
     *   <li>全部成功 → {@link ReplayBatchOutcome.Completed}，样本即全量。</li>
     * </ol>
     *
     * @param plannedCount 窗口内计划重跑的总数（创建批次时固化）
     * @param results      逐条结果
     * @throws IllegalArgumentException 结果条数与计划不符时——这是编程错误，
     *                                  不是业务失败，故抛而不是降级为 Rejected
     */
    public static ReplayBatchOutcome decide(int plannedCount, List<ItemResult> results) {
        if (results == null) {
            throw new IllegalArgumentException("结果列表不得为 null");
        }
        if (plannedCount <= 0) {
            throw new IllegalArgumentException("计划条数必须为正：" + plannedCount);
        }
        if (results.size() != plannedCount) {
            // ★不降级为 Rejected：那样会把「worker 有 bug」伪装成「用户数据有问题」，
            //   掩盖真正的缺陷。宁可炸掉让人看见。
            throw new IllegalArgumentException(
                "结果条数 " + results.size() + " 与计划 " + plannedCount
                    + " 不符——批次未完整执行，不得判定结局");
        }

        Map<ReplayFailureKind, Integer> failures = new EnumMap<>(ReplayFailureKind.class);
        for (ItemResult r : results) {
            if (r.isFailure()) {
                failures.merge(r.failureKind(), 1, Integer::sum);
            }
        }

        // ★任一失败即整批拒答。这一步没有「阈值」「容忍度」之类的旋钮——
        //   上一版正是靠一个可调阈值放行了 30/200 的成功子集。
        if (!failures.isEmpty()) {
            return new ReplayBatchOutcome.Rejected(failures);
        }

        int changed = 0;
        int newlyApproved = 0;
        int newlyRejected = 0;
        java.math.BigDecimal delta = null;

        for (ItemResult r : results) {
            if (r.baseApproved() != r.targetApproved()) {
                changed++;
                if (r.targetApproved()) {
                    newlyApproved++;
                } else {
                    newlyRejected++;
                }
                // ★金额基线缺失时保持 null 而非累加 0——
                //   「无法估算」与「估算为零」是两回事，前端必须能区分。
                if (r.valueDelta() != null) {
                    delta = (delta == null) ? r.valueDelta() : delta.add(r.valueDelta());
                }
            }
        }

        return new ReplayBatchOutcome.Completed(
            changed, newlyApproved, newlyRejected, plannedCount, delta);
    }
}
