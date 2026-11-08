package io.aster.guard.receipt;

import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.receipt.GuardReceiptStore.ConsumeVerdict;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 收据行的 consume / 结果回写判定规则（ADR 0040 §12 D5 / D7）与 409 错误码。纯函数、无持久化依赖：
 * 服务层的预判与存储层加锁后的复核共用同一份规则，服务层因此不必静态依赖 JPA 实现类。
 */
final class GuardReceiptRules {
  static final String NOT_ALLOWED = "not_allowed";
  static final String KID_REVOKED = "kid_revoked";
  static final String RECEIPT_CONSUMED = "receipt_consumed";
  static final String RECEIPT_EXPIRED = "receipt_expired";
  static final String ACTION_MISMATCH = "action_mismatch";
  static final String NOT_CONSUMED = "not_consumed";
  static final String ATTEMPT_MISMATCH = "attempt_mismatch";
  static final String RESULT_RECORDED = "result_recorded";

  private GuardReceiptRules() {
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
