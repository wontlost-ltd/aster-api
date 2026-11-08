package io.aster.guard.receipt;

import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.ResultRequest;
import io.aster.policy.entity.AuditLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** {@link GuardReceiptStore} 的 Panache 实现：每次调用一个独立事务，返回的实体已脱管。 */
@ApplicationScoped
public class GuardReceiptJpaStore implements GuardReceiptStore {
  static final String NOT_ALLOWED = "not_allowed";
  static final String KID_REVOKED = "kid_revoked";
  static final String RECEIPT_CONSUMED = "receipt_consumed";
  static final String RECEIPT_EXPIRED = "receipt_expired";
  static final String ACTION_MISMATCH = "action_mismatch";
  static final String NOT_CONSUMED = "not_consumed";
  static final String ATTEMPT_MISMATCH = "attempt_mismatch";
  static final String RESULT_RECORDED = "result_recorded";

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
    Optional<String> rejected = checkResult(row, req);
    if (rejected.isPresent()) {
      return new ResultVerdict.Rejected(rejected.get());
    }
    Instant resultAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    row.resultStatus = req.status();
    row.resultAt = resultAt;
    row.resultAuditId = resultAuditId;
    return new ResultVerdict.Recorded(resultAt, req.status());
  }

  /**
   * 结果回写判定（ADR 0040 §12 D7），服务层预判与加锁复核共用：无行或未消耗 → {@code not_consumed}；
   * attemptId / executorId 与消耗者不符 → {@code attempt_mismatch}；已有结果 → {@code result_recorded}。
   *
   * @param row 收据行，null 表示无行（决策从未签发收据）
   */
  static Optional<String> checkResult(GuardReceiptEntity row, ResultRequest req) {
    if (row == null || row.consumedAt == null) {
      return Optional.of(NOT_CONSUMED);
    }
    if (!row.attemptId.equals(req.attemptId()) || !row.executorId.equals(req.executorId())) {
      return Optional.of(ATTEMPT_MISMATCH);
    }
    if (row.resultStatus != null) {
      return Optional.of(RESULT_RECORDED);
    }
    return Optional.empty();
  }

  /** 行锁保证同一收据只有一次消耗生效：并发者在锁上排队，拿到锁时已能看到赢家提交的 consumed_at。 */
  private ConsumeVerdict lockAndDecide(String tenantId, UUID decisionId, ConsumeRequest req, long attemptAuditId,
                                       Predicate<String> kidRevoked) {
    Optional<GuardReceiptEntity> locked = GuardReceiptEntity
        .<GuardReceiptEntity>find("decisionId = ?1 and tenantId = ?2", decisionId, tenantId)
        .withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional();
    if (locked.isEmpty()) {
      return new ConsumeVerdict.Rejected(NOT_ALLOWED, Map.of());
    }
    GuardReceiptEntity row = locked.get();
    Instant now = clock.instant();
    Optional<ConsumeVerdict.Rejected> rejected = check(row, req, now, kidRevoked);
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

  /**
   * 对已加锁的行按 ①′ → ② → ③ → ④ 判定（① 行不存在由调用方处理）。顺序的理由见 ADR 0040 §12 D5：
   * 撤销与已消耗是状态性问题，先答；过期收据不该再让人关心参数对不对；哈希比对最后但必判。
   */
  static Optional<ConsumeVerdict.Rejected> check(GuardReceiptEntity row, ConsumeRequest req, Instant now,
                                                 Predicate<String> kidRevoked) {
    if (kidRevoked.test(row.kid)) {
      return reject(KID_REVOKED, Map.of("kid", row.kid));
    }
    if (row.consumedAt != null) {
      Map<String, Object> extra = new LinkedHashMap<>();
      extra.put("consumedAt", row.consumedAt.toString());
      extra.put("executorId", row.executorId);
      extra.put("attemptId", row.attemptId);
      return reject(RECEIPT_CONSUMED, extra);
    }
    if (!now.isBefore(row.expiresAt)) {
      return reject(RECEIPT_EXPIRED, Map.of("expiresAt", row.expiresAt.toString()));
    }
    // 定长比较：不因首个不同字节的位置泄露时序信息（入参已校验为 64 位 hex，非空）
    if (!MessageDigest.isEqual(row.actionHash.getBytes(StandardCharsets.US_ASCII),
        req.actionHash().getBytes(StandardCharsets.US_ASCII))) {
      return reject(ACTION_MISMATCH, Map.of());
    }
    return Optional.empty();
  }

  private static Optional<ConsumeVerdict.Rejected> reject(String code, Map<String, Object> extra) {
    return Optional.of(new ConsumeVerdict.Rejected(code, extra));
  }
}
