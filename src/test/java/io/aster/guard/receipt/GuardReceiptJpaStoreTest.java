package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.GuardDecisionService;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.receipt.GuardReceiptStore.ConsumeVerdict;
import io.aster.guard.receipt.GuardReceiptStore.ResultVerdict;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR 0040 §12 D5 的原子性：真实 Postgres 上 {@code PESSIMISTIC_WRITE} 行锁下的并发 consume，
 * 以及「行已提交、结果事件未写成」的崩溃窗口。
 */
@QuarkusTest
class GuardReceiptJpaStoreTest {
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-consume-store-" + RUN;
  private static final int THREADS = 16;
  private static final String BODY = "{\"policyModule\":\"" + DeployedMapParamProbeTest.MODULE
      + "\",\"policyFunction\":\"decide\",\"action\":{\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\"},"
      + "\"action\":{\"name\":\"read_customer_record\"},\"context\":{\"amount\":250000}}}";

  @Inject GuardReceiptService service;
  @Inject GuardReceiptStore store;
  @Inject GuardReceiptBuilder builder;
  @Inject GuardDecisionService decisions;
  @Inject ReceiptSigningKeys keys;
  @Inject AuditChainAppender appender;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void setUp() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity.delete("tenantId = ?1", TENANT);
      GuardReceiptEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
      PolicyVersion.delete("tenantId = ?1", TENANT);
      PolicyCatalog.delete("tenantId = ?1", TENANT);
    });
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    DeployedMapParamProbeTest.deploy(TENANT, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
        DeployedMapParamProbeTest.SOURCE);
  }

  private static UUID allowDecision() {
    Response r = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "user-store")
        .contentType(ContentType.JSON).body(BODY).when().post("/api/v1/guard/decisions");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return UUID.fromString(r.jsonPath().getString("decisionId"));
  }

  /** 执行方自算：请求体原文中 action 子树的 jcs-sha256/v1。 */
  private static String actionHash() throws IOException {
    return DigestUtils.sha256Hex(JcsCanonicalizer.canonicalize(new ObjectMapper().readTree(BODY).get("action")));
  }

  private static List<AuditLog> events(UUID decisionId, EventType type) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>list(
        "decisionId = ?1 and eventType = ?2 order by id", decisionId.toString(), type.name()));
  }

  private static GuardReceiptEntity row(UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.<GuardReceiptEntity>findById(decisionId));
  }

  /** Review Focus 2：16 线程同时 consume 同一决策（各自不同 attemptId）→ 恰 1 个 Consumed，15 个 receipt_consumed。 */
  @Test
  void concurrentConsumeExactlyOneWins() throws Exception {
    UUID decisionId = allowDecision();
    String hash = actionHash();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<String> attemptIds = new ArrayList<>();
    List<Future<ConsumeVerdict>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS; i++) {
        String attemptId = UUID.randomUUID().toString();
        attemptIds.add(attemptId);
        futures.add(pool.submit(() -> {
          start.await();
          return service.consume(TENANT, "user-store", decisionId, new ConsumeRequest(hash, attemptId, "exec"))
              .orElseThrow().verdict();
        }));
      }
      start.countDown();
      List<ConsumeVerdict> verdicts = new ArrayList<>();
      for (Future<ConsumeVerdict> f : futures) {
        verdicts.add(f.get(60, TimeUnit.SECONDS));
      }

      List<ConsumeVerdict.Consumed> winners = verdicts.stream()
          .filter(ConsumeVerdict.Consumed.class::isInstance).map(ConsumeVerdict.Consumed.class::cast).toList();
      assertThat(winners).hasSize(1);
      assertThat(verdicts).filteredOn(ConsumeVerdict.Rejected.class::isInstance)
          .hasSize(THREADS - 1)
          .allSatisfy(v -> assertThat(((ConsumeVerdict.Rejected) v).code()).isEqualTo("receipt_consumed"));

      GuardReceiptEntity committed = row(decisionId);
      List<AuditLog> attempts = events(decisionId, EventType.ACTION_GUARD_CONSUME_ATTEMPT);
      assertThat(attempts).hasSize(THREADS);
      AuditLog authoritative = attempts.stream().filter(e -> e.id.equals(committed.consumeAuditId))
          .findFirst().orElseThrow();
      assertThat(authoritative.metadata).contains("\"attemptId\":\"" + committed.attemptId + "\"");
      assertThat(committed.attemptId).isEqualTo(winners.get(0).row().attemptId).isIn(attemptIds);
      List<AuditLog> outcomes = events(decisionId, EventType.ACTION_GUARD_CONSUME_OUTCOME);
      assertThat(outcomes).hasSize(THREADS);
      assertThat(outcomes).filteredOn(e -> e.metadata.contains("\"outcome\":\"consumed\""))
          .singleElement()
          .satisfies(e -> assertThat(e.metadata).contains("\"attemptReceiptHash\":\"" + authoritative.currentHash + "\""));
    } finally {
      pool.shutdownNow();
    }
  }

  /** 结果事件追加抛异常（模拟行提交后、结果事件前崩溃）：判定仍为 Consumed，链上只有尝试事件且被行引用。 */
  @Test
  void crashAfterCommitBeforeOutcomeLeavesOnlyAttempt() throws IOException {
    UUID decisionId = allowDecision();
    AuditChainAppender crashing = mock(AuditChainAppender.class);
    when(crashing.appendSync(any())).thenAnswer(inv -> {
      AuditEvent event = inv.getArgument(0);
      if (event.eventType() == EventType.ACTION_GUARD_CONSUME_OUTCOME) {
        throw new AuditChainAppender.AuditAppendException("模拟崩溃", null);
      }
      return appender.appendSync(event);
    });
    GuardReceiptService crashingService = new GuardReceiptService(store, crashing, builder, decisions, keys);

    GuardReceiptService.Result result = crashingService.consume(TENANT, "user-store", decisionId,
        new ConsumeRequest(actionHash(), UUID.randomUUID().toString(), "exec")).orElseThrow();

    assertThat(result.verdict()).isInstanceOf(ConsumeVerdict.Consumed.class);
    List<AuditLog> attempts = events(decisionId, EventType.ACTION_GUARD_CONSUME_ATTEMPT);
    assertThat(attempts).hasSize(1);
    assertThat(row(decisionId).consumeAuditId).isEqualTo(attempts.get(0).id);
    assertThat(events(decisionId, EventType.ACTION_GUARD_CONSUME_OUTCOME)).isEmpty();
  }

  /** 判定顺序（ADR 0040 §12 D5）：kid_revoked → receipt_consumed → receipt_expired → action_mismatch；expiresAt 排他。 */
  @Test
  void checkOrderAndExclusiveExpiry() {
    GuardReceiptEntity r = new GuardReceiptEntity();
    r.kid = "k";
    r.actionHash = "a".repeat(64);
    r.expiresAt = Instant.parse("2026-11-08T10:05:00Z");
    ConsumeRequest wrong = new ConsumeRequest("b".repeat(64), UUID.randomUUID().toString(), "e");
    ConsumeRequest right = new ConsumeRequest("a".repeat(64), UUID.randomUUID().toString(), "e");
    Instant before = r.expiresAt.minusMillis(1);

    assertThat(GuardReceiptJpaStore.check(r, wrong, r.expiresAt, kid -> true)).get()
        .extracting(ConsumeVerdict.Rejected::code).isEqualTo("kid_revoked");
    assertThat(GuardReceiptJpaStore.check(r, wrong, r.expiresAt, kid -> false)).get()
        .extracting(ConsumeVerdict.Rejected::code).isEqualTo("receipt_expired");
    assertThat(GuardReceiptJpaStore.check(r, wrong, before, kid -> false)).get()
        .extracting(ConsumeVerdict.Rejected::code).isEqualTo("action_mismatch");
    assertThat(GuardReceiptJpaStore.check(r, right, before, kid -> false)).isEmpty();
    r.consumedAt = before;
    r.executorId = "first";
    r.attemptId = "a-1";
    assertThat(GuardReceiptJpaStore.check(r, wrong, r.expiresAt, kid -> false)).get()
        .satisfies(v -> {
          assertThat(v.code()).isEqualTo("receipt_consumed");
          assertThat(v.extra()).containsExactly(Map.entry("consumedAt", before.toString()),
              Map.entry("executorId", "first"), Map.entry("attemptId", "a-1"));
        });
  }

  /**
   * 结果回写的并发（ADR 0040 §12 D7）：同一消耗者 8 线程同时回写 → 恰 1 个 Recorded，其余 result_recorded；
   * 每次通过预判的调用各留一条结果事件，只有 result_audit_id 引用的那条生效。
   */
  @Test
  void concurrentResultExactlyOneRecorded() throws Exception {
    UUID decisionId = allowDecision();
    String attemptId = UUID.randomUUID().toString();
    assertThat(service.consume(TENANT, "user-store", decisionId, new ConsumeRequest(actionHash(), attemptId, "exec"))
        .orElseThrow().verdict()).isInstanceOf(ConsumeVerdict.Consumed.class);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ResultVerdict>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return service.recordResult(TENANT, "user-store", decisionId,
              new ResultRequest(attemptId, "exec", "executed", null, null, null)).orElseThrow();
        }));
      }
      start.countDown();
      List<ResultVerdict> verdicts = new ArrayList<>();
      for (Future<ResultVerdict> f : futures) {
        verdicts.add(f.get(60, TimeUnit.SECONDS));
      }

      assertThat(verdicts).filteredOn(ResultVerdict.Recorded.class::isInstance).hasSize(1);
      assertThat(verdicts).filteredOn(ResultVerdict.Rejected.class::isInstance).hasSize(threads - 1)
          .allSatisfy(v -> assertThat(((ResultVerdict.Rejected) v).code()).isEqualTo("result_recorded"));
      GuardReceiptEntity committed = row(decisionId);
      assertThat(committed.resultStatus).isEqualTo("executed");
      List<AuditLog> results = events(decisionId, EventType.ACTION_GUARD_RESULT);
      assertThat(results).isNotEmpty().hasSizeLessThanOrEqualTo(threads);
      assertThat(results).extracting(e -> e.id).contains(committed.resultAuditId);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 结果判定顺序：not_consumed → attempt_mismatch → result_recorded（行为 null 即无收据行）。 */
  @Test
  void checkResultOrder() {
    ResultRequest req = new ResultRequest("a-1", "first", "executed", null, null, null);
    GuardReceiptEntity r = new GuardReceiptEntity();

    assertThat(GuardReceiptJpaStore.checkResult(null, req)).contains("not_consumed");
    assertThat(GuardReceiptJpaStore.checkResult(r, req)).contains("not_consumed");
    r.consumedAt = Instant.parse("2026-11-08T10:01:00Z");
    r.attemptId = "a-1";
    r.executorId = "first";
    r.resultStatus = "aborted";
    assertThat(GuardReceiptJpaStore.checkResult(r, new ResultRequest("a-2", "first", "executed", null, null, null)))
        .contains("attempt_mismatch");
    assertThat(GuardReceiptJpaStore.checkResult(r, new ResultRequest("a-1", "other", "executed", null, null, null)))
        .contains("attempt_mismatch");
    assertThat(GuardReceiptJpaStore.checkResult(r, req)).contains("result_recorded");
    r.resultStatus = null;
    assertThat(GuardReceiptJpaStore.checkResult(r, req)).isEmpty();
  }
}
