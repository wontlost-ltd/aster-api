package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.DeployedMapParamProbeTest;
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
import java.util.Set;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 3 条：拿到 ALLOW 收据后改参数再执行。收据绑定 {@code action} 子树的 jcs-sha256/v1 哈希：
 * 参考执行方本地自算不符即拒；跳过本地检查直接 consume → 409，体恰为 {@code {"error":"action_mismatch"}}（不回显哈希）。
 * 每例最后以正确哈希诚实执行仍成功：409 不烧收据。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B3TamperedParamsBypassTest {
  private static final String TENANT = BypassFixture.tenant("b3");
  private static final String OTHER = BypassFixture.tenant("b3-other");

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

  private Response allow(String body) {
    Response r = api.decide(MEMBER_KEY, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return r;
  }

  /** 篡改后的哈希 consume → 409 action_mismatch；收据未消耗、无副作用；随后诚实执行仍成功。 */
  private void assertTamperRejectedThenHonestExecutes(Response decision, String body, String tamperedBody) {
    String decisionId = decision.jsonPath().getString("decisionId");
    assertThat(executor.run(decision.asString(), tamperedBody, "tampered"))
        .isEqualTo(new Refused("local", "action_mismatch"));
    assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(tamperedBody), "tampered"))
        .isEqualTo(new Refused("consume", "action_mismatch"));
    assertThat(executor.lastConsume().asString()).isEqualTo("{\"error\":\"action_mismatch\"}");
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);

    assertThat(executor.run(decision.asString(), body, "honest")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  @Test
  void changedAmountIs409ActionMismatch() {
    String body = BypassFixture.allowBody(250000);
    assertTamperRejectedThenHonestExecutes(allow(body), body, BypassFixture.allowBody(1));
  }

  @Test
  void changedTargetResourceIs409ActionMismatch() {
    String body = BypassFixture.allowBody(250000, "C-1001");
    assertTamperRejectedThenHonestExecutes(allow(body), body, BypassFixture.allowBody(250000, "C-9999"));
  }

  /**
   * ADR 0054 §3.2 不变式 2：{@code context} 中加 {@code "cc": null}、{@code "cc": ""} 与缺省是三个不同的动作——
   * 三个哈希两两不等；收据按「缺省」签发时，另外两种 consume 均 409。
   */
  @Test
  void addedNullAndEmptyStringAreDistinctHashes() {
    String absent = context("{\"amount\":250000}");
    String nullCc = context("{\"amount\":250000,\"cc\":null}");
    String emptyCc = context("{\"amount\":250000,\"cc\":\"\"}");
    Set<String> hashes = Set.of(ExecutionReceiptAssert.actionHashOf(absent), ExecutionReceiptAssert.actionHashOf(nullCc),
        ExecutionReceiptAssert.actionHashOf(emptyCc));
    assertThat(hashes).hasSize(3);

    Response decision = allow(absent);
    String decisionId = decision.jsonPath().getString("decisionId");
    for (String tampered : new String[]{nullCc, emptyCc}) {
      assertThat(executor.run(decision.asString(), tampered, "cc"))
          .isEqualTo(new Refused("local", "action_mismatch"));
      assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(tampered), "cc"))
          .isEqualTo(new Refused("consume", "action_mismatch"));
    }
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    assertThat(executor.run(decision.asString(), absent, "honest")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  /**
   * 收据挪用（Review Focus 4）：同租户两条 ALLOW（X、Y 动作不同）。X 的收据配 Y 的决策响应 → 本地 decision_mismatch；
   * 跳过本地检查用 X 的哈希 consume Y → 409 action_mismatch。两条收据随后各自仍可被诚实执行一次。
   */
  @Test
  void receiptFromOtherDecisionIsRejected() {
    String bodyX = BypassFixture.allowBody(100, "C-1");
    String bodyY = BypassFixture.allowBody(200, "C-2");
    Response x = allow(bodyX);
    Response y = allow(bodyY);
    String yId = y.jsonPath().getString("decisionId");
    ObjectNode grafted = (ObjectNode) ExecutionReceiptAssert.tree(y.asString());
    grafted.set("executionReceipt", ExecutionReceiptAssert.tree(x.asString()).get("executionReceipt"));

    assertThat(executor.run(grafted.toString(), bodyX, "misuse")).isEqualTo(new Refused("local", "decision_mismatch"));
    assertThat(executor.run(grafted.toString(), bodyY, "misuse")).isEqualTo(new Refused("local", "decision_mismatch"));
    assertThat(executor.attackConsume(yId, ExecutionReceiptAssert.actionHashOf(bodyX), "misuse"))
        .isEqualTo(new Refused("consume", "action_mismatch"));
    assertThat(executor.lastConsume().asString()).isEqualTo("{\"error\":\"action_mismatch\"}");
    BypassAssertions.noSideEffect(ledger, yId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, yId);

    assertThat(executor.run(y.asString(), bodyY, "honest-y")).isInstanceOf(Executed.class);
    assertThat(executor.run(x.asString(), bodyX, "honest-x")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, yId, ledger);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, x.jsonPath().getString("decisionId"), ledger);
  }

  private static String context(String contextJson) {
    return BypassFixture.decisionBody(DeployedMapParamProbeTest.MODULE, "read_customer_record", "C-1001", contextJson);
  }
}
