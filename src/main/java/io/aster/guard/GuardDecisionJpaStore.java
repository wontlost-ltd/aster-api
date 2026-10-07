package io.aster.guard;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
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
