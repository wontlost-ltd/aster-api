package io.aster.guard.receipt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 启动时校验签名密钥配置 {@code revision} 单调不减（ADR 0040 §12 D6）：小于已持久化的最大值 → 启动失败；
 * 等于 → 不写；大于 → 插入一行。
 *
 * <p>「读 max → 比较 → 插入」在一个 {@code requiringNew} 事务内，先取事务级 advisory 锁
 * {@code pg_advisory_xact_lock(hashtext('guard_signing_key_revisions'))} 串行化。没有这把锁时，
 * 带 N 与 N+1 的两个副本并发启动可能都读到旧 max 而都通过；有锁后后到者在先到者提交之后才读 max，
 * 必然看到已提交的更大值。插入仍带 {@code ON CONFLICT (revision) DO NOTHING} 兜底。
 *
 * <p>以 {@code @Observes StartupEvent} 触发：Quarkus 在 Flyway migrate-at-start 完成后才发出该事件，
 * 表一定已存在。抛出的异常让启动失败。
 */
@ApplicationScoped
public class SigningKeyRevisionStartup {
  static final String REGRESSION_MESSAGE = "签名密钥配置 revision 回退";

  private static final Logger LOG = Logger.getLogger(SigningKeyRevisionStartup.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  /** 常量锁键；与其他 advisory 锁（按租户 / 策略派生的键）键空间不会有意重叠。 */
  private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext('guard_signing_key_revisions'))";
  private static final String INSERT_SQL = "INSERT INTO guard_signing_key_revisions (revision, applied_at, kids) "
      + "VALUES (?1, ?2, CAST(?3 AS jsonb)) ON CONFLICT (revision) DO NOTHING";
  private static final Runnable NO_HOOK = () -> { };

  @Inject EntityManager em;
  @Inject ReceiptSigningKeys keys;
  @Inject Clock clock;

  void onStart(@Observes StartupEvent event) {
    List<String> kids = keys.all().stream().map(ReceiptSigningKeys.SigningKey::kid).toList();
    check(keys.revision(), kids);
    LOG.info(loadedMessage(keys.revision(), kids));
  }

  /** 启动成功日志，运维手册以此确认新配置已加载；只含 revision 与 kid，绝不含密钥材料。 */
  static String loadedMessage(long revision, List<String> kids) {
    return "签名密钥已加载 revision=" + revision + " kids=[" + String.join(", ", kids) + "]";
  }

  /**
   * @param kids 该配置中的全部 kid，写入新 revision 行供事后追溯
   * @throws IllegalStateException {@code revision} 小于已持久化的最大值
   */
  public void check(long revision, List<String> kids) {
    check(revision, kids, NO_HOOK);
  }

  /** {@code afterLock} 在取得 advisory 锁后、读 max 前执行；仅供测试构造确定的并发先后。 */
  void check(long revision, List<String> kids, Runnable afterLock) {
    String kidsJson = toJson(kids);
    QuarkusTransaction.requiringNew().run(() -> {
      em.createNativeQuery(LOCK_SQL).getSingleResult();
      afterLock.run();
      apply(revision, kidsJson);
    });
  }

  private void apply(long revision, String kidsJson) {
    Long max = em.createQuery("select max(r.revision) from GuardSigningKeyRevisionEntity r", Long.class)
        .getSingleResult();
    if (max != null && revision < max) {
      throw new IllegalStateException(REGRESSION_MESSAGE + "：配置 revision=" + revision + "，已持久化最大值=" + max);
    }
    if (max != null && revision == max) {
      return;
    }
    em.createNativeQuery(INSERT_SQL)
        .setParameter(1, revision)
        .setParameter(2, clock.instant())
        .setParameter(3, kidsJson)
        .executeUpdate();
  }

  private static String toJson(List<String> kids) {
    try {
      return JSON.writeValueAsString(kids);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("kids 无法序列化", e);
    }
  }
}
