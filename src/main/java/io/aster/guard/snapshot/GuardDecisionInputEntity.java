package io.aster.guard.snapshot;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 待审批决策的输入快照（ADR 0042 §12 D1）：与决策行、审批行同一事务写入，写入后不可变；明文只存于本表，
 * 决策行与审计链只有 {@code context_hash}。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_decision_inputs")
public class GuardDecisionInputEntity extends PanacheEntityBase {
  /** {@link #marksSource}：标记来自决策所用版本的 Core IR（{@code POST /decisions}）。 */
  public static final String MARKS_VERSION = "version";
  /** {@link #marksSource}：标记来自评估时的分类行（from-evidence）。 */
  public static final String MARKS_EVALUATION = "evaluation";
  /** {@link #marksSource}：没有可用标记，视图中全部 {@code context.*} 叶子按 unknown 打码。 */
  public static final String MARKS_NONE = "none";
  /** {@link #inputBinding}：请求的 action 就是被评估的输入。 */
  public static final String BINDING_DIRECT = "direct";
  /** {@link #inputBinding}：已与评估时的输入哈希核对一致。 */
  public static final String BINDING_EVALUATED = "evaluated";
  /** {@link #inputBinding}：无从核对（无分类行或位置格式输入）。 */
  public static final String BINDING_UNBOUND = "unbound";

  @Id @Column(name = "decision_id") public UUID decisionId;
  @Column(name = "tenant_id", nullable = false, length = 64) public String tenantId;
  /** 明文 Action（去掉 facts），JSON 对象。 */
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "action_json", columnDefinition = "jsonb", nullable = false)
  public String actionJson;
  @Column(name = "context_hash", nullable = false, length = 64) public String contextHash;
  /** {@code InputClassification} 的 JSON；null 表示无分类。 */
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "classification", columnDefinition = "jsonb") public String classification;
  @Column(name = "marks_source", nullable = false, length = 16) public String marksSource;
  @Column(name = "input_binding", nullable = false, length = 16) public String inputBinding;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();

  public static Optional<GuardDecisionInputEntity> findByDecisionAndTenant(UUID decisionId, String tenantId) {
    return find("decisionId = ?1 and tenantId = ?2", decisionId, tenantId).firstResultOptional();
  }
}
