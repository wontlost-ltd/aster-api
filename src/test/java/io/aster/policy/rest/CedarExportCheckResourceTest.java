package io.aster.policy.rest;

import static io.aster.policy.rest.CedarExportResourceTest.BEARER_KEY;
import static io.aster.policy.rest.CedarExportResourceTest.BEARER_TENANT;
import static io.aster.policy.rest.CedarExportResourceTest.GUARD_MODULE;
import static io.aster.policy.rest.CedarExportResourceTest.HMAC_KEY;
import static io.aster.policy.rest.CedarExportResourceTest.SAMPLE;
import static io.aster.policy.rest.CedarExportResourceTest.exportEventCount;
import static io.aster.policy.rest.CedarExportResourceTest.json;
import static io.aster.policy.rest.CedarExportResourceTest.structuralBody;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * aster-cloud#786：可导出检查端点与导出端点同一鉴权、入参约束与租户隔离；
 * 但它不写 POLICY_EXPORTED 审计、不占导出并发闸，供详情页加载时决定是否显示「导出为 Cedar」。
 */
@QuarkusTest
@TestProfile(CedarExportResourceTest.AuthProfile.class)
class CedarExportCheckResourceTest {

  static final String CHECK_PATH = "/api/v1/policies/export/cedar/check";
  static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  static final String TENANT = "tenant-cedar-check-" + RUN;
  static final String TENANT_A = "tenant-a-cedar-check";
  static final String TENANT_B = "tenant-b-cedar-check";

  @Inject ApiKeyVerifierService verifier;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;
  @Inject CedarExportGate gate;

  @BeforeEach
  void setUp() {
    QuarkusTransaction.requiringNew().run(() -> {
      for (String t : List.of(TENANT_A, TENANT_B)) {
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", t);
        PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", t);
        PolicyVersion.delete("tenantId = ?1", t);
        PolicyCatalog.delete("tenantId = ?1", t);
      }
    });
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-cedar-1", "user-cedar", BEARER_TENANT, "pro", "active", "MEMBER"));
  }

  @Test
  void anonymousIsRejected() {
    given().contentType(ContentType.JSON).body(Map.of("source", SAMPLE))
        .post(CHECK_PATH).then().statusCode(401);
  }

  @Test
  void tenantHeaderAloneIsRejected() {
    given().header("X-Tenant-Id", TENANT).header("X-User-Role", "MEMBER").contentType(ContentType.JSON)
        .body(Map.of("source", SAMPLE)).post(CHECK_PATH).then().statusCode(401);
  }

  @Test
  void hmacCheckIsExportableAndWritesNoAudit() throws Exception {
    String tenant = "tenant-cedar-check-hmac-" + RUN;
    String body = json(Map.of("source", SAMPLE, "locale", "en-US",
        "claimedRef", Map.of("cloudPolicyId", "pol-1", "version", "3")));
    var resp = signedAs(tenant, body).post(CHECK_PATH);
    resp.then().statusCode(200)
        .body("exportable", equalTo(true))
        .body("rules", contains("decide"))
        .body("diagnostics", empty())
        .body("sourceHash", matchesPattern("[0-9a-f]{64}"))
        .body("scopesHash", equalTo(""));
    // 响应只有约定的五个字段，不带策略文本或 schema
    assertThat(resp.jsonPath().getMap("$").keySet())
        .containsExactlyInAnyOrder("exportable", "rules", "diagnostics", "sourceHash", "scopesHash");
    assertThat(exportEventCount(tenant)).isZero();
  }

  @Test
  void bearerApiKeyChannelChecks() throws Exception {
    given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON)
        .body(json(Map.of("source", SAMPLE))).post(CHECK_PATH)
        .then().statusCode(200).body("exportable", equalTo(true)).body("rules", contains("decide"));
    assertThat(exportEventCount(BEARER_TENANT)).isZero();
  }

  @Test
  void hmacViewerRoleIsForbidden() throws Exception {
    String body = json(Map.of("source", SAMPLE));
    given().headers(InternalCallerSigning.headers(HMAC_KEY, "POST", CHECK_PATH, "", body, TENANT, "VIEWER", "u-1",
            List.of()))
        .contentType(ContentType.JSON).body(body).post(CHECK_PATH).then().statusCode(403);
  }

  @Test
  void notExportableReturns200WithDiagnostics() throws Exception {
    String src = GUARD_MODULE + "@id(\"R-X\")\nRule decide given req as GuardRequest, produce Verdict:\n"
        + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
    signed(json(Map.of("source", src, "locale", "en-US"))).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(false))
        .body("rules", empty())
        .body("diagnostics.code", hasItem("E721"));
  }

  @Test
  void checkDoesNotTakeTheExportGate() throws Exception {
    int held = 0;
    while (gate.tryAcquire()) {
      held++;
    }
    try {
      assertThat(held).isPositive();
      signed(json(Map.of("source", SAMPLE))).post(CHECK_PATH).then().statusCode(200)
          .body("exportable", equalTo(true));
    } finally {
      for (int i = 0; i < held; i++) {
        gate.release();
      }
    }
  }

  @Test
  void validationMatchesTheExport() throws Exception {
    signed(json(Map.of("source", SAMPLE, "versionId", 1))).post(CHECK_PATH).then().statusCode(400)
        .body("error", equalTo("exactly one of source or versionId"));
    signed(json(Map.of("locale", "en-US"))).post(CHECK_PATH).then().statusCode(400)
        .body("error", equalTo("exactly one of source or versionId"));
    String huge = "x".repeat(io.aster.policy.rest.model.CnlSourceLimits.MAX_ANON_SOURCE_LENGTH + 1);
    signed(json(Map.of("source", huge))).post(CHECK_PATH).then().statusCode(400)
        .body("error", equalTo("source too large"));
    signed(json(Map.of("source", SAMPLE, "claimedRef", Map.of("version", "3\u0001")))).post(CHECK_PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef invalid"));
    Map<String, Object> vocab = new LinkedHashMap<>(io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY);
    vocab.put("fields", java.util.Collections.nCopies(
        io.aster.policy.rest.model.VocabularyBounds.MAX_ENTRIES_PER_SECTION + 1, 1));
    signed(json(Map.of("source", SAMPLE, "vocabulary", vocab))).post(CHECK_PATH).then().statusCode(400)
        .body("error", equalTo(io.aster.policy.rest.model.VocabularyBounds.ERROR_CODE));
  }

  @Test
  void hmacGermanSourceWithVocabularyCarriesScopesHash() throws Exception {
    String body = json(Map.of("source", io.aster.policy.parser.GermanReproductions.GUARD_SOURCE,
        "locale", "de-DE", "vocabulary", io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY));
    String expected = io.aster.policy.parser.CompileIdentity.scopesHash(
        io.aster.replay.core.ReplayExecutionCore.buildVocabularyScopes(
            io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY));
    signed(body).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(true)).body("rules", contains("decide"))
        .body("scopesHash", equalTo(expected));
  }

  // ---------- 结构词别名信任：与导出同一口径 ----------

  @Test
  void hmacCallWithAllowStructuralAcceptsStructuralAlias() throws Exception {
    signed(json(structuralBody(true))).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(true));
    signed(json(structuralBody(null))).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(false));
  }

  @Test
  void bearerCallerAllowStructuralIsIgnored() throws Exception {
    given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON)
        .body(json(structuralBody(true))).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(false));
  }

  @Test
  void versionIdIsTenantScopedAndWritesNoAudit() throws Exception {
    Long versionId = DeployedMapParamProbeTest.deploy(TENANT_A, "guard.cedar.sample", "decide", SAMPLE);
    String body = json(Map.of("versionId", versionId));
    signedAs(TENANT_B, body).post(CHECK_PATH).then().statusCode(404).body("error", equalTo("version not found"));
    signedAs(TENANT_A, body).post(CHECK_PATH).then().statusCode(200)
        .body("exportable", equalTo(true)).body("rules", contains("decide"));
    assertThat(exportEventCount(TENANT_A)).isZero();
  }

  // ---------- 辅助 ----------

  static RequestSpecification signed(String body) {
    return signedAs(TENANT, body);
  }

  static RequestSpecification signedAs(String tenant, String body) {
    return given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", CHECK_PATH, "", body, tenant, "MEMBER", "u-1",
            List.of()))
        .contentType(ContentType.JSON).body(body);
  }
}
