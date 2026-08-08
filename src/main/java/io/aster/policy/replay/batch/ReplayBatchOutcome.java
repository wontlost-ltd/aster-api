package io.aster.policy.replay.batch;

import java.util.Collections;
import java.util.Map;

/**
 * 批次跑完后的结局：**要么全量成功带数字，要么拒答带失败分布**。
 *
 * <p>这是 ADR 0034 §1.1 在类型层面的表达——用一个密封的二选一，
 * 而不是一个「带可空数字字段 + 可空错误字段」的结构体。
 * 后者允许「既有数字又有错误」这种在本设计里**不该存在**的状态，
 * 而上一版 Phase 4 正是死在那种模糊地带（部分成功也出数字）。
 */
public sealed interface ReplayBatchOutcome {

    /**
     * 全量成功。<b>只有这一种结局携带数字。</b>
     *
     * @param changed             决策发生变化的执行条数
     * @param newlyApproved       从拒绝变为通过
     * @param newlyRejected       从通过变为拒绝
     * @param totalSampled        样本总数（= plannedCount，全量）
     * @param estimatedValueDelta 业务价值变化估算；无金额基线时为 null
     *                            （★不是 0——「无法估算」与「估算为零」是两回事）
     */
    record Completed(
        int changed,
        int newlyApproved,
        int newlyRejected,
        int totalSampled,
        java.math.BigDecimal estimatedValueDelta
    ) implements ReplayBatchOutcome {

        public Completed {
            if (totalSampled <= 0) {
                throw new IllegalArgumentException("全量成功的批次样本数必须为正");
            }
            if (changed < 0 || newlyApproved < 0 || newlyRejected < 0) {
                throw new IllegalArgumentException("计数不得为负");
            }
            if (changed > totalSampled) {
                throw new IllegalArgumentException(
                    "变化条数 " + changed + " 不得超过样本总数 " + totalSampled);
            }
            if (newlyApproved + newlyRejected > changed) {
                throw new IllegalArgumentException(
                    "通过/拒绝翻转之和 " + (newlyApproved + newlyRejected)
                        + " 不得超过变化总数 " + changed);
            }
        }
    }

    /**
     * 拒答。<b>不携带任何会被读成结论的数字。</b>
     *
     * <p>★这里刻意**没有** partialCount / successCount 之类的字段：
     * 给了前端就会自行计算比率，那正是 §1.1 要防的。
     * 唯一的数字是失败原因分布——它描述的是「哪里出了问题」，
     * 不是「业务影响是多少」。
     *
     * @param failuresByKind 各类失败的条数分布
     */
    record Rejected(Map<ReplayFailureKind, Integer> failuresByKind)
        implements ReplayBatchOutcome {

        public Rejected {
            if (failuresByKind == null || failuresByKind.isEmpty()) {
                throw new IllegalArgumentException("拒答必须说明失败原因，不能只说「失败了」");
            }
            failuresByKind = Collections.unmodifiableMap(new java.util.EnumMap<>(failuresByKind));
        }

        /** 失败总条数。仅用于日志与运维，**不得**回传给前端做比率计算。 */
        public int totalFailures() {
            return failuresByKind.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** 是否全部失败都属于「可重试」类——决定 UI 是否提示重试。 */
        public boolean allRetryable() {
            return failuresByKind.keySet().stream().allMatch(ReplayFailureKind::isRetryable);
        }
    }
}
