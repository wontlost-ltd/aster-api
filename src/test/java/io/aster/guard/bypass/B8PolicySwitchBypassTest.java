package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.ExpectedPolicy;
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
 * #517：同租户换策略模块为同一动作另取 ALLOW 收据。{@code actionHash} 只覆盖 {@code action}，所以把执行方本应走的
 * 严格策略（恒 DENY）换成同租户另一个宽松的已部署模块，得到的 ALLOW 收据与执行方自算的 actionHash 完全相同。
 * 收据载荷签名覆盖 {@code policyModule / policyFunction}（ADR 0040 §13 R68）：核对期望地址的执行方本地拒绝
 * {@code policy_mismatch}，改写地址键则验签失败；不核对地址的旧执行方仍会放行——这正是客户端须升级的原因。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B8PolicySwitchBypassTest {
  private static final String TENANT = BypassFixture.tenant("b8");
  private static final String OTHER = BypassFixture.tenant("b8-other");
  private static final ExpectedPolicy STRICT = new ExpectedPolicy(BypassFixture.DENY_MODULE, "decide");
  private static final ExpectedPolicy LENIENT = new ExpectedPolicy(DeployedMapParamProbeTest.MODULE, "decide");

  @Inject BypassFixture api;
  private SideEffectLedger ledger;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
  }

  private ReferenceExecutor executor(ExpectedPolicy expected) {
    return new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC(), expected);
  }

  /** 执行方期望的严格策略给 DENY；同一动作换到宽松模块拿到 ALLOW，actionHash 与严格请求相同。 */
  private Response switchedAllow(String strictBody, String switchedBody) {
    Response strict = api.decide(MEMBER_KEY, strictBody);
    assertThat(strict.statusCode()).as(strict.asString()).isEqualTo(200);
    assertThat(strict.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(strict.asString()).doesNotContain("executionReceipt");

    Response switched = api.decide(MEMBER_KEY, switchedBody);
    assertThat(switched.statusCode()).as(switched.asString()).isEqualTo(200);
    assertThat(switched.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    ObjectNode payload = (ObjectNode) ExecutionReceiptAssert.tree(switched.asString())
        .get("executionReceipt").get("payload");
    assertThat(payload.get("actionHash").asText()).isEqualTo(ExecutionReceiptAssert.actionHashOf(strictBody));
    assertThat(payload.get("policyModule").asText()).isEqualTo(LENIENT.module());
    assertThat(payload.get("policyFunction").asText()).isEqualTo(LENIENT.function());
    assertThat(payload.get("v").asInt()).isEqualTo(1);
    return switched;
  }

  @Test
  void receiptFromSwitchedModuleIsRefusedByBoundExecutor() {
    String strictBody = BypassFixture.denyBody(250000);
    Response switched = switchedAllow(strictBody, BypassFixture.allowBody(250000));
    String decisionId = switched.jsonPath().getString("decisionId");

    // 不核对地址的执行方（0.2.0 直连核对）本地全部通过：这是 #517 的缺口，不在这里执行以免烧掉收据
    assertThat(executor(null).verifyLocally(ExecutionReceiptAssert.tree(switched.asString()), strictBody)).isEmpty();

    assertThat(executor(STRICT).run(switched.asString(), strictBody, "switched-module"))
        .isEqualTo(new Refused("local", "policy_mismatch"));
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
  }

  /** 把载荷的地址键改写成执行方期望的模块：地址受签名覆盖，验签失败。 */
  @Test
  void rewritingSignedPolicyAddressFailsSignature() {
    String strictBody = BypassFixture.denyBody(250000);
    Response switched = switchedAllow(strictBody, BypassFixture.allowBody(250000));
    String decisionId = switched.jsonPath().getString("decisionId");
    ObjectNode forged = (ObjectNode) ExecutionReceiptAssert.tree(switched.asString());
    ((ObjectNode) forged.get("executionReceipt").get("payload")).put("policyModule", STRICT.module());

    assertThat(executor(STRICT).run(forged.toString(), strictBody, "forged-address"))
        .isEqualTo(new Refused("local", "bad_signature"));
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
  }

  /** 正向对照：出自期望模块的收据照常执行恰一次——地址核对不误伤诚实路径。 */
  @Test
  void receiptFromExpectedModuleExecutes() {
    String body = BypassFixture.allowBody(250000);
    Response allow = api.decide(MEMBER_KEY, body);
    assertThat(allow.statusCode()).as(allow.asString()).isEqualTo(200);
    String decisionId = allow.jsonPath().getString("decisionId");

    assertThat(executor(LENIENT).run(allow.asString(), body, "honest")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }
}
