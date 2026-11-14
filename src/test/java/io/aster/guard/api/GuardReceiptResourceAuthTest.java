package io.aster.guard.api;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * consume 的鉴权双通道（Bearer 与 HMAC 内部调用者，ADR 0040 §12 D5）与 kid 紧急撤销（D6）。
 *
 * <p>独立 profile：apikey 鉴权与 HMAC 同时打开，并把测试密钥 {@code test-b} 标为 {@code revoked}。
 * {@code revision} 保持与默认测试配置相同的 1——启动校验会拒绝低于已持久化最大值的 revision，
 * 若这里写 2，之后按默认配置启动的测试类都会启动失败。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardReceiptResourceAuthTest.AuthProfile.class)
public class GuardReceiptResourceAuthTest {
  public static class AuthProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "aster.plan-gate.hmac-key", HMAC_KEY,
          "aster.security.apikey.enabled", "true",
          "aster.security.signature.enabled", "false",
          "aster.security.evaluate-source.public", "false",
          "aster.security.evaluate-source.trial.enabled", "false",
          "aster.guard.receipt.signing-keys", "{\"revision\":1,\"keys\":["
              + "{\"kid\":\"test-a\",\"privateKey\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE\"},"
              + "{\"kid\":\"test-b\",\"privateKey\":\"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\",\"revoked\":true}]}",
          "aster.guard.receipt.active-kid", "test-a");
    }
  }

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-consume-auth-" + RUN;
  private static final String BEARER_KEY = "ak_test_guard_consume_member";
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";

  @Inject ApiKeyVerifierService verifier;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void setUp() {
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-gc-1", "user-gc", TENANT, "pro", "active", "MEMBER"));
    QuarkusTransaction.requiringNew().run(() -> {
      io.aster.guard.snapshot.GuardDecisionInputEntity.delete("tenantId = ?1", TENANT);
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

  private String allowDecision(String body) {
    Response r = given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON).body(body)
        .when().post("/api/v1/guard/decisions");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    return r.jsonPath().getString("decisionId");
  }

  private static Response bearerConsume(String decisionId, String body) {
    return given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON).body(body)
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");
  }

  private static Response hmacConsume(String decisionId, String body) {
    String path = "/api/v1/guard/receipts/" + decisionId + "/consume";
    return given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", body, TENANT, "member", "u-cloud", List.of()))
        .contentType("application/json").body(body).post(path);
  }

  @Test
  void hmacInternalCallerCanConsume() {
    String body = GuardReceiptResourceTest.allowBody(250000);
    String decisionId = allowDecision(body);
    String consume = GuardReceiptResourceTest.consumeBody(ExecutionReceiptAssert.actionHashOf(body),
        UUID.randomUUID().toString(), "aster-cloud-exec");

    Response r = hmacConsume(decisionId, consume);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("decisionId")).isEqualTo(decisionId);
    assertThat(GuardReceiptResourceTest.events(decisionId, "ACTION_GUARD_CONSUME_ATTEMPT"))
        .singleElement().satisfies(e -> assertThat(e.performedBy).isEqualTo("u-cloud"));
  }

  /** HMAC 双通道同样覆盖结果回写（cloud 执行路径 consume → 执行 → 回写）。 */
  @Test
  void hmacInternalCallerCanReportResult() {
    String body = GuardReceiptResourceTest.allowBody(250000);
    String decisionId = allowDecision(body);
    String attemptId = UUID.randomUUID().toString();
    assertThat(hmacConsume(decisionId, GuardReceiptResourceTest.consumeBody(ExecutionReceiptAssert.actionHashOf(body),
        attemptId, "aster-cloud-exec")).statusCode()).isEqualTo(200);
    String result = GuardReceiptResourceTest.resultBody(attemptId, "aster-cloud-exec", "executed");
    String path = "/api/v1/guard/receipts/" + decisionId + "/result";

    Response anonymous = given().contentType(ContentType.JSON).body(result).when().post(path);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    Response r = given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", result, TENANT, "member", "u-cloud", List.of()))
        .contentType("application/json").body(result).post(path);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(GuardReceiptResourceTest.events(decisionId, "ACTION_GUARD_RESULT"))
        .singleElement().satisfies(e -> assertThat(e.performedBy).isEqualTo("u-cloud"));
  }

  @Test
  void bearerCanConsumeAndUnauthenticatedIs401() {
    String body = GuardReceiptResourceTest.allowBody(250000);
    String decisionId = allowDecision(body);
    String consume = GuardReceiptResourceTest.consumeBody(ExecutionReceiptAssert.actionHashOf(body),
        UUID.randomUUID().toString(), "sdk-exec");

    Response anonymous = given().contentType(ContentType.JSON).body(consume)
        .when().post("/api/v1/guard/receipts/" + decisionId + "/consume");
    assertThat(anonymous.statusCode()).isEqualTo(401);

    Response r = bearerConsume(decisionId, consume);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
  }

  /** 收据行的 kid 是配置中 {@code revoked: true} 的 key → 409 kid_revoked，带 kid 与当前 revision，行不消耗。 */
  @Test
  void revokedKidIs409WithRevision() {
    String body = GuardReceiptResourceTest.allowBody(250000);
    String decisionId = allowDecision(body);
    UUID id = UUID.fromString(decisionId);
    QuarkusTransaction.requiringNew().run(() -> GuardReceiptEntity.update("kid = 'test-b' where decisionId = ?1", id));

    Response r = bearerConsume(decisionId, GuardReceiptResourceTest.consumeBody(
        ExecutionReceiptAssert.actionHashOf(body), UUID.randomUUID().toString(), "sdk-exec"));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.asString()).isEqualTo("{\"error\":\"kid_revoked\",\"kid\":\"test-b\",\"revision\":1}");
    assertThat(QuarkusTransaction.requiringNew().call(
        () -> GuardReceiptEntity.<GuardReceiptEntity>findById(id).consumedAt)).isNull();
  }
}
