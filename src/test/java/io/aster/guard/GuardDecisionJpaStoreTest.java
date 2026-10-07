package io.aster.guard;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** 幂等键唯一索引冲突识别：只有幂等索引冲突才转为 {@link GuardIdempotencyRaceException}。 */
class GuardDecisionJpaStoreTest {

  @Test
  void 幂等索引冲突被识别() {
    SQLException sql = new SQLException("duplicate key", "23505");
    var cve = new ConstraintViolationException("dup", sql, "guard_decisions_idem_uq");
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
