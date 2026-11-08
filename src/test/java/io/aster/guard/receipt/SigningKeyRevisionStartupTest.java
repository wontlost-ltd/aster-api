package io.aster.guard.receipt;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 签名密钥配置 revision 单调校验（ADR 0040 §12 D6）。并发用例跑在真实 PostgreSQL 上：
 * 两个线程各占一个连接与事务，靠 {@code afterLock} 钩子与 {@code pg_locks} 构造确定的加锁先后。
 */
@QuarkusTest
class SigningKeyRevisionStartupTest {
  private static final List<String> KIDS = List.of("test-a", "test-b");
  private static final int REPEAT = 20;

  @Inject SigningKeyRevisionStartup startup;
  @Inject ReceiptSigningKeys keys;

  /** 恢复成应用启动后的状态：只有测试 profile 的 revision 一行。 */
  @AfterEach
  void restore() {
    reset();
    startup.check(keys.revision(), KIDS);
  }

  @Test
  void startupRecordsConfiguredRevision() {
    assertThat(keys.revision()).isEqualTo(1L);
    GuardSigningKeyRevisionEntity row = QuarkusTransaction.requiringNew().call(() ->
        GuardSigningKeyRevisionEntity.<GuardSigningKeyRevisionEntity>findById(1L));
    assertThat(row).isNotNull();
    assertThat(row.appliedAt).isNotNull();
    assertThat(row.kids).isEqualTo("[\"test-a\", \"test-b\"]");
  }

  @Test
  void higherRevisionIsInserted() {
    reset(5L);

    startup.check(6, List.of("test-b"));

    assertThat(revisions()).containsExactly(5L, 6L);
    GuardSigningKeyRevisionEntity row = QuarkusTransaction.requiringNew().call(() ->
        GuardSigningKeyRevisionEntity.<GuardSigningKeyRevisionEntity>findById(6L));
    assertThat(row.kids).isEqualTo("[\"test-b\"]");
  }

  @Test
  void firstRevisionIsInsertedIntoEmptyTable() {
    reset();

    startup.check(0, KIDS);

    assertThat(revisions()).containsExactly(0L);
  }

  @Test
  void revisionRegressionFailsStartup() {
    reset(5L);

    assertThatThrownBy(() -> startup.check(4, KIDS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("签名密钥配置 revision 回退");
    assertThat(revisions()).containsExactly(5L);
  }

  @Test
  void sameRevisionIsNoop() {
    reset();

    startup.check(5, KIDS);
    startup.check(5, List.of("other"));

    assertThat(revisions()).containsExactly(5L);
    GuardSigningKeyRevisionEntity row = QuarkusTransaction.requiringNew().call(() ->
        GuardSigningKeyRevisionEntity.<GuardSigningKeyRevisionEntity>findById(5L));
    assertThat(row.kids).isEqualTo("[\"test-a\", \"test-b\"]");
  }

  @Test
  void concurrentSameRevisionStartupIsIdempotent() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < REPEAT; i++) {
        reset();
        CountDownLatch start = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> {
          await(start);
          startup.check(6, KIDS);
        });
        Future<?> b = pool.submit(() -> {
          await(start);
          startup.check(6, KIDS);
        });
        start.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        assertThat(revisions()).as("第 %d 轮", i).containsExactly(6L);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentNAndNPlus1BothOrders() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < REPEAT; i++) {
        // 6 先拿锁：7 等锁，6 提交后 7 看到 max=6，两者都成功
        reset(5L);
        Race lowFirst = race(pool, 6, 7);
        lowFirst.first.get(30, TimeUnit.SECONDS);
        lowFirst.second.get(30, TimeUnit.SECONDS);
        assertThat(revisions()).as("6 先，第 %d 轮", i).containsExactly(5L, 6L, 7L);

        // 7 先拿锁：6 等锁，7 提交后 6 看到 max=7，回退失败
        reset(5L);
        Race highFirst = race(pool, 7, 6);
        highFirst.first.get(30, TimeUnit.SECONDS);
        assertThatThrownBy(() -> highFirst.second.get(30, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("签名密钥配置 revision 回退");
        assertThat(revisions()).as("7 先，第 %d 轮", i).containsExactly(5L, 7L);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private record Race(Future<?> first, Future<?> second) {
  }

  /**
   * first 拿到 advisory 锁后在钩子里停住；second 启动并确认已在 {@code pg_locks} 中等待该锁后，
   * 才放行 first。于是 first 的「读 max → 插入」先提交，second 加锁后读到的 max 必含它。
   */
  private Race race(ExecutorService pool, long firstRevision, long secondRevision) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> first = pool.submit(() -> startup.check(firstRevision, KIDS, () -> {
      locked.countDown();
      await(release);
    }));
    if (!locked.await(30, TimeUnit.SECONDS)) {
      fail("first 未在 30s 内拿到 advisory 锁");
    }
    Future<?> second = pool.submit(() -> startup.check(secondRevision, KIDS));
    awaitLockWaiter();
    release.countDown();
    return new Race(first, second);
  }

  /** 轮询 pg_locks，直到有一个会话在等 revision 表的 advisory 锁。 */
  private static void awaitLockWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Number waiting = QuarkusTransaction.requiringNew().call(() -> (Number) GuardSigningKeyRevisionEntity
          .getEntityManager()
          .createNativeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
              + " AND objsubid = 1"
              + " AND objid = (hashtext('guard_signing_key_revisions')::bigint & 4294967295)::oid")
          .getSingleResult());
      if (waiting.longValue() > 0) {
        return;
      }
      Thread.sleep(10);
    }
    fail("second 未在 30s 内进入 advisory 锁等待");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("等待超时");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 清空表并写入给定 revision（升序）。 */
  private static void reset(Long... revisions) {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardSigningKeyRevisionEntity.deleteAll();
      for (Long revision : revisions) {
        GuardSigningKeyRevisionEntity row = new GuardSigningKeyRevisionEntity();
        row.revision = revision;
        row.appliedAt = java.time.Instant.parse("2026-11-01T00:00:00Z");
        row.kids = "[\"test-a\"]";
        row.persist();
      }
    });
  }

  private static List<Long> revisions() {
    return QuarkusTransaction.requiringNew().call(() -> GuardSigningKeyRevisionEntity
        .<GuardSigningKeyRevisionEntity>list("order by revision").stream().map(r -> r.revision).toList());
  }
}
