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
import java.util.UUID;

/**
 * 孤儿动作（ADR 0054 §10.3）：同一事实 {@code (tenantId, kind, subjectKey)} 永远一行。检测由
 * {@link GuardOrphanDetector} 以 {@code INSERT … ON CONFLICT DO NOTHING} 写入，此后只有自动解除与管理员确认会改状态。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_orphan_actions")
public class GuardOrphanActionEntity extends PanacheEntityBase {
  public static final String OPEN = "OPEN";
  public static final String RESOLVED = "RESOLVED";
  public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
  public static final String RESOLUTION_LATE_RESULT = "late_result";
  public static final String RESOLUTION_AGED_OUT = "aged_out";
  public static final String RESOLUTION_ACKNOWLEDGED = "acknowledged";

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  /** {@link OrphanKind#wire()}。 */
  @Column(name = "kind", nullable = false, length = 40) public String kind;
  @Column(name = "severity", nullable = false, length = 16) public String severity;
  /** 决策类为 decisionId 文本，上报类为 {@code se:<上报行 id>}。 */
  @Column(name = "subject_key", nullable = false, length = 80) public String subjectKey;
  @Column(name = "decision_id") public UUID decisionId;
  /** 上报行被保留期清理后为 null（{@code ON DELETE SET NULL}）。 */
  @Column(name = "side_effect_id") public Long sideEffectId;
  @Column(name = "executor_label", nullable = false, length = 40) public String executorLabel;
  @Column(name = "executor_id") public String executorId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "detail", columnDefinition = "jsonb", nullable = false) public String detail;
  @Column(name = "status", nullable = false, length = 16) public String status = OPEN;
  @Column(name = "first_run_id", nullable = false) public long firstRunId;
  @Column(name = "detected_at", nullable = false) public Instant detectedAt;
  @Column(name = "resolved_at") public Instant resolvedAt;
  @Column(name = "resolution", length = 32) public String resolution;
  @Column(name = "acknowledged_by") public String acknowledgedBy;
  @Column(name = "ack_comment", length = 1000) public String ackComment;
  @Column(name = "ack_audit_id") public Long ackAuditId;
}
