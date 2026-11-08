package io.aster.guard.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 签发方表的唯一读写入口（ADR 0040 §11 D3）。读取不缓存：写入后下一次验签立即可见，
 * 每次决策至多 8 次主键查询，代价可接受。
 */
@ApplicationScoped
public class FactIssuerStore {

  /** 主键直查；不存在返回空。是否 {@code enabled} / 过 {@code notAfter} 由调用方判定。 */
  @Transactional
  public Optional<GuardFactIssuerEntity> find(String tenantId, String issuer, String kid) {
    return Optional.ofNullable(GuardFactIssuerEntity.findById(new GuardFactIssuerEntity.Key(tenantId, issuer, kid)));
  }

  @Transactional
  public List<GuardFactIssuerEntity> list(String tenantId) {
    return GuardFactIssuerEntity.listByTenant(tenantId);
  }

  /**
   * 登记或覆盖签发方。已存在时只改公钥、启用状态与 {@code notAfter}，创建时间与创建人保留首次写入值。
   * 并发首次写入同一主键时后到者因主键冲突失败（不吞异常，调用方重试即为覆盖）。
   */
  @Transactional
  public GuardFactIssuerEntity upsert(String tenantId, String issuer, String kid, String publicKey, boolean enabled,
                                      Instant notAfter, String performedBy, Instant now) {
    GuardFactIssuerEntity existing = GuardFactIssuerEntity.findById(new GuardFactIssuerEntity.Key(tenantId, issuer, kid));
    GuardFactIssuerEntity row = existing != null ? existing : new GuardFactIssuerEntity();
    if (existing == null) {
      row.tenantId = tenantId;
      row.issuer = issuer;
      row.kid = kid;
      row.createdAt = now;
      row.createdBy = performedBy;
    }
    row.alg = GuardFactIssuerEntity.ALG_ED25519;
    row.publicKey = publicKey;
    row.enabled = enabled;
    row.notAfter = notAfter;
    // 新行在字段齐全后再 persist：Hibernate 在 persist 时取插入快照。
    if (existing == null) {
      row.persist();
    }
    return row;
  }

  /** 软删：置 {@code enabled=false}，行保留。返回是否存在该签发方（已禁用的再次禁用同样返回 true）。 */
  @Transactional
  public boolean disable(String tenantId, String issuer, String kid) {
    GuardFactIssuerEntity row = GuardFactIssuerEntity.findById(new GuardFactIssuerEntity.Key(tenantId, issuer, kid));
    if (row == null) {
      return false;
    }
    row.enabled = false;
    return true;
  }
}
