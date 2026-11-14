package io.aster.guard.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0054 §11 D1：{@code PUT / DELETE /api/v1/guard/policy-deployments/{cloudPolicyId}} 只接受内部 HMAC 通道；
 * 部署后同租户 API key 的 {@code POST /guard/decisions} 解析到部署的版本，撤回后 404 {@code policy_not_found}。
 * 复用 {@link GuardFromEvidenceResourceTest.AuthProfile}（apikey 鉴权与 HMAC 同时打开）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class GuardPolicyDeploymentResourceTest {
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-deploy-rest-" + RUN;
  private static final String MODULE = "guard.deployrest.r" + RUN;
  private static final String BASE = "/api/v1/guard/policy-deployments";
  private static final String BEARER_KEY = "ak_test_guard_deploy_member";
  private static final String HMAC_KEY = GuardFromEvidenceResourceTest.HMAC_KEY_FOR_TESTS;
  private static final String SOURCE = String.join("\n", "Module " + MODULE + ".", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.",
      "", "@id(\"DEPLOY-REST-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  Return Verdict.allow().");

  @Inject ApiKeyVerifierService verifier;

  @BeforeEach
  void setUp() {
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-gd-1", "user-gd", TENANT, "pro", "active", "MEMBER"));
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
      PolicyVersion.delete("tenantId = ?1", TENANT);
      PolicyCatalog.delete("tenantId = ?1", TENANT);
    });
  }

  private static String sha256(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  private static String putBody(long generation, String cloudVersionId) throws Exception {
    String escaped = SOURCE.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "{\"generation\":" + generation + ",\"cloudVersionId\":\"" + cloudVersionId + "\",\"cloudVersion\":1,"
        + "\"moduleName\":\"" + MODULE + "\",\"functionName\":\"decide\",\"locale\":\"en-US\","
        + "\"source\":\"" + escaped + "\",\"sourceSha256\":\"" + sha256(SOURCE) + "\"}";
  }

  private static Response signed(String method, String path, String query, String body, String userId) {
    var req = given().headers(InternalCallerSigning.headers(HMAC_KEY, method, path, query, body, TENANT, "member",
        userId, List.of()));
    if (!body.isEmpty()) {
      req = req.contentType("application/json").body(body);
    }
    return req.request(method, query.isEmpty() ? path : path + "?" + query);
  }

  private static Response decide() {
    String body = "{\"policyModule\":\"" + MODULE + "\",\"policyFunction\":\"decide\",\"action\":{"
        + "\"principal\":{\"id\":\"agent-1\"},\"action\":{\"name\":\"lookup_payment\"},\"context\":{\"p\":null}}}";
    return given().header("Authorization", "Bearer " + BEARER_KEY).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body(body).post("/api/v1/guard/decisions");
  }

  @Test
  void putDeploysAndDecisionsResolve() throws Exception {
    Response put = signed("PUT", BASE + "/cp-1", "", putBody(1, "cv-1"), "u-cloud-admin");
    assertThat(put.statusCode()).as(put.asString()).isEqualTo(200);
    assertThat(put.jsonPath().getBoolean("created")).isTrue();
    assertThat(put.jsonPath().getString("cloudPolicyId")).isEqualTo("cp-1");
    assertThat(put.jsonPath().getString("moduleName")).isEqualTo(MODULE);
    long versionId = put.jsonPath().getLong("policyVersionId");

    Response decision = decide();
    assertThat(decision.statusCode()).as(decision.asString()).isEqualTo(200);
    assertThat(decision.jsonPath().getLong("policyVersion.id")).isEqualTo(versionId);

    Response replay = signed("PUT", BASE + "/cp-1", "", putBody(1, "cv-1"), "u-cloud-admin");
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.jsonPath().getBoolean("created")).isFalse();
  }

  @Test
  void bearerIs403InternalOnly() throws Exception {
    List<Response> responses = List.of(
        given().header("Authorization", "Bearer " + BEARER_KEY).contentType("application/json")
            .body(putBody(1, "cv-1")).put(BASE + "/cp-1"),
        given().header("Authorization", "Bearer " + BEARER_KEY).delete(BASE + "/cp-1?generation=1"),
        given().contentType("application/json").body(putBody(1, "cv-1")).put(BASE + "/cp-1"));
    for (Response r : responses) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
      assertThat(r.jsonPath().getString("reason")).isEqualTo("internal_only");
    }
  }

  @Test
  void unsignedUserIs400() throws Exception {
    Response r = signed("PUT", BASE + "/cp-1", "", putBody(1, "cv-1"), "");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("user_required");
  }

  @Test
  void duplicateKeysIs400InvalidDeployment() throws Exception {
    String body = putBody(1, "cv-1").replaceFirst("\\{", "{\"generation\":9,");
    Response r = signed("PUT", BASE + "/cp-1", "", body, "u-cloud-admin");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("invalid_deployment");
  }

  @Test
  void deleteThenDecisionsIs404() throws Exception {
    assertThat(signed("PUT", BASE + "/cp-1", "", putBody(1, "cv-1"), "u-cloud-admin").statusCode()).isEqualTo(200);
    Response stale = signed("DELETE", BASE + "/cp-1", "generation=0", "", "u-cloud-admin");
    assertThat(stale.statusCode()).as(stale.asString()).isEqualTo(400);
    Response deleted = signed("DELETE", BASE + "/cp-1", "generation=2", "", "u-cloud-admin");
    assertThat(deleted.statusCode()).as(deleted.asString()).isEqualTo(204);
    Response decision = decide();
    assertThat(decision.statusCode()).isEqualTo(404);
    assertThat(decision.jsonPath().getString("error")).isEqualTo("policy_not_found");
    Response older = signed("PUT", BASE + "/cp-1", "", putBody(1, "cv-1"), "u-cloud-admin");
    assertThat(older.statusCode()).isEqualTo(409);
    assertThat(older.jsonPath().getString("error")).isEqualTo("stale_generation");
    Response unknown = signed("DELETE", BASE + "/cp-unknown", "generation=1", "", "u-cloud-admin");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.asString()).isEmpty();
  }
}
