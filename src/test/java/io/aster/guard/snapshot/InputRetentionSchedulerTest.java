package io.aster.guard.snapshot;

import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.GuardDecisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0042 §12 D7：输入快照（终态后 P90D）与评估分类行（P7D）的保留期清理；解除记录不清理。 */
@QuarkusTest
class InputRetentionSchedulerTest {
  private static final String TENANT = "tenant-input-retention-" + UUID.randomUUID().toString().substring(0, 8);
  private static final Duration RETENTION = Duration.ofDays(90);

  @Inject InputRetentionScheduler scheduler;
  @Inject EntityManager em;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardInputRevealEntity.delete("tenantId", TENANT);
      GuardDecisionInputEntity.delete("tenantId", TENANT);
      GuardEvaluationClassificationEntity.delete("tenantId", TENANT);
      GuardApprovalEntity.delete("tenantId", TENANT);
      GuardDecisionEntity.delete("tenantId", TENANT);
    });
  }

  /** 决策 + 审批（给定状态与终态时刻）+ 输入行，返回审批行。 */
  private static GuardApprovalEntity seed(GuardApprovalStatus status, Instant decidedAt, Instant expiresAt) {
    GuardDecisionEntity d = new GuardDecisionEntity();
    d.id = UUID.randomUUID();
    d.tenantId = TENANT;
    d.policyModule = "m";
    d.policyFunction = "f";
    d.principalId = "p";
    d.actionName = "a";
    d.contextHash = "0".repeat(64);
    d.outcome = "REQUIRE_APPROVAL";
    d.responseJson = "{}";
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = TENANT;
    a.decisionId = d.id;
    a.outcome = d.outcome;
    a.principalId = "p";
    a.status = status;
    a.decidedAt = decidedAt;
    a.expiresAt = expiresAt;
    GuardDecisionInputEntity in = new GuardDecisionInputEntity();
    in.decisionId = d.id;
    in.tenantId = TENANT;
    in.actionJson = "{\"context\":{}}";
    in.contextHash = d.contextHash;
    in.marksSource = "none";
    in.inputBinding = "direct";
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
      in.persistAndFlush();
    });
    return a;
  }

  private static boolean hasInput(GuardApprovalEntity a) {
    return QuarkusTransaction.requiringNew().call(() ->
        GuardDecisionInputEntity.findByDecisionAndTenant(a.decisionId, TENANT).isPresent());
  }

  private static Instant ago(long days) {
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  @Test
  void deletesInputsOfTerminalApprovalsPastRetention() {
    GuardApprovalEntity old = seed(GuardApprovalStatus.APPROVED, ago(100), ago(101));
    GuardApprovalEntity recent = seed(GuardApprovalStatus.REJECTED, ago(1), ago(2));
    GuardApprovalEntity expired = seed(GuardApprovalStatus.EXPIRED, null, ago(95));
    scheduler.sweepInputs(Instant.now().minus(RETENTION), InputRetentionScheduler.BATCH);
    assertThat(hasInput(old)).isFalse();
    assertThat(hasInput(expired)).as("EXPIRED 无 decided_at 时取 expires_at").isFalse();
    assertThat(hasInput(recent)).isTrue();
  }

  @Test
  void keepsPendingInputsForever() {
    GuardApprovalEntity pending = seed(GuardApprovalStatus.PENDING, null, Instant.now().plus(1, ChronoUnit.DAYS));
    scheduler.sweepInputs(Instant.now(), InputRetentionScheduler.BATCH);
    assertThat(hasInput(pending)).isTrue();
  }

  @Test
  void p0dDeletesAtTerminal() {
    GuardApprovalEntity justDecided = seed(GuardApprovalStatus.APPROVED, Instant.now().minusSeconds(1), ago(1));
    scheduler.sweepInputs(Instant.now(), InputRetentionScheduler.BATCH);
    assertThat(hasInput(justDecided)).isFalse();
  }

  @Test
  void neverDeletesReveals() {
    GuardApprovalEntity old = seed(GuardApprovalStatus.APPROVED, ago(100), ago(101));
    GuardInputRevealEntity reveal = new GuardInputRevealEntity();
    reveal.approvalId = old.id;
    reveal.revealedBy = "dpo-1";
    reveal.tenantId = TENANT;
    reveal.revealedAt = ago(100);
    reveal.auditId = 1L;
    QuarkusTransaction.requiringNew().run(reveal::persistAndFlush);
    scheduler.sweepInputs(Instant.now().minus(RETENTION), InputRetentionScheduler.BATCH);
    scheduler.sweepClassifications(Instant.now(), InputRetentionScheduler.BATCH);
    assertThat(hasInput(old)).isFalse();
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardInputRevealEntity.count("approvalId", old.id)))
        .isEqualTo(1);
  }

  private void insertClassifications(int count, Instant createdAt) {
    QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
            "INSERT INTO guard_evaluation_classifications (tenant_id, correlation_id, input_hash, classification, "
                + "created_at) SELECT ?1, 'c-' || g || '-' || ?3, NULL, NULL, ?2 FROM generate_series(1, ?4) g")
        .setParameter(1, TENANT).setParameter(2, createdAt).setParameter(3, createdAt.toEpochMilli())
        .setParameter(4, count).executeUpdate());
  }

  private static long classifications() {
    return QuarkusTransaction.requiringNew().call(() -> GuardEvaluationClassificationEntity.count("tenantId", TENANT));
  }

  @Test
  void deletesClassificationsPastRetention() {
    insertClassifications(3, ago(8));
    insertClassifications(2, ago(1));
    scheduler.sweepClassifications(Instant.now().minus(Duration.ofDays(7)), InputRetentionScheduler.BATCH);
    assertThat(classifications()).isEqualTo(2);
  }

  @Test
  void batchCappedAt1000() {
    insertClassifications(1005, ago(30));
    int first = scheduler.sweepClassifications(Instant.now().minus(Duration.ofDays(7)), InputRetentionScheduler.BATCH);
    assertThat(first).isEqualTo(1000);
    while (scheduler.sweepClassifications(Instant.now().minus(Duration.ofDays(7)), InputRetentionScheduler.BATCH) > 0) {
      // 剩余的由后续轮次删除
    }
    assertThat(classifications()).isZero();
  }
}
