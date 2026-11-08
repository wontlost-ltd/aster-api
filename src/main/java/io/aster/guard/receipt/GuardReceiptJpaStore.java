package io.aster.guard.receipt;

import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.ResultRequest;
import io.aster.policy.entity.AuditLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** {@link GuardReceiptStore} 的 Panache 实现：每次调用一个独立事务，返回的实体已脱管。 */
@ApplicationScoped
public class GuardReceiptJpaStore implements GuardReceiptStore {
  /** 「加锁后的 now」的来源：过期判定与 consumed_at、result_at 都取加锁之后的时刻。 */
  @Inject
  Clock clock;

  @Override
  public Optional<GuardReceiptEntity> findReceipt(String tenantId, UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity
        .<GuardReceiptEntity>find("decisionId = ?1 and tenantId = ?2", decisionId, tenantId).firstResultOptional());
  }

  @Override
  public ConsumeVerdict consume(String tenantId, UUID decisionId, ConsumeRequest req, long attemptAuditId,
                                Predicate<String> kidRevoked) {
    return QuarkusTransaction.requiringNew().call(() -> lockAndDecide(tenantId, decisionId, req, attemptAuditId,
        kidRevoked));
  }

  @Override
  public Optional<String> auditCurrentHash(String tenantId, long auditId) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog
        .<AuditLog>find("id = ?1 and tenantId = ?2", auditId, tenantId).firstResultOptional()
        .map(log -> log.currentHash));
  }

  @Override
  public ResultVerdict recordResult(String tenantId, UUID decisionId, ResultRequest req, long resultAuditId) {
    return QuarkusTransaction.requiringNew().call(() -> lockAndRecord(tenantId, decisionId, req, resultAuditId));
  }

  /** 与 consume 同一把行锁：并发回写排队，后到者看到赢家已提交的 result_status。 */
  private ResultVerdict lockAndRecord(String tenantId, UUID decisionId, ResultRequest req, long resultAuditId) {
    GuardReceiptEntity row = GuardReceiptEntity
        .<GuardReceiptEntity>find("decisionId = ?1 and tenantId = ?2", decisionId, tenantId)
        .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    Optional<String> rejected = GuardReceiptRules.checkResult(row, req);
    if (rejected.isPresent()) {
      return new ResultVerdict.Rejected(rejected.get());
    }
    Instant resultAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    row.resultStatus = req.status();
    row.resultAt = resultAt;
    row.resultAuditId = resultAuditId;
    return new ResultVerdict.Recorded(resultAt, req.status());
  }

  /** 行锁保证同一收据只有一次消耗生效：并发者在锁上排队，拿到锁时已能看到赢家提交的 consumed_at。 */
  private ConsumeVerdict lockAndDecide(String tenantId, UUID decisionId, ConsumeRequest req, long attemptAuditId,
                                       Predicate<String> kidRevoked) {
    Optional<GuardReceiptEntity> locked = GuardReceiptEntity
        .<GuardReceiptEntity>find("decisionId = ?1 and tenantId = ?2", decisionId, tenantId)
        .withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional();
    if (locked.isEmpty()) {
      return new ConsumeVerdict.Rejected(GuardReceiptRules.NOT_ALLOWED, Map.of());
    }
    GuardReceiptEntity row = locked.get();
    Instant now = clock.instant();
    Optional<ConsumeVerdict.Rejected> rejected = GuardReceiptRules.check(row, req, now, kidRevoked);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    // 截到毫秒：与 issuedAt 同精度，响应中的 consumedAt 与数据库列逐字相同
    Instant consumedAt = now.truncatedTo(ChronoUnit.MILLIS);
    row.consumedAt = consumedAt;
    row.executorId = req.executorId();
    row.attemptId = req.attemptId();
    row.consumeAuditId = attemptAuditId;
    return new ConsumeVerdict.Consumed(consumedAt, row);
  }
}
