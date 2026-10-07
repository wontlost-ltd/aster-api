package io.aster.guard;

import java.util.Optional;
import java.util.UUID;

/**
 * guard 决策与待审批的持久化边界。抽成接口是为了让决策编排可以脱离数据库单测；
 * 每个方法自带独立事务，调用方不得在外层事务中调用（收据追加同样要求非事务线程）。
 */
public interface GuardDecisionStore {

  Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String idempotencyKey);

  /** 按租户限定查找决策；跨租户返回 empty。 */
  Optional<GuardDecisionEntity> findDecision(String tenantId, UUID decisionId);

  Optional<GuardApprovalEntity> findApprovalByDecision(UUID decisionId);

  /**
   * 在<b>同一个事务</b>中写入决策行与（可选的）待审批行：要么都落库，要么都不落库，
   * 不会出现「决策显示 PENDING 却没有审批行」的孤儿状态。
   * {@code (tenant_id, idempotency_key)} 唯一索引冲突时抛 {@link GuardIdempotencyRaceException}。
   *
   * @param approvalOrNull REQUIRE_APPROVAL / ESCALATE 时的待审批行，其余为 null
   */
  void saveDecision(GuardDecisionEntity decision, GuardApprovalEntity approvalOrNull);
}
