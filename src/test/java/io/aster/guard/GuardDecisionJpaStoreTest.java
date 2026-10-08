package io.aster.guard;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等键唯一索引冲突识别：只有幂等索引冲突才转为 {@link GuardIdempotencyRaceException}；
 * 以及审批 tx2 的可迁移判定（加锁后以当前时刻复核过期）。
 */
class GuardDecisionJpaStoreTest {
  private static final Instant EXPIRES = Instant.parse("2026-10-07T12:00:00Z");

  private static GuardApprovalEntity row(GuardApprovalStatus status) {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.status = status;
    a.expiresAt = EXPIRES;
    return a;
  }

  /** 校验（tx1）发生在到期前 1ms 的目标迁移。 */
  private static GuardApprovalEntity decided(GuardApprovalStatus target) {
    GuardApprovalEntity d = row(target);
    d.decidedAt = EXPIRES.minusMillis(1);
    return d;
  }

  @Test
  void tx1未过期但加锁时已过期_批准不可迁移() {
    // 校验在 expiresAt-1ms 通过，加锁时已过 expiresAt：读取方此刻已返回 DENY，不得再翻成 ALLOW
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.PENDING),
        decided(GuardApprovalStatus.APPROVED), EXPIRES.plusMillis(1))).isFalse();
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.PENDING),
        decided(GuardApprovalStatus.REJECTED), EXPIRES.plusMillis(1))).isFalse();
  }

  @Test
  void 加锁时仍未过期_批准可迁移() {
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.PENDING),
        decided(GuardApprovalStatus.APPROVED), EXPIRES.minusMillis(1))).isTrue();
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.PENDING),
        decided(GuardApprovalStatus.APPROVED), EXPIRES)).isTrue();
  }

  @Test
  void 过期迁移不受过期复核限制_非PENDING一律不可迁移() {
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.PENDING),
        decided(GuardApprovalStatus.EXPIRED), EXPIRES.plusSeconds(60))).isTrue();
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.APPROVED),
        decided(GuardApprovalStatus.EXPIRED), EXPIRES.plusSeconds(60))).isFalse();
    assertThat(GuardDecisionJpaStore.transitionable(row(GuardApprovalStatus.EXPIRED),
        decided(GuardApprovalStatus.APPROVED), EXPIRES.minusSeconds(60))).isFalse();
  }

  @Test
  void 幂等索引冲突被识别() {
    SQLException sql = new SQLException("duplicate key", "23505");
    var cve = new ConstraintViolationException("dup", sql, "guard_decisions_idem_uq");
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new RuntimeException(cve))).isTrue();
  }

  @Test
  void 证据相关键索引冲突被识别() {
    SQLException sql = new SQLException("duplicate key", "23505");
    var cve = new ConstraintViolationException("dup", sql, "guard_decisions_evidence_uq");
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new RuntimeException(cve))).isTrue();
  }

  @Test
  void 约束名缺失时按SQLState识别() {
    SQLException sql = new SQLException("duplicate key", "23505");
    var cve = new ConstraintViolationException("dup", sql, null);
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new RuntimeException(cve))).isTrue();
  }

  @Test
  void 其他约束或错误不识别为幂等冲突() {
    SQLException sql = new SQLException("duplicate key", "23505");
    var pkey = new ConstraintViolationException("dup", sql, "guard_decisions_pkey");
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new RuntimeException(pkey))).isFalse();
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new SQLException("check", "23514"))).isFalse();
    assertThat(GuardDecisionJpaStore.isIdempotencyViolation(new IllegalStateException("x"))).isFalse();
  }
}
