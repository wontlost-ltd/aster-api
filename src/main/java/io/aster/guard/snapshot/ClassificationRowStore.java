package io.aster.guard.snapshot;

import java.util.Optional;

/** 评估分类行的持久化边界（ADR 0042 §12 D1）：生产为 {@link JpaClassificationRowStore}，单测可替换。 */
public interface ClassificationRowStore {
  /**
   * 在独立事务中 {@code INSERT … ON CONFLICT DO NOTHING}：先写者为准，从不覆盖。
   *
   * @return true 表示本次写入成功；false 表示同键已有行（冲突）
   */
  boolean insertIfAbsent(GuardEvaluationClassificationEntity row);

  Optional<GuardEvaluationClassificationEntity> find(String tenantId, String correlationId);
}
