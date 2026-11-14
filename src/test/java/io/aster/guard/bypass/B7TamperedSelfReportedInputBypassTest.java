package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.GuardInputRefPaths;
import io.aster.guard.provenance.TestFactSigner;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.governance.RuleMetadata;
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
import java.time.Instant;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 7 条：高风险档案（{@code eu-ai-act-high-risk}，registry 1.3.0 起要求 verifiedInputs）下篡改自报输入。
 *
 * <p>规则读 {@code req.context.amount}：金额 ≥ 50000 需 DPO 审批，否则直接放行。攻击者把金额「填小」以绕过审批：
 * 未附签名事实 → 来源门 200 DENY {@code unverified_input}（{@code reason} 为 null）、不建待审批、不签执行收据；
 * 附了事实但与 context 不符 / 签后被改 / 签发方未登记 / 已过期 → 400 {@code invalid_fact}（各自的 {@code reason}），
 * 不落决策行。两条正向对照证明门不是一刀切：签发方证实的真实金额照常进入审批或放行并执行一次。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B7TamperedSelfReportedInputBypassTest {
  private static final String TENANT = BypassFixture.tenant("b7");
  private static final String OTHER = BypassFixture.tenant("b7-other");
  static final String MODULE = "guard.bypass.highrisk";
  static final String SOURCE = String.join("\n",
      "Module guard.bypass.highrisk.",
      "Profile \"eu-ai-act-high-risk\".", "",
      "Define GuardContext has amount as Int.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context as GuardContext, agent_model.",
      "",
      "@id(\"BYPASS-HR-001\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  If req.context.amount at least 50000:",
      "    Return Verdict.require_approval(\"Data Protection Officer\", \"large_refund\").",
      "  Otherwise:",
      "    Return Verdict.allow().");

  @Inject BypassFixture api;
  @Inject FactIssuerStore factIssuerStore;
  @Inject PolicyEvaluationService evaluationService;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;
  private Long versionId;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    versionId = api.deploy(TENANT, MODULE, SOURCE);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
  }

  private static String body(long amount, ObjectNode... facts) {
    ObjectNode root = (ObjectNode) ExecutionReceiptAssert.tree(BypassFixture.decisionBody(MODULE, "refund_customer",
        "C-1001", "{\"amount\":" + amount + "}"));
    if (facts.length > 0) {
      var list = ((ObjectNode) root.get("action")).putArray("facts");
      for (ObjectNode f : facts) {
        list.add(f);
      }
    }
    return root.toString();
  }

  private static TestFactSigner amountFact(TestFactSigner signer, long amount) {
    Instant now = Instant.now();
    return signer.audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.amount\":" + amount + "}").sources("{\"context.amount\":\"ledger.amount\"}");
  }

  private TestFactSigner registeredSigner() {
    TestFactSigner signer = TestFactSigner.newKey();
    var saved = factIssuerStore.upsert(TENANT, TestFactSigner.ISSUER, TestFactSigner.KID, signer.publicKey(), true,
        null, BypassFixture.MEMBER_USER, Instant.now());
    assertThat(saved).isInstanceOf(FactIssuerStore.UpsertResult.Saved.class);
    return signer;
  }

  private void assertInvalidFact(String body, String reason) {
    long before = BypassFixture.decisionRows(TENANT);
    Response r = api.decide(MEMBER_KEY, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("invalid_fact");
    assertThat(r.jsonPath().getString("reason")).isEqualTo(reason);
    assertThat(BypassFixture.decisionRows(TENANT)).isEqualTo(before);
    assertThat(ledger.entries()).isEmpty();
  }

  /** 探针：部署路径接受带类型的 context，档案要求 verifiedInputs，规则引用映射后恰为 context.amount。 */
  @Test
  void highRiskModuleInputRefsAreContextAmount() {
    RuleMetadata meta = evaluationService.ruleMetadata(TENANT, MODULE, "decide", String.valueOf(versionId));

    assertThat(meta.profile()).isEqualTo("eu-ai-act-high-risk");
    assertThat(meta.verifiedInputsRequired()).isTrue();
    assertThat(GuardInputRefPaths.guardShape(meta.inputRefs())).containsExactly("context.amount");
  }

  @Test
  void selfReportedSmallAmountWithoutFactIsDenyUnverifiedInput() {
    String body = body(100);
    Response r = api.decide(MEMBER_KEY, body);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(r.jsonPath().getString("reason")).isNull();
    assertThat(r.jsonPath().getMap("approval")).isNull();
    assertThat(r.jsonPath().getMap("executionReceipt")).isNull();
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(BypassFixture.approvalRows(decisionId)).isZero();
    Response receipts = api.auditReceipts(MEMBER_KEY, decisionId);
    assertThat(receipts.statusCode()).as(receipts.asString()).isEqualTo(200);
    assertThat(receipts.jsonPath().getList("receipts[0].metadata.unverifiedFields")).containsExactly("context.amount");

    assertThat(executor.run(r.asString(), body, "refund")).isEqualTo(new Refused("local", "receipt_missing"));
    assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(body), "refund"))
        .isEqualTo(new Refused("consume", "not_allowed"));
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "DENY", "unverified_input");
  }

  /** 事实证实的真实金额 250000、context 填小为 100 → 400 claim_mismatch。 */
  @Test
  void signedFactForRealAmountButSmallerContextIs400ClaimMismatch() {
    TestFactSigner signer = registeredSigner();
    assertInvalidFact(body(100, amountFact(signer, 250000).sign()), "claim_mismatch");
  }

  /** 签发后把 claims.context.amount 改为 100（与 context 一致）→ 签名不再成立，400 bad_signature。 */
  @Test
  void factWithClaimEditedAfterSigningIs400BadSignature() {
    TestFactSigner signer = registeredSigner();
    ObjectNode fact = amountFact(signer, 250000).sign();
    ((ObjectNode) fact.get("claims")).put("context.amount", 100);
    assertInvalidFact(body(100, fact), "bad_signature");
  }

  /** 攻击者自建签发方（未在本租户登记）给「填小」的金额签名 → 400 unknown_issuer。 */
  @Test
  void factFromUnregisteredIssuerIs400UnknownIssuer() {
    registeredSigner();
    TestFactSigner attacker = TestFactSigner.newKey().issuer("attacker.example.com");
    assertInvalidFact(body(100, amountFact(attacker, 100).sign()), "unknown_issuer");
  }

  @Test
  void expiredFactIs400Expired() {
    TestFactSigner signer = registeredSigner();
    Instant now = Instant.now();
    ObjectNode fact = signer.audience(TENANT).window(now.minusSeconds(3600), now.minusSeconds(60))
        .claims("{\"context.amount\":100}").sources("{\"context.amount\":\"ledger.amount\"}").sign();
    assertInvalidFact(body(100, fact), "expired");
  }

  /** 正向对照：事实与 context 都是真实金额 250000 → 通过来源门、按规则进入审批（PENDING），verified 含 context.amount。 */
  @Test
  void verifiedRealAmountPassesGateAndNeedsApproval() {
    TestFactSigner signer = registeredSigner();
    Response r = api.decide(MEMBER_KEY, body(250000, amountFact(signer, 250000).sign()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(r.jsonPath().getString("approval.requiredRole")).isEqualTo(BypassFixture.DPO);
    assertThat(r.jsonPath().getList("inputSources.verified")).contains("context.amount");
  }

  /** 正向对照：签发方证实的小金额 100 → ALLOW → 参考执行方执行恰一次。 */
  @Test
  void verifiedSmallAmountIsAllowAndExecutesOnce() {
    TestFactSigner signer = registeredSigner();
    String body = body(100, amountFact(signer, 100).sign());
    Response r = api.decide(MEMBER_KEY, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    String decisionId = r.jsonPath().getString("decisionId");

    assertThat(executor.run(r.asString(), body, "refund:100")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }
}
