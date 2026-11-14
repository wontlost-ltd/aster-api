package io.aster.guard.bypass;

import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
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

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 套件自检：参考执行方的诚实路径真的会执行、攻击者原语只在 consume 200 时写账本——
 * 否则「账本为空」的断言在攻击路径上就是同义反复（计划 Review Focus 第 2 条）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class ReferenceExecutorSelfTest {
  private static final String TENANT = BypassFixture.tenant("self");
  private static final String OTHER = BypassFixture.tenant("self-other");

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

  @Test
  void honestAllowExecutesOnceAndReportsResult() {
    String body = BypassFixture.allowBody(250000);
    Response d = api.decide(MEMBER_KEY, body);
    assertThat(d.statusCode()).as(d.asString()).isEqualTo(200);
    String decisionId = d.jsonPath().getString("decisionId");

    ReferenceExecutor.Outcome o = executor.run(d.asString(), body, "refund:C-1001");

    assertThat(o).isEqualTo(new Executed(decisionId, ((Executed) o).attemptId()));
    assertThat(ledger.forDecision(decisionId)).hasSize(1);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
    assertThat(api.decision(MEMBER_KEY, decisionId).jsonPath().getString("result.status")).isEqualTo("executed");
    assertThat(api.decision(MEMBER_KEY, decisionId).jsonPath().getString("consumedBy.executorId"))
        .isEqualTo(executor.executorId());
  }

  @Test
  void attackerExecutesOnlyOn200() {
    String denyBody = BypassFixture.denyBody(1);
    Response deny = api.decide(MEMBER_KEY, denyBody);
    assertThat(deny.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    String denyId = deny.jsonPath().getString("decisionId");

    assertThat(executor.attackConsume(denyId, "a".repeat(64), "attack:deny"))
        .isEqualTo(new Refused("consume", "not_allowed"));
    BypassAssertions.noSideEffect(ledger, denyId);

    String allowBody = BypassFixture.allowBody(250000);
    Response allow = api.decide(MEMBER_KEY, allowBody);
    String allowId = allow.jsonPath().getString("decisionId");
    ReferenceExecutor.Outcome o = executor.attackConsume(allowId, ExecutionReceiptAssert.actionHashOf(allowBody),
        "attack:allow");

    assertThat(o).isInstanceOf(Executed.class);
    assertThat(ledger.entries()).singleElement().satisfies(e -> assertThat(e.decisionId()).isEqualTo(allowId));
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, allowId, ledger);
  }

  /** 本地检查确实会拦：请求体被改过 → 本地 action_mismatch，不 consume，收据仍可被诚实执行。 */
  @Test
  void localCheckRefusesBeforeConsume() {
    String body = BypassFixture.allowBody(250000);
    Response d = api.decide(MEMBER_KEY, body);
    String decisionId = d.jsonPath().getString("decisionId");

    assertThat(executor.run(d.asString(), BypassFixture.allowBody(1), "refund"))
        .isEqualTo(new Refused("local", "action_mismatch"));
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    assertThat(executor.run(d.asString(), body, "refund")).isInstanceOf(Executed.class);
  }
}
