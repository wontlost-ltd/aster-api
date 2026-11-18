package io.aster.policy.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.signing.Ed25519KeyRing;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.List;

/**
 * 启动时校验导出签名密钥配置 {@code revision} 单调不减（ADR 0059 修订 3 P2-R2，表 {@code export_signing_key_revisions}，
 * V6.40.0）：小于已持久化的最大值 → 启动失败；等于 → 不写；大于 → 插入一行。未配置导出密钥时什么都不做。
 *
 * <p>与收据密钥的 {@code SigningKeyRevisionStartup} 同一做法：「读 max → 比较 → 插入」在一个 {@code requiringNew}
 * 事务内，先取 {@code pg_advisory_xact_lock(hashtext('export_signing_key_revisions'))} 串行化多副本并发启动；
 * 插入带 {@code ON CONFLICT (revision) DO NOTHING} 兜底。以 {@code @Observes StartupEvent} 触发，Flyway 已迁移完成。
 */
@ApplicationScoped
public class ExportKeyRevisionStartup {
  static final String REGRESSION_MESSAGE = "导出签名密钥配置 revision 回退";

  private static final Logger LOG = Logger.getLogger(ExportKeyRevisionStartup.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext('export_signing_key_revisions'))";
  private static final String MAX_SQL = "SELECT max(revision) FROM export_signing_key_revisions";
  private static final String INSERT_SQL = "INSERT INTO export_signing_key_revisions (revision, applied_at, kids) "
      + "VALUES (?1, ?2, CAST(?3 AS jsonb)) ON CONFLICT (revision) DO NOTHING";

  @Inject EntityManager em;
  @Inject ExportSigningKeys keys;
  @Inject Clock clock;

  void onStart(@Observes StartupEvent event) {
    keys.ring().ifPresent(ring -> {
      List<String> kids = ring.all().stream().map(Ed25519KeyRing.SigningKey::kid).toList();
      check(ring.revision(), kids);
      LOG.info("导出签名密钥已加载 revision=" + ring.revision() + " kids=[" + String.join(", ", kids) + "]");
    });
  }

  /**
   * @param kids 该配置中的全部 kid，写入新 revision 行供事后追溯
   * @throws IllegalStateException {@code revision} 小于已持久化的最大值
   */
  public void check(long revision, List<String> kids) {
    String kidsJson = toJson(kids);
    QuarkusTransaction.requiringNew().run(() -> {
      em.createNativeQuery(LOCK_SQL).getSingleResult();
      Number max = (Number) em.createNativeQuery(MAX_SQL).getSingleResult();
      if (max != null && revision < max.longValue()) {
        throw new IllegalStateException(REGRESSION_MESSAGE + "：配置 revision=" + revision + "，已持久化最大值=" + max);
      }
      if (max != null && revision == max.longValue()) {
        return;
      }
      em.createNativeQuery(INSERT_SQL)
          .setParameter(1, revision)
          .setParameter(2, clock.instant())
          .setParameter(3, kidsJson)
          .executeUpdate();
    });
  }

  private static String toJson(List<String> kids) {
    try {
      return JSON.writeValueAsString(kids);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("kids 无法序列化", e);
    }
  }
}
