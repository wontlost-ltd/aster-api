package io.aster.policy.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.api.GuardFromEvidenceResourceTest;
import io.aster.guard.snapshot.GuardEvaluationClassificationEntity;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.PiiMark;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0042 §12 D1 / D2：可审批的 evaluate-source 评估留下分类行（输入哈希 + 执行 IR 推导的标记）；
 * ALLOW 与 simulate 不留。与 {@link GuardFromEvidenceResourceTest} 共用同一测试档案，避免重复启动。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class EvaluateSourceClassificationTest {
  private static final String TENANT = "tenant-eval-cls-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String PATH = "/api/v1/policies/evaluate-source";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOURCE = String.join("\n",
      "Module eval.cls460.", "",
      "Define Applicant has amount as Int, email as @pii(L2, email) Text.", "",
      "Rule decide given applicant as Applicant, produce Verdict:",
      "  If applicant.amount greater than 10:",
      "    Return Verdict.require_approval(\"Credit Officer\", \"large amount\").",
      "  Return Verdict.allow().", "");

  private static Response evaluate(int amount, boolean simulate) {
    String body = "{\"source\":" + JSON.valueToTree(SOURCE) + ",\"context\":{\"applicant\":{\"amount\":" + amount
        + ",\"email\":\"a@b.cc\"}},\"locale\":\"en-US\",\"functionName\":\"decide\"}";
    String query = simulate ? "simulate=true" : "";
    var headers = InternalCallerSigning.headers(GuardFromEvidenceResourceTest.HMAC_KEY_FOR_TESTS, "POST", PATH, query,
        body, TENANT, "member", "u-eval", List.of());
    return given().headers(headers).contentType("application/json").body(body)
        .post(simulate ? PATH + "?" + query : PATH);
  }

  private static java.util.Optional<GuardEvaluationClassificationEntity> row(String cid) {
    return QuarkusTransaction.requiringNew().call(() -> GuardEvaluationClassificationEntity.findByKey(TENANT, cid));
  }

  @Test
  void requireApprovalEvaluationLeavesClassificationRow() {
    Response r = evaluate(20, false);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    String cid = r.jsonPath().getString("evidence.correlationId");
    GuardEvaluationClassificationEntity row = row(cid).orElseThrow();
    assertThat(row.inputHash).hasSize(64);
    InputClassification c = InputClassification.fromJson(row.classification);
    assertThat(c.declared()).containsExactly("context.applicant.amount", "context.applicant.email");
    assertThat(c.marks()).containsExactly(new PiiMark("context.applicant.email", "L2", "email", "ir"));
  }

  @Test
  void allowEvaluationLeavesNoRow() {
    Response r = evaluate(1, false);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(row(r.jsonPath().getString("evidence.correlationId"))).isEmpty();
  }
}
