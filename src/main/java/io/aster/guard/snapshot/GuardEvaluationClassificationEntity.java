package io.aster.guard.snapshot;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;

/**
 * 可审批评估（及要求 verifiedInputs 的 ALLOW，ADR 0046 §11.8）的分类行（ADR 0042 §12 D1）：评估事件发布之后写入，先写者为准、从不更新；
 * from-evidence 用 {@code input_hash} 核对输入，用 {@code classification} 打码。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_evaluation_classifications")
@IdClass(GuardEvaluationClassificationEntity.Key.class)
public class GuardEvaluationClassificationEntity extends PanacheEntityBase {
  @Id @Column(name = "tenant_id", length = 64) public String tenantId;
  @Id @Column(name = "correlation_id", length = 64) public String correlationId;
  /** 请求 context 的 {@code sha256-sorted-json/v1}；位置格式输入为 null。 */
  @Column(name = "input_hash", length = 64) public String inputHash;
  /** {@code InputClassification} 的 JSON；推导失败、超上限或位置输入为 null。 */
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "classification", columnDefinition = "jsonb") public String classification;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();

  /** 复合主键。 */
  public record Key(String tenantId, String correlationId) implements Serializable {
    /** JPA 要求 {@link IdClass} 有无参构造。 */
    public Key() {
      this(null, null);
    }
  }

  public static Optional<GuardEvaluationClassificationEntity> findByKey(String tenantId, String correlationId) {
    return Optional.ofNullable(findById(new Key(tenantId, correlationId)));
  }
}
