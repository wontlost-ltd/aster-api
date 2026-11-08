package io.aster.guard.receipt;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 执行收据行（ADR 0040 §12 D4）：一决策一行，是 consume / 结果回写判定的唯一权威。
 * 签发时只写前半段；consume 成功写 {@code consumed_at / executor_id / attempt_id / consume_audit_id}，
 * 结果回写写 {@code result_status / result_at / result_audit_id}，未发生的阶段保持 null。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_receipts")
public class GuardReceiptEntity extends PanacheEntityBase {
  @Id @Column(name = "decision_id") public UUID decisionId;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  @Column(name = "action_hash", nullable = false) public String actionHash;
  @Column(name = "kid", nullable = false) public String kid;
  @Column(name = "issued_at", nullable = false) public Instant issuedAt;
  /** 排他上界：{@code now >= expiresAt} 即过期。 */
  @Column(name = "expires_at", nullable = false) public Instant expiresAt;
  /** Ed25519 签名，base64url 无填充。 */
  @Column(name = "signature", nullable = false) public String signature;
  @Column(name = "consumed_at") public Instant consumedAt;
  /** 执行方自报的关联字段，不参与放行判定。 */
  @Column(name = "executor_id") public String executorId;
  @Column(name = "attempt_id") public String attemptId;
  /** 权威消耗的尝试事件 id；对账只认这个指针。 */
  @Column(name = "consume_audit_id") public Long consumeAuditId;
  /** executed / failed / aborted，数据库 CHECK 约束兜底。 */
  @Column(name = "result_status") public String resultStatus;
  @Column(name = "result_at") public Instant resultAt;
  @Column(name = "result_audit_id") public Long resultAuditId;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
}
