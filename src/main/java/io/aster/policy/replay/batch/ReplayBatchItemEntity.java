package io.aster.policy.replay.batch;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 批次的**冻结总体**：窗口内一条待重跑执行的标识与基线（ADR 0034 §10.2）。
 *
 * <p><b>为什么要冻结成员而不只是数量</b>：上一版只把 {@code window.size()} 写进
 * {@code plannedCount}，回收重跑时重新拉窗口——上游 executions 若已变化
 * （迟到写入、删除、replayable 状态变更），即使 planned 重新派生也
 * <b>不是同一个总体</b>。而「样本即某个总体的全量」正是 §1.1 的前提；
 * 总体本身会漂移，前提就不成立。
 *
 * <p>★<b>只存输入标识与基线，不存 targetDecision</b>——
 * 与 §3.1 的边界一致：那条禁的是决策结果（PII 面 + 失效语义），
 * 这里存的是「跑哪些、原来什么结论」，是总体的定义本身。
 *
 * <p>{@code baseApproved} 也在冻结时一并存下：上游的 decision 可能因数据订正
 * 而变，那会让「变化了多少条」这个结论随时间漂移。基线必须与总体同时冻结。
 */
@Entity
@Table(name = "replay_batch_item")
@IdClass(ReplayBatchItemEntity.PK.class)
public class ReplayBatchItemEntity extends PanacheEntityBase {

    @Id
    @Column(name = "batch_id", nullable = false)
    public UUID batchId;

    @Id
    @Column(name = "execution_id", nullable = false, length = 255)
    public String executionId;

    @Column(name = "base_approved", nullable = false)
    public boolean baseApproved;

    /**
     * 本条是否重跑成功。<b>{@code null} = 尚未重跑</b>——分段执行的天然中间态。
     *
     * <p>★分段执行拆掉了「整批一个事务」，而那原本是 §1.1 的实现基础
     * （要么全成功、要么全拒答）。改由本列保：每条跑完立即落标记，
     * 崩溃只丢当前段；DB 触发器保证「COMPLETED ⇒ 不存在非成功条目」。
     */
    @Column(name = "success")
    public Boolean success;

    /** 仅 {@code success = false} 时非空，取值为 {@link ReplayFailureKind} 的名字。 */
    @Column(name = "failure_kind", length = 64)
    public String failureKind;

    /**
     * 重跑后目标版本是否「通过」。
     *
     * <p>★<b>只存布尔判定，不存 decision 内容</b>——与 §3.1 的边界一致：
     * 那条要避免的是决策结果（PII 面 + 失效语义），这里是一个不含 PII 的判定位。
     */
    @Column(name = "target_approved")
    public Boolean targetApproved;

    public ReplayBatchItemEntity() {
    }

    public ReplayBatchItemEntity(UUID batchId, String executionId, boolean baseApproved) {
        this.batchId = batchId;
        this.executionId = executionId;
        this.baseApproved = baseApproved;
    }

    /** 复合主键：同一批次内 executionId 唯一（天然去重）。 */
    public static class PK implements Serializable {
        public UUID batchId;
        public String executionId;

        public PK() {
        }

        public PK(UUID batchId, String executionId) {
            this.batchId = batchId;
            this.executionId = executionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PK other)) {
                return false;
            }
            return Objects.equals(batchId, other.batchId)
                && Objects.equals(executionId, other.executionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(batchId, executionId);
        }
    }
}
