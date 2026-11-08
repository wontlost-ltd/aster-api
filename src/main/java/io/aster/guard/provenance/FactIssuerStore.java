package io.aster.guard.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
    return Optional.ofNullable(findRow(tenantId, issuer, kid));
  }

  @Transactional
  public List<GuardFactIssuerEntity> list(String tenantId) {
    return GuardFactIssuerEntity.listByTenant(tenantId);
  }

  /** {@link #upsert} 的结果：写入成功，或因与已有行冲突而拒绝（行不变）。 */
  public sealed interface UpsertResult {
    /** 写入后的行。 */
    record Saved(GuardFactIssuerEntity row) implements UpsertResult {}

    /** 冲突码：{@link #KEY_CONFLICT} 或 {@link #REVOKED}。 */
    record Conflict(String code) implements UpsertResult {}
  }

  /** 同一 (tenant, issuer, kid) 已登记了不同公钥：kid 与公钥一一绑定，换钥须换 kid。 */
  public static final String KEY_CONFLICT = "issuer_key_conflict";
  /** 已禁用（吊销）的签发方不可重新启用：吊销是终态，恢复须轮换到新 kid。 */
  public static final String REVOKED = "issuer_revoked";

  private static final String INSERT_IF_ABSENT = "INSERT INTO guard_fact_issuers "
      + "(tenant_id, issuer, kid, alg, public_key, enabled, not_after, created_at, created_by) "
      + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9) ON CONFLICT (tenant_id, issuer, kid) DO NOTHING";

  @Inject
  EntityManager em;

  /**
   * 登记签发方，或更新同一公钥的启用状态与 {@code notAfter}（创建时间与创建人保留首次写入值）。
   *
   * <p>首次写入用 {@code INSERT … ON CONFLICT DO NOTHING}：并发首次写入同一主键时后到者不报主键冲突，
   * 而是读回先到者的行，再按与已有行相同的规则裁决（同钥 200，异钥 409）。规则：
   * 公钥不同 → {@link #KEY_CONFLICT}；行已禁用而请求启用 → {@link #REVOKED}；其余更新 enabled / notAfter。
   */
  @Transactional
  public UpsertResult upsert(String tenantId, String issuer, String kid, String publicKey, boolean enabled,
                             Instant notAfter, String performedBy, Instant now) {
    GuardFactIssuerEntity existing = findRow(tenantId, issuer, kid);
    if (existing == null) {
      int inserted = em.createNativeQuery(INSERT_IF_ABSENT)
          .setParameter(1, tenantId).setParameter(2, issuer).setParameter(3, kid)
          .setParameter(4, GuardFactIssuerEntity.ALG_ED25519).setParameter(5, publicKey)
          .setParameter(6, enabled).setParameter(7, notAfter).setParameter(8, now).setParameter(9, performedBy)
          .executeUpdate();
      GuardFactIssuerEntity row = findRow(tenantId, issuer, kid);
      if (inserted == 1) {
        return new UpsertResult.Saved(row);
      }
      existing = row;
    }
    if (!existing.publicKey.equals(publicKey)) {
      return new UpsertResult.Conflict(KEY_CONFLICT);
    }
    if (!existing.enabled && enabled) {
      return new UpsertResult.Conflict(REVOKED);
    }
    existing.enabled = enabled;
    existing.notAfter = notAfter;
    return new UpsertResult.Saved(existing);
  }

  private static GuardFactIssuerEntity findRow(String tenantId, String issuer, String kid) {
    return GuardFactIssuerEntity.findById(new GuardFactIssuerEntity.Key(tenantId, issuer, kid));
  }

  /** 软删：置 {@code enabled=false}，行保留。返回是否存在该签发方（已禁用的再次禁用同样返回 true）。 */
  @Transactional
  public boolean disable(String tenantId, String issuer, String kid) {
    GuardFactIssuerEntity row = findRow(tenantId, issuer, kid);
    if (row == null) {
      return false;
    }
    row.enabled = false;
    return true;
  }
}
