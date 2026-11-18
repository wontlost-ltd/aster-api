package io.aster.policy.export;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 导出签名密钥 revision 单调校验（ADR 0059 修订 3 P2-R2，表 export_signing_key_revisions）。 */
@QuarkusTest
class ExportKeyRevisionStartupTest {
  private static final List<String> KIDS = List.of("test-only-export");

  @Inject ExportKeyRevisionStartup startup;
  @Inject EntityManager em;

  /** 恢复成应用启动后的状态：只有测试 profile 的 revision 1。 */
  @AfterEach
  void restore() {
    reset();
    startup.check(1, KIDS);
  }

  private void reset(long... revisions) {
    QuarkusTransaction.requiringNew().run(() -> {
      em.createNativeQuery("DELETE FROM export_signing_key_revisions").executeUpdate();
      for (long r : revisions) {
        em.createNativeQuery("INSERT INTO export_signing_key_revisions (revision, applied_at, kids) "
            + "VALUES (?1, now(), '[]'::jsonb)").setParameter(1, r).executeUpdate();
      }
    });
  }

  @SuppressWarnings("unchecked")
  private List<Long> revisions() {
    return QuarkusTransaction.requiringNew().call(() ->
        ((List<Number>) em.createNativeQuery("SELECT revision FROM export_signing_key_revisions ORDER BY revision")
            .getResultList()).stream().map(Number::longValue).toList());
  }

  @Test
  void startupRecordedConfiguredRevision() {
    assertThat(revisions()).containsExactly(1L);
    String kids = QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
        "SELECT kids::text FROM export_signing_key_revisions WHERE revision = 1").getSingleResult());
    assertThat(kids).isEqualTo("[\"test-only-export\"]");
  }

  @Test
  void higherInsertsEqualIsNoopLowerFails() {
    reset(5L);
    startup.check(6, KIDS);
    assertThat(revisions()).containsExactly(5L, 6L);
    startup.check(6, KIDS);
    assertThat(revisions()).containsExactly(5L, 6L);
    assertThatThrownBy(() -> startup.check(4, KIDS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ExportKeyRevisionStartup.REGRESSION_MESSAGE)
        .hasMessageContaining("最大值=6");
    assertThat(revisions()).containsExactly(5L, 6L);
  }
}
