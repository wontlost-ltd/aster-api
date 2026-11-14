package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** {@link GuardDecisionStore} 的 Panache 实现：每次调用一个独立事务，返回的实体已脱管。 */
@ApplicationScoped
public class GuardDecisionJpaStore implements GuardDecisionStore {
  /** V6.27.0 迁移中的幂等部分唯一索引名。 */
  static final String IDEMPOTENCY_INDEX = "guard_decisions_idem_uq";
  /** V6.29.0 迁移中的证据相关键部分唯一索引名。 */
  static final String EVIDENCE_INDEX = "guard_decisions_evidence_uq";
  /** PostgreSQL unique_violation。 */
  private static final String UNIQUE_VIOLATION = "23505";

  /** 审批迁移「加锁后的 now」的来源：过期复核与审批转 ALLOW 的收据 issuedAt 取同一时刻。 */
  @Inject
  Clock clock;

  private GuardJson json;

  @Inject
  void setObjectMapper(ObjectMapper objectMapper) {
    this.json = new GuardJson(objectMapper);
  }

  @Override
  public Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String idempotencyKey) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.findByIdempotencyKey(tenantId, idempotencyKey));
  }

  @Override
  public Optional<GuardDecisionEntity> findByEvidenceCorrelationId(String tenantId, String correlationId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.findByEvidenceCorrelationId(tenantId, correlationId));
  }

  @Override
  public Optional<GuardDecisionEntity> findDecision(String tenantId, UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity.findByIdAndTenant(decisionId, tenantId));
  }

  @Override
  public List<GuardDecisionEntity> findDecisions(String tenantId, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity
        .<GuardDecisionEntity>list("tenantId = ?1 and id in ?2", tenantId, List.copyOf(ids)));
  }

  @Override
  public Optional<GuardApprovalEntity> findApprovalByDecision(UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.findByDecision(decisionId));
  }

  @Override
  public void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull,
                           GuardReceiptEntity receiptOrNull, GuardDecisionInputEntity inputOrNull) {
    try {
      QuarkusTransaction.requiringNew().run(() -> persist(decision, approvalOrNull, receiptOrNull, inputOrNull));
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
  public List<GuardApprovalEntity> listApprovals(String tenantId, GuardApprovalStatus status, Instant now, int page,
                                                int size) {
    return QuarkusTransaction.requiringNew().call(
        () -> GuardApprovalEntity.listByTenantAndStatus(tenantId, status, now, page, size));
  }

  @Override
  public List<GuardApprovalEntity> findExpiredPending(Instant now, int limit) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity
        .<GuardApprovalEntity>find("status = ?1 and expiresAt < ?2 order by expiresAt", GuardApprovalStatus.PENDING, now)
        .page(0, limit).list());
  }

  /**
   * 以 {@code guard_approvals_tenant_status_ix (tenant_id, status, created_at DESC)} 取本租户 PENDING，再按主键连接决策行
   * 过滤 jsonb 包含；{@code jsonb_build_array(?2)} 由数据库构造数组，签发方引用不经字符串拼接。
   */
  static final String PENDING_BY_FACT_ISSUER = "SELECT a.* FROM guard_approvals a "
      + "JOIN guard_decisions d ON d.id = a.decision_id AND d.tenant_id = a.tenant_id "
      + "WHERE a.tenant_id = ?1 AND a.status = 'PENDING' "
      + "AND d.fact_issuers @> jsonb_build_array(CAST(?2 AS text)) "
      + "ORDER BY a.created_at";

  @Override
  @SuppressWarnings("unchecked")
  public List<GuardApprovalEntity> findPendingByFactIssuer(String tenantId, String issuerKid) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.getEntityManager()
        .createNativeQuery(PENDING_BY_FACT_ISSUER, GuardApprovalEntity.class)
        .setParameter(1, tenantId).setParameter(2, issuerKid)
        .getResultList());
  }

  static final String REVOKED_DECISION_IDS = "SELECT a.decision_id FROM guard_approvals a "
      + "JOIN guard_decisions d ON d.id = a.decision_id AND d.tenant_id = a.tenant_id "
      + "WHERE a.tenant_id = ?1 AND a.status = 'EXPIRED' AND a.expired_reason = ?3 "
      + "AND d.fact_issuers @> jsonb_build_array(CAST(?2 AS text)) "
      + "ORDER BY a.decided_at, a.created_at";

  @Override
  public List<UUID> findRevokedDecisionIds(String tenantId, String issuerKid) {
    List<?> rows = QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.getEntityManager()
        .createNativeQuery(REVOKED_DECISION_IDS)
        .setParameter(1, tenantId).setParameter(2, issuerKid)
        .setParameter(3, GuardApprovalEntity.EXPIRED_FACT_ISSUER_REVOKED)
        .getResultList());
    return rows.stream().map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString())).toList();
  }

  @Override
  public boolean completeApproval(GuardApprovalEntity decided, Function<Instant, ApprovalWrite> writeAt) {
    return QuarkusTransaction.requiringNew().call(() -> transition(decided, writeAt));
  }

  /** 行锁保证同一审批只有一个迁移生效；审批行、决策行与收据行在同一事务内写入，三者不会不一致。 */
  private boolean transition(GuardApprovalEntity decided, Function<Instant, ApprovalWrite> writeAt) {
    Optional<GuardApprovalEntity> locked = GuardApprovalEntity
        .<GuardApprovalEntity>find("id = ?1 and tenantId = ?2", decided.id, decided.tenantId)
        .withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional();
    Instant now = clock.instant();
    if (locked.isEmpty() || !transitionable(locked.get(), decided, now)) {
      return false;
    }
    GuardApprovalEntity row = locked.get();
    GuardDecisionEntity decision = GuardDecisionEntity.findByIdAndTenant(row.decisionId, row.tenantId).orElseThrow(
        () -> new IllegalStateException("审批 " + row.id + " 关联的决策不存在: " + row.decisionId));
    if (decided.status == GuardApprovalStatus.APPROVED && !factIssuersUsable(decision, now)) {
      return false;
    }
    ApprovalWrite write = writeAt.apply(now);
    row.status = decided.status;
    row.decidedBy = decided.decidedBy;
    row.decidedAt = decided.decidedAt;
    row.comment = decided.comment;
    row.receiptAuditId = decided.receiptAuditId;
    row.expiredReason = decided.expiredReason;
    row.inputSnapshotHash = decided.inputSnapshotHash;
    row.inputSnapshotMatched = decided.inputSnapshotMatched;
    row.inputRevealed = decided.inputRevealed;
    row.inputStatus = decided.inputStatus;
    decision.responseJson = write.responseJson();
    if (write.receiptOrNull() != null) {
      write.receiptOrNull().persist();
    }
    return true;
  }

  /**
   * 决策引用的签发方此刻全部可用：逐个以 {@code PESSIMISTIC_READ}（PostgreSQL {@code FOR SHARE}）按主键读取，
   * 行不存在、已禁用或 {@code now >= notAfter} 即不可用。并发吊销的 UPDATE 要么先提交（这里读到禁用），
   * 要么等本事务提交后才执行（批准先于吊销，吊销扫描时它已不是 PENDING）。无事实的决策不查表。
   */
  private boolean factIssuersUsable(GuardDecisionEntity decision, Instant now) {
    for (String ref : json.readFactIssuers(decision)) {
      int hash = ref.indexOf('#');
      GuardFactIssuerEntity issuer = hash < 0 ? null : GuardFactIssuerEntity.findById(
          new GuardFactIssuerEntity.Key(decision.tenantId, ref.substring(0, hash), ref.substring(hash + 1)),
          LockModeType.PESSIMISTIC_READ);
      if (issuer == null || !issuer.enabled || issuer.notAfter != null && !now.isBefore(issuer.notAfter)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 只有 PENDING 可迁移；人工批准 / 拒绝还要求在<b>加锁后的 now</b> 未过期，过期迁移则不受此限。
   * 不能用校验时刻的 decidedAt 复核：校验通过后行可能已到期，期间读取方已按过期返回 DENY，
   * 这时再提交 APPROVED 就会让同一决策从 DENY 翻成 ALLOW。decidedAt 仍按校验时刻记录。
   */
  static boolean transitionable(GuardApprovalEntity current, GuardApprovalEntity decided, Instant now) {
    if (current.status != GuardApprovalStatus.PENDING) {
      return false;
    }
    return decided.status == GuardApprovalStatus.EXPIRED || !current.isExpired(now);
  }

  /** 先决策后审批与收据（外键），flush 让约束错误在本事务内暴露。 */
  private static void persist(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull,
                              GuardReceiptEntity receiptOrNull, GuardDecisionInputEntity inputOrNull) {
    decision.persistAndFlush();
    if (approvalOrNull != null) {
      approvalOrNull.persistAndFlush();
    }
    if (receiptOrNull != null) {
      receiptOrNull.persistAndFlush();
    }
    if (inputOrNull != null) {
      inputOrNull.persistAndFlush();
    }
  }

  /**
   * 沿异常链识别幂等键或证据相关键唯一索引冲突（模式同 {@code ReplayBatchResource.isUniqueViolation}）：
   * Hibernate 给出约束名时按名字判断（主键等其他唯一约束不算）；拿不到约束名时退回 SQLState 23505。
   */
  static boolean isIdempotencyViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
        String name = cve.getConstraintName();
        return name.contains(IDEMPOTENCY_INDEX) || name.contains(EVIDENCE_INDEX);
      }
      if (c instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
