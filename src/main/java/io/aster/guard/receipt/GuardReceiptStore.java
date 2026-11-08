package io.aster.guard.receipt;

import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.ResultRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 执行收据行（{@code guard_receipts}）的 consume 与结果回写持久化边界。抽成接口是为了让 consume 编排可以脱离数据库单测；
 * 每个方法自带独立事务，调用方不得在外层事务中调用（尝试事件追加同样要求非事务线程）。
 */
public interface GuardReceiptStore {

  /** 不加锁读取收据行（构造尝试事件用）；跨租户或无行返回 empty。 */
  Optional<GuardReceiptEntity> findReceipt(String tenantId, UUID decisionId);

  /**
   * 原子一次性消耗（ADR 0040 §12 D5）：在 {@code requiringNew} 事务内以 {@code PESSIMISTIC_WRITE} 锁住收据行，
   * 取<b>加锁后的</b>当前时刻 now，按固定顺序判定：
   * ① 行不存在 → {@code not_allowed}；①′ 行的 kid 已紧急撤销 → {@code kid_revoked}（{@code extra.kid}）；
   * ② 已消耗 → {@code receipt_consumed}（{@code extra} 为原消耗者 consumedAt / executorId / attemptId）；
   * ③ {@code now >= expiresAt} → {@code receipt_expired}（{@code extra.expiresAt}）；
   * ④ actionHash 不等 → {@code action_mismatch}（不回显哈希）。
   * 全部通过才写 {@code consumed_at / executor_id / attempt_id / consume_audit_id}；任一拒绝不写任何列。
   *
   * @param attemptAuditId 已入链的尝试事件 id，成功时成为权威消耗指针 {@code consume_audit_id}
   * @param kidRevoked     kid 是否被紧急撤销；已从配置删除（退役）的 kid 不算撤销
   */
  ConsumeVerdict consume(String tenantId, UUID decisionId, ConsumeRequest req, long attemptAuditId,
                         Predicate<String> kidRevoked);

  /** 按 id 取本租户审计事件的 current_hash（结果事件以它链接权威尝试事件）；跨租户或不存在返回 empty。 */
  Optional<String> auditCurrentHash(String tenantId, long auditId);

  /**
   * 结果回写的加锁复核与写入（ADR 0040 §12 D7）：在 {@code requiringNew} 事务内以 {@code PESSIMISTIC_WRITE}
   * 锁住收据行，按 {@code not_consumed → attempt_mismatch → result_recorded} 复核；通过才写
   * {@code result_status / result_at / result_audit_id}，result_at 取加锁后的当前时刻。
   *
   * @param resultAuditId 已入链的结果事件 id；复核失败时该事件不被行引用，按「未生效」读
   */
  ResultVerdict recordResult(String tenantId, UUID decisionId, ResultRequest req, long resultAuditId);

  /** 加锁判定的结果。 */
  sealed interface ConsumeVerdict {

    /** 消耗成功；{@code row} 为已提交的收据行（脱管）。 */
    record Consumed(Instant consumedAt, GuardReceiptEntity row) implements ConsumeVerdict {}

    /**
     * 409 拒绝。{@code code} 即响应 {@code error}，{@code extra} 为响应体附加字段（按输出顺序）。
     */
    record Rejected(String code, Map<String, Object> extra) implements ConsumeVerdict {}
  }

  /** 结果回写判定。 */
  sealed interface ResultVerdict {

    /** 已记录；{@code resultAt} 与行的 result_at 相同。 */
    record Recorded(Instant resultAt, String status) implements ResultVerdict {}

    /** 409 拒绝；{@code code} 即响应 {@code error}，无附加字段。 */
    record Rejected(String code) implements ResultVerdict {}
  }
}
