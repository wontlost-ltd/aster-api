package io.aster.guard;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Action Guard 待审批记录（ADR 0040 §4.1）。审批事实本身在 audit_logs 哈希链里。 */
@RegisterForReflection
@Entity
@Table(name = "guard_approvals")
public class GuardApprovalEntity extends PanacheEntityBase {
  @Id @Column(name = "id") public UUID id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  @Column(name = "decision_id", nullable = false) public UUID decisionId;
  @Column(name = "outcome", nullable = false) public String outcome;
  @Column(name = "required_role") public String requiredRole;
  @Column(name = "principal_id", nullable = false) public String principalId;
  @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16)
  public GuardApprovalStatus status = GuardApprovalStatus.PENDING;
  @Column(name = "decided_by") public String decidedBy;
  @Column(name = "decided_at") public Instant decidedAt;
  @Column(name = "comment") public String comment;
  @Column(name = "expires_at", nullable = false) public Instant expiresAt;
  @Column(name = "receipt_audit_id") public Long receiptAuditId;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();

  public static Optional<GuardApprovalEntity> findByDecision(UUID decisionId) {
    return find("decisionId = ?1", decisionId).firstResultOptional();
  }

  public static Optional<GuardApprovalEntity> findByIdAndTenant(UUID id, String tenantId) {
    return find("id = ?1 and tenantId = ?2", id, tenantId).firstResultOptional();
  }

  /**
   * 租户内按状态分页，按创建时间倒序。PENDING 只列出 {@code now} 时仍可审批的行（与 {@link #isExpired} 同一边界），
   * 已到期但调度器尚未落库的不出现在待审批列表里。
   */
  public static List<GuardApprovalEntity> listByTenantAndStatus(String tenantId, GuardApprovalStatus status, Instant now,
                                                                int page, int size) {
    PanacheQuery<GuardApprovalEntity> query = status == GuardApprovalStatus.PENDING
        ? find("tenantId = ?1 and status = ?2 and expiresAt >= ?3 order by createdAt desc", tenantId, status, now)
        : find("tenantId = ?1 and status = ?2 order by createdAt desc", tenantId, status);
    return query.page(page, Math.min(size, 200)).list();
  }

  /** 过期判定在读取时也执行，不依赖调度器及时（ADR 0040 §4.3）。 */
  public boolean isExpired(Instant now) {
    return status == GuardApprovalStatus.PENDING && expiresAt.isBefore(now);
  }
}
