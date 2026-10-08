package io.aster.guard;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
   * 在<b>同一个事务</b>中写入决策行与（可选的）待审批行：要么都落库，要么都不落库，
   * 不会出现「决策显示 PENDING 却没有审批行」的孤儿状态。
   * {@code (tenant_id, idempotency_key)} 或 {@code (tenant_id, evidence_correlation_id)} 唯一索引冲突时抛 {@link GuardIdempotencyRaceException}。
   *
   * @param approvalOrNull REQUIRE_APPROVAL / ESCALATE 时的待审批行，其余为 null
   */
  void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull);

  /** 按租户限定查找审批；跨租户返回 empty。 */
  Optional<GuardApprovalEntity> findApproval(String tenantId, UUID approvalId);

  /** 租户内按状态分页，按创建时间倒序；size 上限 200；PENDING 不含 {@code now} 时已到期的行。 */
  List<GuardApprovalEntity> listApprovals(String tenantId, GuardApprovalStatus status, Instant now, int page, int size);

  /** 跨租户取出 {@code expires_at < now} 的 PENDING 审批，最多 limit 条，按到期时间升序。 */
  List<GuardApprovalEntity> findExpiredPending(Instant now, int limit);

  /**
   * 在<b>一个事务</b>内完成审批状态迁移：以 {@code PESSIMISTIC_WRITE} 锁住审批行，确认它仍是 PENDING，
   * 且（目标不是 EXPIRED 时）在<b>加锁后的当前时刻</b>尚未过期，然后写入 {@code decided} 的
   * status / decidedBy / decidedAt / comment / receiptAuditId，并把关联决策行的 response_json 替换为
   * {@code newResponseJson}。
   *
   * @return false 表示加锁后发现已不可迁移（已被处理或已过期），此时不做任何修改
   */
  boolean completeApproval(GuardApprovalEntity decided, String newResponseJson);
}
