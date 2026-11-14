package io.aster.guard.bypass;

import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.Outcome;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 4 条：收据重放。收据一次性：第二次 consume 409 {@code receipt_consumed}（附首次的
 * {@code consumedAt / executorId / attemptId}）；并发 consume 只有一个 200；结果回写不能被另一次尝试改写。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B4ReceiptReplayBypassTest {
  private static final String TENANT = BypassFixture.tenant("b4");
  private static final String OTHER = BypassFixture.tenant("b4-other");
  private static final int THREADS = 8;

  @Inject BypassFixture api;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
  }

  private Response allow(String body, String idempotencyKey) {
    Response r = api.decide(MEMBER_KEY, body, idempotencyKey);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return r;
  }

  @Test
  void secondConsumeIs409ReceiptConsumed() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(body, null);
    String decisionId = d.jsonPath().getString("decisionId");
    Executed first = (Executed) executor.run(d.asString(), body, "refund");

    assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(body), "replay"))
        .isEqualTo(new Refused("consume", "receipt_consumed"));
    Response replay = executor.lastConsume();
    assertThat(replay.jsonPath().getString("executorId")).isEqualTo(executor.executorId());
    assertThat(replay.jsonPath().getString("attemptId")).isEqualTo(first.attemptId());
    assertThat(replay.jsonPath().getString("consumedAt")).isNotBlank();
    // 诚实执行方自己再跑一遍同样被拒（本地验签通过，consume 拒绝）
    assertThat(executor.run(d.asString(), body, "again")).isEqualTo(new Refused("consume", "receipt_consumed"));
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  /** 同 Idempotency-Key 重放得到同一 decisionId 与同一份收据——它已被消耗，不能再执行一次。 */
  @Test
  void idempotentReplayReturnsSameReceiptWhichCannotBeConsumedAgain() {
    String body = BypassFixture.allowBody(250000);
    String key = "bypass-replay-" + UUID.randomUUID();
    Response first = allow(body, key);
    assertThat(executor.run(first.asString(), body, "refund")).isInstanceOf(Executed.class);

    Response replayed = allow(body, key);

    assertThat(replayed.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
    assertThat(ExecutionReceiptAssert.tree(replayed.asString()).get("executionReceipt"))
        .isEqualTo(ExecutionReceiptAssert.tree(first.asString()).get("executionReceipt"));
    assertThat(executor.run(replayed.asString(), body, "refund-again"))
        .isEqualTo(new Refused("consume", "receipt_consumed"));
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, first.jsonPath().getString("decisionId"), ledger);
  }

  /** 8 个执行方同时 consume 同一收据：恰 1 个 200、7 个 409 receipt_consumed，账本恰一条，指针指向唯一尝试事件。 */
  @Test
  void concurrentConsumeExactlyOneWins() throws Exception {
    String body = BypassFixture.allowBody(250000);
    String decisionId = allow(body, null).jsonPath().getString("decisionId");
    String hash = ExecutionReceiptAssert.actionHashOf(body);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Outcome>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          go.await();
          return executor.attackConsume(decisionId, hash, "concurrent");
        }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        outcomes.add(f.get(60, TimeUnit.SECONDS));
      }

      assertThat(outcomes).filteredOn(o -> o instanceof Executed).hasSize(1);
      assertThat(outcomes).filteredOn(o -> o instanceof Refused).hasSize(THREADS - 1)
          .allSatisfy(o -> assertThat(o).isEqualTo(new Refused("consume", "receipt_consumed")));
    } finally {
      pool.shutdownNow();
    }
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_CONSUME_ATTEMPT")).hasSize(THREADS);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  /** 已执行并回写之后：他 attemptId 回写 → 409 attempt_mismatch；同 attempt 二次回写 → 409 result_recorded；行保留首次结果。 */
  @Test
  void resultCannotBeRewrittenByAnotherAttempt() {
    String body = BypassFixture.allowBody(250000);
    Response d = allow(body, null);
    String decisionId = d.jsonPath().getString("decisionId");
    Executed done = (Executed) executor.run(d.asString(), body, "refund");

    Response otherAttempt = api.result(MEMBER_KEY, decisionId, UUID.randomUUID().toString(), executor.executorId(),
        "failed");
    Response otherExecutor = api.result(MEMBER_KEY, decisionId, done.attemptId(), "bypass-intruder", "failed");
    Response again = api.result(MEMBER_KEY, decisionId, done.attemptId(), executor.executorId(), "failed");

    for (Response r : List.of(otherAttempt, otherExecutor)) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
      assertThat(r.asString()).isEqualTo("{\"error\":\"attempt_mismatch\"}");
    }
    assertThat(again.statusCode()).as(again.asString()).isEqualTo(409);
    assertThat(again.asString()).isEqualTo("{\"error\":\"result_recorded\"}");
    assertThat(BypassFixture.receiptRow(decisionId).resultStatus).isEqualTo("executed");
    assertThat(BypassFixture.events(decisionId, "ACTION_GUARD_RESULT")).hasSize(1);
    assertThat(api.decision(MEMBER_KEY, decisionId).jsonPath().getString("result.status")).isEqualTo("executed");
  }
}
