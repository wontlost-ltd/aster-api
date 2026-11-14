package io.aster.guard.reconcile;

import io.aster.guard.reconcile.SideEffectReport.Effect;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Clock;
import java.time.Instant;

/**
 * 副作用上报的批量幂等写入（ADR 0054 §10.2）：整批一个事务，逐条 {@code INSERT … ON CONFLICT (tenant_id, source,
 * effect_id) DO NOTHING}，按实际插入行数计 accepted，其余（含同批内重复）计 duplicates，原行不改。
 * 调用方须已校验（{@link SideEffectReport#validate}），且不得在外层事务内调用。可空参数一律显式 CAST：
 * Hibernate 以未知类型绑定 null，PostgreSQL 无法推断其列类型。
 */
@ApplicationScoped
public class SideEffectIngestService {
  static final String INSERT = "INSERT INTO guard_side_effects (tenant_id, source, effect_id, occurred_at, action_name, "
      + "resource_type, resource_id, decision_id, attempt_id, action_hash, reported_by, received_at) "
      + "VALUES (:t, :source, :effectId, :occurredAt, :actionName, CAST(:resourceType AS varchar), "
      + "CAST(:resourceId AS varchar), CAST(CAST(:decisionId AS varchar) AS uuid), CAST(:attemptId AS varchar), "
      + "CAST(:actionHash AS varchar), :reportedBy, :receivedAt) "
      + "ON CONFLICT (tenant_id, source, effect_id) DO NOTHING";

  public record IngestResult(int accepted, int duplicates) {}

  @Inject
  EntityManager em;

  @Inject
  Clock clock;

  public IngestResult ingest(String tenantId, String reportedBy, SideEffectReport report) {
    Instant receivedAt = clock.instant();
    int accepted = QuarkusTransaction.requiringNew().call(() -> {
      int n = 0;
      for (Effect e : report.effects()) {
        n += insert(tenantId, reportedBy, receivedAt, e);
      }
      return n;
    });
    return new IngestResult(accepted, report.effects().size() - accepted);
  }

  private int insert(String tenantId, String reportedBy, Instant receivedAt, Effect e) {
    return em.createNativeQuery(INSERT)
        .setParameter("t", tenantId)
        .setParameter("source", e.source())
        .setParameter("effectId", e.effectId())
        .setParameter("occurredAt", e.occurredInstant())
        .setParameter("actionName", e.action().name())
        .setParameter("resourceType", e.resource() == null ? null : e.resource().type())
        .setParameter("resourceId", e.resource() == null ? null : e.resource().id())
        .setParameter("decisionId", e.decisionId() == null ? null : e.decisionUuid().toString())
        .setParameter("attemptId", e.attemptId())
        .setParameter("actionHash", e.actionHash())
        .setParameter("reportedBy", reportedBy)
        .setParameter("receivedAt", receivedAt)
        .executeUpdate();
  }
}
