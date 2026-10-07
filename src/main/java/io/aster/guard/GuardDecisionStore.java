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

  /** 写入决策行；{@code (tenant_id, idempotency_key)} 唯一索引冲突时抛 {@link GuardIdempotencyRaceException}。 */
  void saveDecision(GuardDecisionEntity decision);

  /** 写入待审批行；必须在对应决策行写入之后调用（外键）。 */
  void saveApproval(GuardApprovalEntity approval);
}
