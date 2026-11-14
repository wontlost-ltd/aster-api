package io.aster.guard;

import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * guard 决策与待审批的持久化边界。抽成接口是为了让决策编排可以脱离数据库单测；
 * 每个方法自带独立事务，调用方不得在外层事务中调用（收据追加同样要求非事务线程）。
 */
public interface GuardDecisionStore {

  Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String idempotencyKey);

  /** 按租户 + 证据相关键取决策（from-evidence 幂等重放）；跨租户返回 empty。 */
  Optional<GuardDecisionEntity> findByEvidenceCorrelationId(String tenantId, String correlationId);

  /** 按租户限定查找决策；跨租户返回 empty。 */
  Optional<GuardDecisionEntity> findDecision(String tenantId, UUID decisionId);

  /** 按租户批量取决策（一次 in 查询，审批列表合入决策字段用）；跨租户的 id 不返回，空输入不访问数据库。 */
  List<GuardDecisionEntity> findDecisions(String tenantId, Collection<UUID> ids);

  Optional<GuardApprovalEntity> findApprovalByDecision(UUID decisionId);

  /**
   * 在<b>同一个事务</b>中写入决策行与（可选的）待审批行、执行收据行：要么都落库，要么都不落库，
   * 不会出现「决策显示 PENDING 却没有审批行」或「响应带收据却没有收据行」的孤儿状态。
   * {@code (tenant_id, idempotency_key)} 或 {@code (tenant_id, evidence_correlation_id)} 唯一索引冲突时抛 {@link GuardIdempotencyRaceException}。
   *
   * @param approvalOrNull REQUIRE_APPROVAL / ESCALATE 时的待审批行，其余为 null
   * @param receiptOrNull  直接 ALLOW 时的执行收据行（ADR 0040 §12 D4），其余为 null
   */
  default void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull,
                            GuardReceiptEntity receiptOrNull) {
    saveDecision(decision, approvalOrNull, receiptOrNull, null);
  }

  /**
   * 同上，并在同一事务内写入待审批决策的输入快照（ADR 0042 §12 D1）：输入行随决策行、审批行一起落库或一起回滚。
   *
   * @param inputOrNull 建了审批行时的输入快照，其余为 null
   */
  void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull, GuardReceiptEntity receiptOrNull,
                    GuardDecisionInputEntity inputOrNull);

  /** 按租户限定查找审批；跨租户返回 empty。 */
  Optional<GuardApprovalEntity> findApproval(String tenantId, UUID approvalId);

  /** 租户内按状态分页，按创建时间倒序；size 上限 200；PENDING 不含 {@code now} 时已到期的行。 */
  List<GuardApprovalEntity> listApprovals(String tenantId, GuardApprovalStatus status, Instant now, int page, int size);

  /** 跨租户取出 {@code expires_at < now} 的 PENDING 审批，最多 limit 条，按到期时间升序。 */
  List<GuardApprovalEntity> findExpiredPending(Instant now, int limit);

  /**
   * 本租户中关联决策的 {@code fact_issuers} 含 {@code issuerKid}（{@code issuer#kid}）的 PENDING 审批，按创建时间升序
   * （ADR 0040 §11 D4 签发方吊销）。查询由 {@code guard_approvals (tenant_id, status)} 索引驱动再连接决策行，
   * 不按租户全扫 guard_decisions；含已到期而调度尚未落库的行。
   */
  List<GuardApprovalEntity> findPendingByFactIssuer(String tenantId, String issuerKid);

  /**
   * 本租户因签发方吊销转 EXPIRED（{@code expired_reason = fact_issuer_revoked}）、且关联决策 {@code fact_issuers} 含
   * {@code issuerKid} 的审批所属决策 id，按迁移时刻升序。吊销事件在写入时以此计算 {@code affectedDecisionIds}，
   * 因此包含审批时就地过期的项，也不受上一轮联动中途失败的影响。查询同样由 {@code guard_approvals (tenant_id, status)} 驱动。
   */
  List<UUID> findRevokedDecisionIds(String tenantId, String issuerKid);

  /**
   * 在<b>一个事务</b>内完成审批状态迁移：以 {@code PESSIMISTIC_WRITE} 锁住审批行，确认它仍是 PENDING，
   * 且（目标不是 EXPIRED 时）在<b>加锁后的当前时刻</b> {@code now} 尚未过期；随后以同一个 {@code now} 调用
   * {@code writeAt} 得到要写入的内容，写入 {@code decided} 的 status / decidedBy / decidedAt / comment / receiptAuditId，
   * 把关联决策行的 response_json 替换为其 {@code responseJson}，并插入其执行收据行（仅 APPROVED 时有）。
   *
   * <p>{@code writeAt} 在加锁后调用，是因为审批转 ALLOW 的执行收据以加锁时刻为 {@code issuedAt}（ADR 0040 §12 D1 (2)），
   * 而收据要同时写进 response_json（D4）。{@code writeAt} 抛出的异常使整个事务回滚、状态不变。
   *
   * @return false 表示加锁后发现已不可迁移（已被处理或已过期），此时不做任何修改，{@code writeAt} 不被调用
   */
  boolean completeApproval(GuardApprovalEntity decided, Function<Instant, ApprovalWrite> writeAt);

  /**
   * 审批迁移要写入的内容。
   *
   * @param responseJson  决策行新的 response_json
   * @param receiptOrNull 审批转 ALLOW 时签发的执行收据行；REJECTED / EXPIRED 为 null
   */
  record ApprovalWrite(String responseJson, GuardReceiptEntity receiptOrNull) {}
}
