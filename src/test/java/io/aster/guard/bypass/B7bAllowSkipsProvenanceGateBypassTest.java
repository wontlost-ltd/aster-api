package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.bypass.ReferenceExecutor.Executed;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.TestFactSigner;
import io.aster.policy.entity.AuditLog;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.util.List;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static io.aster.guard.bypass.BypassFixture.MEMBER_USER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 7 条的 ALLOW 变体（aster-api#510 裁定 (a)，ADR 0046 §11.8）：高风险档案下把自报金额「填小」，
 * 让规则在评估阶段直接得出 ALLOW，试图不经来源门拿到执行授权。
 *
 * <p>走 cloud 的真实路径：HMAC {@code evaluate-source} 得评估事件 → HMAC {@code from-evidence} 登记。未附签名事实
 * → DENY {@code unverified_input}、无执行收据，参考执行方拒绝执行、攻击者直接 consume 得 409；签发方证实的同一小金额
 * → ALLOW 并带执行收据，执行恰一次；档案不要求 verifiedInputs 的 ALLOW 照旧 409 {@code evidence_not_approvable}。
 * 评估端点本身的 ALLOW 不是授权：它没有决策、没有收据，consume 无从谈起（ADR 0046 §11.8 第 6 条）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B7bAllowSkipsProvenanceGateBypassTest {
  private static final String TENANT = BypassFixture.tenant("b7b");
  private static final String OTHER = BypassFixture.tenant("b7b-other");
  private static final String EVALUATE_SOURCE = "/api/v1/policies/evaluate-source";
  private static final String FROM_EVIDENCE = "/api/v1/guard/decisions/from-evidence";
  private static final String SMALL = "{\"applicant\":{\"amount\":100}}";

  private static String source(String profile) {
    return String.join("\n",
        "Module guard.bypass.allow510.", "",
        "Profile \"" + profile + "\".", "",
        "Define Applicant has amount as Int.", "",
        "@id(\"BYPASS-HR-510\")",
        "@control(\"EU_AI_ACT:ART14\")",
        "Rule decide given applicant as Applicant, produce Verdict:",
        "  If applicant.amount at least 50000:",
        "    Return Verdict.require_approval(\"Data Protection Officer\", \"large_refund\").",
        "  Otherwise:",
        "    Return Verdict.allow().", "");
  }

  @Inject BypassFixture api;
  @Inject FactIssuerStore factIssuerStore;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
  }

  private static Response hmacPost(String path, String body) {
    return given()
        .headers(InternalCallerSigning.headers(BypassProfile.HMAC_KEY, "POST", path, "", body, TENANT, "member",
            MEMBER_USER, List.of()))
        .contentType("application/json")
        .body(body)
        .post(path);
  }

  /** 以 cloud 的形态评估即时源码，返回证据 correlationId（等评估事件入链）。 */
  private static String evaluate(String profile) {
    String body = "{\"source\":" + TextNode.valueOf(source(profile)) + ",\"context\":" + SMALL
        + ",\"locale\":\"en-US\",\"functionName\":\"decide\"}";
    Response r = hmacPost(EVALUATE_SOURCE, body);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("decision.outcome")).as(r.asString()).isEqualTo("ALLOW");
    String cid = r.jsonPath().getString("evidence.correlationId");
    long deadline = System.currentTimeMillis() + 15_000;
    while (QuarkusTransaction.requiringNew().call(() -> AuditLog.findByCorrelationIds(TENANT, List.of(cid))).isEmpty()) {
      assertThat(System.currentTimeMillis()).as("评估事件未在 15s 内入链").isLessThan(deadline);
      Thread.onSpinWait();
    }
    return cid;
  }

  private static String fromEvidenceBody(String cid, ObjectNode... facts) {
    ObjectNode root = (ObjectNode) ExecutionReceiptAssert.tree("{\"correlationId\":\"" + cid + "\",\"action\":{"
        + "\"principal\":{\"id\":\"" + MEMBER_USER + "\",\"type\":\"user\"},\"action\":{\"name\":\"refund_customer\"},"
        + "\"resource\":{\"type\":\"customer\",\"id\":\"C-1001\"},\"context\":" + SMALL + "}}");
    if (facts.length > 0) {
      var list = ((ObjectNode) root.get("action")).putArray("facts");
      for (ObjectNode f : facts) {
        list.add(f);
      }
    }
    return root.toString();
  }

  private TestFactSigner registeredSigner() {
    TestFactSigner signer = TestFactSigner.newKey();
    var saved = factIssuerStore.upsert(TENANT, TestFactSigner.ISSUER, TestFactSigner.KID, signer.publicKey(), true,
        null, MEMBER_USER, Instant.now());
    assertThat(saved).isInstanceOf(FactIssuerStore.UpsertResult.Saved.class);
    return signer;
  }

  private static ObjectNode smallAmountFact(TestFactSigner signer) {
    Instant now = Instant.now();
    return signer.audience(TENANT).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.applicant.amount\":100}").sources("{\"context.applicant.amount\":\"ledger.amount\"}")
        .sign();
  }

  @Test
  void selfReportedSmallAmountAllowWithoutFactIsDenyUnverifiedInput() {
    String cid = evaluate("eu-ai-act-high-risk");
    String body = fromEvidenceBody(cid);

    Response r = hmacPost(FROM_EVIDENCE, body);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo("unverified_input");
    assertThat(r.jsonPath().getMap("executionReceipt")).isNull();
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(BypassFixture.approvalRows(decisionId)).isZero();
    assertThat(executor.run(r.asString(), body, "refund")).isEqualTo(new Refused("local", "receipt_missing"));
    assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(body), "refund"))
        .isEqualTo(new Refused("consume", "not_allowed"));
    BypassAssertions.noSideEffect(ledger, decisionId);
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "DENY", "unverified_input");
  }

  /** 正向对照：签发方证实的同一小金额 → ALLOW 带执行收据 → 参考执行方执行恰一次。 */
  @Test
  void verifiedSmallAmountAllowFromEvidenceExecutesOnce() {
    TestFactSigner signer = registeredSigner();
    String cid = evaluate("eu-ai-act-high-risk");
    String body = fromEvidenceBody(cid, smallAmountFact(signer));

    Response r = hmacPost(FROM_EVIDENCE, body);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    String decisionId = r.jsonPath().getString("decisionId");
    assertThat(executor.run(r.asString(), body, "refund:100")).isInstanceOf(Executed.class);
    BypassAssertions.consumedExactlyOnce(api, MEMBER_KEY, decisionId, ledger);
  }

  /** 门不是一刀切：档案不要求 verifiedInputs 的 ALLOW 不受理，照旧 409，不落决策行。 */
  @Test
  void allowUnderNonRequiringProfileIsNotRegistered() {
    String cid = evaluate("governed");
    long before = BypassFixture.decisionRows(TENANT);

    Response r = hmacPost(FROM_EVIDENCE, fromEvidenceBody(cid));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("evidence_not_approvable");
    assertThat(BypassFixture.decisionRows(TENANT)).isEqualTo(before);
  }
}
