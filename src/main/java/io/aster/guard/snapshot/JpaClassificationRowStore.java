package io.aster.guard.snapshot;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Optional;

/** {@link ClassificationRowStore} 的 JPA 实现：每次调用自开 {@code requiringNew} 事务。 */
@ApplicationScoped
public class JpaClassificationRowStore implements ClassificationRowStore {
  static final String INSERT_SQL = "INSERT INTO guard_evaluation_classifications "
      + "(tenant_id, correlation_id, input_hash, classification, created_at) "
      + "VALUES (?1, ?2, ?3, CAST(?4 AS jsonb), ?5) ON CONFLICT (tenant_id, correlation_id) DO NOTHING";

  @Inject EntityManager em;

  @Override
  public boolean insertIfAbsent(GuardEvaluationClassificationEntity row) {
    return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(INSERT_SQL)
        .setParameter(1, row.tenantId)
        .setParameter(2, row.correlationId)
        .setParameter(3, row.inputHash)
        .setParameter(4, row.classification)
        .setParameter(5, row.createdAt)
        .executeUpdate() > 0);
  }

  @Override
  public Optional<GuardEvaluationClassificationEntity> find(String tenantId, String correlationId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> GuardEvaluationClassificationEntity.findByKey(tenantId, correlationId));
  }
}
