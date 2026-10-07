package io.aster.guard;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link GuardDecisionStore} 的 Panache 实现：每次调用一个独立事务，返回的实体已脱管。 */
@ApplicationScoped
public class GuardDecisionJpaStore implements GuardDecisionStore {
  /** V6.27.0 迁移中的幂等部分唯一索引名。 */
  static final String IDEMPOTENCY_INDEX = "guard_decisions_idem_uq";
  /** PostgreSQL unique_violation。 */
  private static final String UNIQUE_VIOLATION = "23505";

  @Override
  public Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String idempotencyKey) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.findByIdempotencyKey(tenantId, idempotencyKey));
  }

  @Override
  public Optional<GuardDecisionEntity> findDecision(String tenantId, UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.findByIdAndTenant(decisionId, tenantId));
  }

  @Override
  public Optional<GuardApprovalEntity> findApprovalByDecision(UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.findByDecision(decisionId));
  }

  @Override
  public void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull) {
    try {
      QuarkusTransaction.requiringNew().run(() -> persist(decision, approvalOrNull));
    } catch (RuntimeException e) {
      if (isIdempotencyViolation(e)) {
        throw new GuardIdempotencyRaceException(e);
      }
      throw e;
    }
  }

  @Override
  public Optional<GuardApprovalEntity> findApproval(String tenantId, UUID approvalId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.findByIdAndTenant(approvalId, tenantId));
  }

  @Override
  public List<GuardApprovalEntity> listApprovals(String tenantId, GuardApprovalStatus status, int page, int size) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.listByTenantAndStatus(tenantId, status, page, size));
  }

  @Override
  public List<GuardApprovalEntity> findExpiredPending(Instant now, int limit) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity
        .<GuardApprovalEntity>find("status = ?1 and expiresAt < ?2 order by expiresAt", GuardApprovalStatus.PENDING, now)
        .page(0, limit).list());
  }

  @Override
  public boolean completeApproval(GuardApprovalEntity decided, String newResponseJson) {
    return QuarkusTransaction.requiringNew().call(() -> transition(decided, newResponseJson));
  }

  /** 行锁保证同一审批只有一个迁移生效；审批行与决策行在同一事务内更新，二者不会不一致。 */
  private static boolean transition(GuardApprovalEntity decided, String newResponseJson) {
    Optional<GuardApprovalEntity> locked = GuardApprovalEntity
        .<GuardApprovalEntity>find("id = ?1 and tenantId = ?2", decided.id, decided.tenantId)
        .withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional();
    if (locked.isEmpty() || !transitionable(locked.get(), decided)) {
      return false;
    }
    GuardApprovalEntity row = locked.get();
    row.status = decided.status;
    row.decidedBy = decided.decidedBy;
    row.decidedAt = decided.decidedAt;
    row.comment = decided.comment;
    row.receiptAuditId = decided.receiptAuditId;
    GuardDecisionEntity decision = GuardDecisionEntity.findByIdAndTenant(row.decisionId, row.tenantId).orElseThrow(
        () -> new IllegalStateException("审批 " + row.id + " 关联的决策不存在: " + row.decisionId));
    decision.responseJson = newResponseJson;
    return true;
  }

  /** 只有 PENDING 可迁移；人工批准 / 拒绝还要求在决定时刻未过期，过期迁移则不受此限。 */
  private static boolean transitionable(GuardApprovalEntity current, GuardApprovalEntity decided) {
    if (current.status != GuardApprovalStatus.PENDING) {
      return false;
    }
    return decided.status == GuardApprovalStatus.EXPIRED || !current.isExpired(decided.decidedAt);
  }

  /** 先决策后审批（外键），flush 让约束错误在本事务内暴露。 */
  private static void persist(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull) {
    decision.persistAndFlush();
    if (approvalOrNull != null) {
      approvalOrNull.persistAndFlush();
    }
  }

  /**
   * 沿异常链识别幂等索引冲突（模式同 {@code ReplayBatchResource.isUniqueViolation}）：
   * Hibernate 给出约束名时按名字判断（主键等其他唯一约束不算）；拿不到约束名时退回 SQLState 23505。
   */
  static boolean isIdempotencyViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
        return cve.getConstraintName().contains(IDEMPOTENCY_INDEX);
      }
      if (c instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
