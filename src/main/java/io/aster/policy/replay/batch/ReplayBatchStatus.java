package io.aster.policy.replay.batch;

import java.util.EnumSet;
import java.util.Set;

/**
 * What-If 批次状态机（ADR 0034 §3.1）。
 *
 * <p><b>为什么把迁移规则写成代码而不是靠调用方自觉</b>：
 * 上一版 Phase 4 的死因是「部分成功被当成全量」。在异步模型里，
 * 这个错误会以「批次还没跑完就被标成 COMPLETED」的形式重现——
 * 而那一步只要有一处代码写错就会发生。故把合法迁移收敛到这里，
 * 任何非法迁移抛异常，不静默放行。
 *
 * <pre>
 *   PENDING ──→ RUNNING ──┬─→ COMPLETED ──→ EXPIRED
 *      │                  └─→ FAILED    ──→ EXPIRED
 *      └────────────────────→ FAILED
 * </pre>
 *
 * <p>注意 {@code PENDING → FAILED} 是合法的：批次可能在**开跑之前**就失败
 * （如目标版本已被删除、窗口内零条可重跑执行）。
 * 而 {@code PENDING → COMPLETED} **非法**——没跑过就没有全量成功可言。
 */
public enum ReplayBatchStatus {

    /** 已创建，等待 worker 领取。 */
    PENDING,

    /** worker 正在逐条重跑。 */
    RUNNING,

    /**
     * 窗口内**全部**执行重跑成功。这是唯一允许携带数字的状态。
     *
     * <p>★不存在「部分完成」的成功态——这正是 ADR 0034 §1.1 的落地。
     */
    COMPLETED,

    /**
     * 任一条重跑失败，或前置条件不满足。
     *
     * <p>★拒答，不给任何数字——连 partialCount 都不给，
     * 因为它会诱导前端自行计算比率。
     */
    FAILED,

    /** 超过保留期（30 天，ADR 0034 §7.3）：保留元数据，清空聚合结果。 */
    EXPIRED;

    private static final Set<ReplayBatchStatus> TERMINAL =
        EnumSet.of(COMPLETED, FAILED, EXPIRED);

    /** 该状态是否为终态（不可再迁移到非 EXPIRED 状态）。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 该状态是否占用并发额度（ADR 0034 §7.2 的并发上限只数这两个）。 */
    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }

    /** 该状态是否允许携带聚合结果。仅 COMPLETED。 */
    public boolean allowsResult() {
        return this == COMPLETED;
    }

    /**
     * 是否允许从本状态迁移到 {@code next}。
     *
     * <p>规则表见类注释。同状态自迁移一律**不允许**——
     * 幂等应由调用方用条件更新（WHERE status = ?）表达，
     * 而不是让状态机默许，否则「重复标记完成」这类 bug 会被掩盖。
     */
    public boolean canTransitionTo(ReplayBatchStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case PENDING   -> next == RUNNING || next == FAILED;
            case RUNNING   -> next == COMPLETED || next == FAILED;
            case COMPLETED -> next == EXPIRED;
            case FAILED    -> next == EXPIRED;
            case EXPIRED   -> false;
        };
    }

    /**
     * 校验迁移合法性，非法则抛出。
     *
     * @throws IllegalStateException 迁移非法时（含具体状态，便于定位）
     */
    public void requireTransitionTo(ReplayBatchStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                "非法的批次状态迁移：" + this + " → " + next);
        }
    }
}
