package io.aster.guard.snapshot;

import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * 审批输入快照与评估分类行的保留期清理（ADR 0042 §12 D7），与 {@code GuardApprovalExpiryScheduler} 同构。
 *
 * <ul>
 *   <li>{@code guard_decision_inputs}：审批到达终态（非 PENDING）后过 {@code aster.guard.input-retention}（缺省 P90D）删除；
 *       终态时刻取 {@code decided_at}，缺失时取 {@code expires_at}。PENDING 永不删除；删除后视图为 {@code purged}；</li>
 *   <li>{@code guard_evaluation_classifications}：{@code created_at} 起过 {@code aster.guard.classification-retention}
 *       （缺省 P7D）无条件删除；</li>
 *   <li>{@code guard_input_reveals} 不清理：它不含 PII，是访问审计的一部分。</li>
 * </ul>
 * 每轮每表至多删 {@link #BATCH} 行（{@code DELETE … WHERE ctid IN (SELECT … LIMIT)}），剩余的留给下一轮。
 */
@ApplicationScoped
public class InputRetentionScheduler {
  static final int BATCH = 1000;
  static final String DELETE_INPUTS = "DELETE FROM guard_decision_inputs WHERE ctid IN ("
      + "SELECT i.ctid FROM guard_decision_inputs i JOIN guard_approvals a "
      + "ON a.decision_id = i.decision_id AND a.tenant_id = i.tenant_id "
      + "WHERE a.status <> 'PENDING' AND COALESCE(a.decided_at, a.expires_at) <= ?1 LIMIT ?2)";
  static final String DELETE_CLASSIFICATIONS = "DELETE FROM guard_evaluation_classifications WHERE ctid IN ("
      + "SELECT ctid FROM guard_evaluation_classifications WHERE created_at <= ?1 LIMIT ?2)";

  @Inject EntityManager em;

  @ConfigProperty(name = "aster.guard.input-retention", defaultValue = "P90D")
  Duration inputRetention;

  @ConfigProperty(name = "aster.guard.classification-retention", defaultValue = "P7D")
  Duration classificationRetention;

  @Scheduled(
      every = "${aster.guard.input-retention-sweep:PT1H}",
      identity = "guard-input-retention",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  void sweep() {
    Instant now = Instant.now();
    int inputs = sweepInputs(now.minus(inputRetention), BATCH);
    int classifications = sweepClassifications(now.minus(classificationRetention), BATCH);
    if (inputs + classifications > 0) {
      Log.infof("Action Guard 输入保留期清理：输入快照 %d 行，评估分类 %d 行", inputs, classifications);
    }
  }

  /** 删除终态时刻不晚于 {@code cutoff} 的审批的输入行，至多 {@code limit} 行。 */
  int sweepInputs(Instant cutoff, int limit) {
    return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(DELETE_INPUTS)
        .setParameter(1, cutoff).setParameter(2, limit).executeUpdate());
  }

  /** 删除创建时刻不晚于 {@code cutoff} 的评估分类行，至多 {@code limit} 行。 */
  int sweepClassifications(Instant cutoff, int limit) {
    return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(DELETE_CLASSIFICATIONS)
        .setParameter(1, cutoff).setParameter(2, limit).executeUpdate());
  }
}
