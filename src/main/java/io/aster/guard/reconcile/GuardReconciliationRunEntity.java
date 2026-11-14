package io.aster.guard.reconcile;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;

/** 某租户一次实际执行的对账（跳过的不写）；与孤儿行、链事件同一事务提交。 */
@RegisterForReflection
@Entity
@Table(name = "guard_reconciliation_runs")
public class GuardReconciliationRunEntity extends PanacheEntityBase {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  /** 账本类扫描窗口 [windowStart, windowEnd)，即 [since, cutoff)。 */
  @Column(name = "window_start", nullable = false) public Instant windowStart;
  @Column(name = "window_end", nullable = false) public Instant windowEnd;
  @Column(name = "started_at", nullable = false) public Instant startedAt;
  @Column(name = "finished_at", nullable = false) public Instant finishedAt;
  @Column(name = "side_effects_checked", nullable = false) public int sideEffectsChecked;
  @Column(name = "new_orphans", nullable = false) public int newOrphans;
  @Column(name = "resolved_orphans", nullable = false) public int resolvedOrphans;
  @Column(name = "open_orphans", nullable = false) public int openOrphans;
  /** {@code {"<kind>": n}}，只列本次新增的类别。 */
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "new_by_kind", columnDefinition = "jsonb", nullable = false)
  public String newByKind;
  /** 本次的 GUARD_RECONCILIATION 事件；无变化不入链时为 null。 */
  @Column(name = "audit_id") public Long auditId;

  public static Optional<GuardReconciliationRunEntity> latest(String tenantId) {
    return find("tenantId = ?1 order by finishedAt desc, id desc", tenantId).firstResultOptional();
  }
}
