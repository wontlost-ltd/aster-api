package io.aster.guard;

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
 * Action Guard 决策记录（ADR 0040 §3.3）。response_json 是<b>当前</b>响应：审批迁移时与审批行同一事务改写，
 * 幂等重放与回读都从它读取。requested_by / requested_api_key_id 记录提交请求的已解析身份，供审批四眼校验。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_decisions")
public class GuardDecisionEntity extends PanacheEntityBase {
  @Id @Column(name = "id") public UUID id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  @Column(name = "idempotency_key") public String idempotencyKey;
  @Column(name = "policy_module", nullable = false) public String policyModule;
  @Column(name = "policy_function", nullable = false) public String policyFunction;
  @Column(name = "policy_version_id") public Long policyVersionId;
  @Column(name = "source_hash") public String sourceHash;
  @Column(name = "principal_id", nullable = false) public String principalId;
  @Column(name = "action_name", nullable = false) public String actionName;
  @Column(name = "resource_type", nullable = false) public String resourceType = "";
  @Column(name = "resource_id", nullable = false) public String resourceId = "";
  @Column(name = "context_hash", nullable = false) public String contextHash;
  @Column(name = "outcome", nullable = false) public String outcome;
  @Column(name = "reason") public String reason;
  @Column(name = "required_role") public String requiredRole;
  @Column(name = "reason_code") public String reasonCode;
  @Column(name = "rule_id") public String ruleId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "controls", columnDefinition = "jsonb") public String controls;
  @Column(name = "receipt_audit_id") public Long receiptAuditId;
  @Column(name = "receipt_hash") public String receiptHash;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json", columnDefinition = "jsonb", nullable = false) public String responseJson;
  @Column(name = "requested_by") public String requestedBy;
  @Column(name = "requested_api_key_id") public String requestedApiKeyId;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();

  public static Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String key) {
    return find("tenantId = ?1 and idempotencyKey = ?2", tenantId, key).firstResultOptional();
  }

  public static Optional<GuardDecisionEntity> findByIdAndTenant(UUID id, String tenantId) {
    return find("id = ?1 and tenantId = ?2", id, tenantId).firstResultOptional();
  }
}
