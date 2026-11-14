package io.aster.guard.bypass;

import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.policy.entity.AuditLog;
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
import java.util.List;
import java.util.UUID;

import static io.aster.guard.bypass.BypassFixture.DPO_KEY;
import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 2 条：DENY / 待审批 / 审批拒绝之后仍试图执行。DENY 与 PENDING 没有执行收据，参考执行方本地即拒绝；
 * 跳过本地检查直接 consume 一律 409 {@code not_allowed}（附 {@code effectiveOutcome}），账本为空、收据未消耗。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B2ExecuteAfterDenyBypassTest {
  private static final String TENANT = BypassFixture.tenant("b2");
  private static final String OTHER = BypassFixture.tenant("b2-other");
  private static final String ATTEMPT = "ACTION_GUARD_CONSUME_ATTEMPT";
  private static final String OUTCOME = "ACTION_GUARD_CONSUME_OUTCOME";

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

  private Response decided(String key, String body, String expectedEffective) {
    Response r = api.decide(key, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo(expectedEffective);
    return r;
  }

  private void assertConsumeNotAllowed(String decisionId, String hash, String effective) {
    assertThat(executor.attackConsume(decisionId, hash, "attack")).isEqualTo(new Refused("consume", "not_allowed"));
    assertThat(executor.lastConsume().asString())
        .isEqualTo("{\"error\":\"not_allowed\",\"effectiveOutcome\":\"" + effective + "\"}");
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
  }

  @Test
  void denyHasNoReceiptAndReferenceExecutorRefuses() {
    String body = BypassFixture.denyBody(1);
    Response deny = decided(MEMBER_KEY, body, "DENY");
    String decisionId = deny.jsonPath().getString("decisionId");
    assertThat(deny.jsonPath().getMap("executionReceipt")).isNull();

    assertThat(executor.run(deny.asString(), body, "refund")).isEqualTo(new Refused("local", "receipt_missing"));

    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "DENY", null);
    assertThat(BypassFixture.events(decisionId, ATTEMPT)).isEmpty();
  }

  @Test
  void attackerConsumeAfterDenyIs409NotAllowed() {
    String body = BypassFixture.denyBody(1);
    String decisionId = decided(MEMBER_KEY, body, "DENY").jsonPath().getString("decisionId");

    assertConsumeNotAllowed(decisionId, ExecutionReceiptAssert.actionHashOf(body), "DENY");

    // 链上：恰一条尝试事件 + 一条 rejected:not_allowed 判定事件；guard_receipts 无行
    assertThat(BypassFixture.events(decisionId, ATTEMPT)).hasSize(1);
    List<AuditLog> outcomes = BypassFixture.events(decisionId, OUTCOME);
    assertThat(outcomes).singleElement().satisfies(e -> {
      assertThat(e.metadata).contains("\"outcome\":\"rejected:not_allowed\"");
      assertThat(e.success).isFalse();
    });
    assertThat(BypassFixture.receiptRow(decisionId)).isNull();
  }

  /**
   * 待审批不可执行；发起方（MEMBER）批准自己的审批 → 403 segregation_of_duties。
   * 发起方即使持有所需业务角色（DPO 自己提交、自己批准）同样 403——四眼按身份判定，与角色无关。
   */
  @Test
  void pendingCannotBeExecutedAndRequesterCannotSelfApprove() {
    String body = BypassFixture.pendingBody(250000);
    Response pending = decided(MEMBER_KEY, body, "PENDING");
    String decisionId = pending.jsonPath().getString("decisionId");
    String approvalId = pending.jsonPath().getString("approval.id");

    assertThat(executor.run(pending.asString(), body, "delete")).isEqualTo(new Refused("local", "receipt_missing"));
    assertConsumeNotAllowed(decisionId, ExecutionReceiptAssert.actionHashOf(body), "PENDING");

    Response self = api.approve(MEMBER_KEY, approvalId, "自批");
    assertThat(self.statusCode()).as(self.asString()).isEqualTo(403);
    assertThat(self.jsonPath().getString("error")).isEqualTo("segregation_of_duties");

    String dpoBody = BypassFixture.pendingBody(250001);
    Response dpoPending = decided(DPO_KEY, dpoBody, "PENDING");
    Response dpoSelf = api.approve(DPO_KEY, dpoPending.jsonPath().getString("approval.id"), "自批");
    assertThat(dpoSelf.statusCode()).as(dpoSelf.asString()).isEqualTo(403);
    assertThat(dpoSelf.jsonPath().getString("error")).isEqualTo("segregation_of_duties");

    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "PENDING", null);
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, dpoPending.jsonPath().getString("decisionId"), "PENDING", null);
    assertConsumeNotAllowed(decisionId, ExecutionReceiptAssert.actionHashOf(body), "PENDING");
    assertThat(ledger.entries()).isEmpty();
  }

  @Test
  void rejectedCannotBeExecuted() {
    String body = BypassFixture.pendingBody(250000);
    Response pending = decided(MEMBER_KEY, body, "PENDING");
    String decisionId = pending.jsonPath().getString("decisionId");
    Response rejected = api.reject(DPO_KEY, pending.jsonPath().getString("approval.id"), "不批");
    assertThat(rejected.statusCode()).as(rejected.asString()).isEqualTo(200);
    assertThat(rejected.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(rejected.jsonPath().getMap("executionReceipt")).isNull();

    assertThat(executor.run(rejected.asString(), body, "delete")).isEqualTo(new Refused("local", "receipt_missing"));
    assertConsumeNotAllowed(decisionId, ExecutionReceiptAssert.actionHashOf(body), "DENY");
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "DENY", null);
  }

  /** DENY 后以同一 Idempotency-Key 换动作重新申请 → 422 idempotency_mismatch，不落新决策行，不能把 DENY「洗」成 ALLOW。 */
  @Test
  void reRequestWithSameIdempotencyKeyAndChangedActionIs422() {
    String key = "bypass-idem-" + UUID.randomUUID();
    String denied = BypassFixture.denyBody(1);
    Response first = api.decide(MEMBER_KEY, denied, key);
    assertThat(first.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    long rowsBefore = BypassFixture.decisionRows(TENANT);

    Response changed = api.decide(MEMBER_KEY, BypassFixture.denyBody(2), key);
    Response otherPolicy = api.decide(MEMBER_KEY, BypassFixture.allowBody(1), key);

    for (Response r : List.of(changed, otherPolicy)) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(422);
      assertThat(r.jsonPath().getString("error")).isEqualTo("idempotency_mismatch");
    }
    assertThat(BypassFixture.decisionRows(TENANT)).isEqualTo(rowsBefore);
    Response replay = api.decide(MEMBER_KEY, denied, key);
    assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
    assertThat(replay.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(ledger.entries()).isEmpty();
  }

  /** ADR 0054 §7 2b：DENY 后换一个被允许的动作重新申请，得到独立的新决策；旧 DENY 仍不可 consume，账本只含新决策。 */
  @Test
  void denyThenOtherAllowedToolGetsIndependentDecision() {
    String deniedBody = BypassFixture.denyBody(1);
    String denyId = decided(MEMBER_KEY, deniedBody, "DENY").jsonPath().getString("decisionId");
    // 换一个动作（金额不同）：action 子树不含 policyModule，同一动作换策略模块得到的是同一个 actionHash
    String allowedBody = BypassFixture.allowBody(2);
    Response allow = decided(MEMBER_KEY, allowedBody, "ALLOW");
    String allowId = allow.jsonPath().getString("decisionId");
    assertThat(allowId).isNotEqualTo(denyId);

    assertThat(executor.run(allow.asString(), allowedBody, "read")).isInstanceOf(Executed.class);
    assertConsumeNotAllowed(denyId, ExecutionReceiptAssert.actionHashOf(deniedBody), "DENY");
    // 新决策的收据也不能拿去执行旧 DENY 的动作
    assertThat(executor.run(allow.asString(), deniedBody, "read")).isEqualTo(new Refused("local", "action_mismatch"));

    assertThat(ledger.entries()).singleElement().satisfies(e -> assertThat(e.decisionId()).isEqualTo(allowId));
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, allowId, ledger);
  }
}
