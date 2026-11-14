package io.aster.guard.snapshot;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 审批输入原值解除记录（ADR 0042 §12 D5）：每人每审批一行，对应链上恰好一条 {@code ACTION_GUARD_INPUT_REVEAL}
 * 事件（{@link #auditId}）。只含审批人 id，不含 PII，不随输入行清理。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_input_reveals")
@IdClass(GuardInputRevealEntity.Key.class)
public class GuardInputRevealEntity extends PanacheEntityBase {
  @Id @Column(name = "approval_id") public UUID approvalId;
  @Id @Column(name = "revealed_by") public String revealedBy;
  @Column(name = "tenant_id", nullable = false, length = 64) public String tenantId;
  @Column(name = "revealed_at", nullable = false) public Instant revealedAt;
  @Column(name = "audit_id", nullable = false) public Long auditId;

  /** 复合主键。 */
  public record Key(UUID approvalId, String revealedBy) implements Serializable {
    /** JPA 要求 {@link IdClass} 有无参构造。 */
    public Key() {
      this(null, null);
    }
  }
}
