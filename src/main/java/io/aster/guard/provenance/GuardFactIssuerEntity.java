package io.aster.guard.provenance;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 签名事实签发方公钥（ADR 0040 §11 D3）。主键 {@code (tenant_id, issuer, kid)}：签发方是租户级事实，不跨租户共享。
 * 吊销只置 {@code enabled=false}，行永久保留供审计；{@code publicKey} 为 base64url（无填充）的 32 字节 Ed25519 原始公钥。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_fact_issuers")
@IdClass(GuardFactIssuerEntity.Key.class)
public class GuardFactIssuerEntity extends PanacheEntityBase {
  /** 目前唯一支持的签名算法。 */
  public static final String ALG_ED25519 = "Ed25519";

  @Id @Column(name = "tenant_id") public String tenantId;
  @Id @Column(name = "issuer") public String issuer;
  @Id @Column(name = "kid") public String kid;
  @Column(name = "alg", nullable = false) public String alg = ALG_ED25519;
  @Column(name = "public_key", nullable = false) public String publicKey;
  @Column(name = "enabled", nullable = false) public boolean enabled;
  @Column(name = "not_after") public Instant notAfter;
  @Column(name = "created_at", nullable = false) public Instant createdAt;
  @Column(name = "created_by", nullable = false) public String createdBy;
  /** 吊销（启用 → 禁用）时刻；新建即禁用的行为 null。 */
  @Column(name = "revoked_at") public Instant revokedAt;
  /** 吊销操作者（{@code not_after} 到期为 {@code SYSTEM}），补写吊销事件时沿用。 */
  @Column(name = "revoked_by") public String revokedBy;
  /** 该次吊销的 {@code GUARD_FACT_ISSUER_REVOKED} 事件 id；{@code revokedAt} 非空而本字段为 null 表示事件尚未入链。 */
  @Column(name = "revocation_audit_id") public Long revocationAuditId;

  /** 复合主键。 */
  public record Key(String tenantId, String issuer, String kid) implements Serializable {
    /** JPA 要求 {@link IdClass} 有无参构造。 */
    public Key() {
      this(null, null, null);
    }
  }

  /** 本租户全部签发方（含已禁用），按 issuer、kid 排序。 */
  public static List<GuardFactIssuerEntity> listByTenant(String tenantId) {
    return list("tenantId = ?1 order by issuer, kid", tenantId);
  }
}
