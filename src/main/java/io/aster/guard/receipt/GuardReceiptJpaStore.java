package io.aster.guard.receipt;

import io.aster.guard.api.ConsumeRequest;
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

  /** 「加锁后的 now」的来源：过期判定与 consumed_at 取同一时刻。 */
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
