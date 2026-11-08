package io.aster.guard.receipt;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 签名密钥配置 revision 历史（ADR 0040 §12 D6）：每个首次生效的 revision 一行，由
 * {@link SigningKeyRevisionStartup} 在启动时写入；表中最大值是「不得回退」的下界。
 */
@RegisterForReflection
@Entity
@Table(name = "guard_signing_key_revisions")
public class GuardSigningKeyRevisionEntity extends PanacheEntityBase {
  @Id @Column(name = "revision") public Long revision;
  @Column(name = "applied_at", nullable = false) public Instant appliedAt;
  /** 该 revision 配置中的全部 kid（JSON 数组，按配置顺序，含 revoked）。 */
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "kids", columnDefinition = "jsonb", nullable = false) public String kids;
}
