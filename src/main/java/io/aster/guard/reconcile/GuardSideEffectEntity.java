package io.aster.guard.reconcile;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 副作用落地方的一条上报（ADR 0054 §10.2）。写入后只有对账会改 {@code reconciledAt / reconcileOutcome}；
 * {@code decisionId} 是上报方声称的凭据，可能不存在或属于他租户。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_side_effects")
public class GuardSideEffectEntity extends PanacheEntityBase {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  @Column(name = "source", nullable = false, length = 64) public String source;
  @Column(name = "effect_id", nullable = false, length = 128) public String effectId;
  @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
  @Column(name = "action_name", nullable = false) public String actionName;
  @Column(name = "resource_type") public String resourceType;
  @Column(name = "resource_id") public String resourceId;
  @Column(name = "decision_id") public UUID decisionId;
  @Column(name = "attempt_id", length = 64) public String attemptId;
  @Column(name = "action_hash", length = 64) public String actionHash;
  @Column(name = "reported_by", nullable = false) public String reportedBy;
  @Column(name = "received_at", nullable = false) public Instant receivedAt;
  @Column(name = "reconciled_at") public Instant reconciledAt;
  /** {@code matched} 或孤儿 kind 字面量；未对账为 null。 */
  @Column(name = "reconcile_outcome", length = 40) public String reconcileOutcome;
}
