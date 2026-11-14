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
  /** {@link #expiredReason} 的取值：所依赖事实的签发方已吊销（禁用、删除或过 {@code not_after}）。 */
  public static final String EXPIRED_FACT_ISSUER_REVOKED = "fact_issuer_revoked";

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
  /** 转 EXPIRED 的原因（ADR 0040 §11 D4）：自然过期为 null，签发方吊销为 {@code fact_issuer_revoked}。 */
  @Column(name = "expired_reason", length = 32) public String expiredReason;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
  /** 审批人提交（或服务端按打码视图代记）的输入视图快照哈希（ADR 0042 §12 D6）；未决或输入不可用为 null。 */
  @Column(name = "input_snapshot_hash", length = 64) public String inputSnapshotHash;
  /** 提交的哈希与服务端为该审批人重算的视图一致；未带哈希为 false，未决为 null。 */
  @Column(name = "input_snapshot_matched") public Boolean inputSnapshotMatched;
  /** 审批所据视图是否为该审批人的解除视图；未决为 null。 */
  @Column(name = "input_revealed") public Boolean inputRevealed;
  /** 审批时的输入状态：available / unavailable / purged / integrity_failed / too_large；未决为 null。 */
  @Column(name = "input_status", length = 16) public String inputStatus;

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
