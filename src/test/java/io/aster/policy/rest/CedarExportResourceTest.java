package io.aster.policy.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.common.JacksonMappers;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * ADR 0047 §6 端到端：Cedar 导出端点的双通道鉴权（HMAC 内部调用 / Bearer API key，无匿名）、
 * 入参约束、租户隔离的版本模式，以及成功导出才写入的 POLICY_EXPORTED 审计事件。
 */
@QuarkusTest
@TestProfile(CedarExportResourceTest.AuthProfile.class)
class CedarExportResourceTest {

  /** HMAC 密钥、API key 鉴权与 RBAC 同时打开，签名关闭以贴近生产；匿名请求才会被过滤器拒绝。 */
  public static class AuthProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "aster.plan-gate.hmac-key", HMAC_KEY,
          "aster.security.apikey.enabled", "true",
          "aster.security.rbac.enabled", "true",
          "aster.security.signature.enabled", "false",
          "aster.security.evaluate-source.public", "false",
          "aster.security.evaluate-source.trial.enabled", "false");
    }
  }

  static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
  static final String PATH = "/api/v1/policies/export/cedar";
  static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  static final String TENANT = "tenant-cedar-export-" + RUN;
  static final String TENANT_A = "tenant-a-cedar";
  static final String TENANT_B = "tenant-b-cedar";
  static final String BEARER_KEY = "ak_test_cedar_export_member";
  static final String BEARER_TENANT = "tenant-cedar-bearer-" + RUN;
  static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;
  static final String SAMPLE = read("cedar/guard-cedar-sample.aster");
  static final String GUARD_MODULE = "Module guard.cedar.bad.\n\n"
      + "Define GuardRequest has principal_id, principal_type, principal_roles, action, "
      + "resource_type, resource_id, context, agent_model.\n\n";

  @Inject ApiKeyVerifierService verifier;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  /** 只在「入链失败」用例里打桩，其余用例走真实追加器。 */
  @InjectSpy AuditChainAppender appender;
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
        .post(PATH).then().statusCode(401);
  }

  @Test
  void tenantHeaderAloneIsRejected() {
    given().header("X-Tenant-Id", TENANT).header("X-User-Role", "MEMBER").contentType(ContentType.JSON)
        .body(Map.of("source", SAMPLE)).post(PATH).then().statusCode(401);
  }

  @Test
  void hmacCallExportsAndAudits() throws Exception {
    String body = json(Map.of("source", SAMPLE, "locale", "en-US",
        "claimedRef", Map.of("cloudPolicyId", "pol-1", "version", "3")));
    var resp = signed(body).post(PATH);
    resp.then().statusCode(200).body("exportable", equalTo(true)).body("ruleId", equalTo("GCS-001"))
        .body("policies", notNullValue()).body("schema", notNullValue())
        .body("probes.count", equalTo(resp.jsonPath().getInt("probes.agreed")));
    AuditLog row = awaitExportEvent(TENANT);
    Map<String, Object> meta = metadataOf(row);
    assertThat(meta).containsEntry("target", "cedar").containsEntry("ruleId", "GCS-001")
        .containsKeys("sourceHash", "policiesHash", "schemaHash", "probeCount", "claimedRef");
    assertThat((String) meta.get("policiesHash")).matches("[0-9a-f]{64}");
    assertThat((String) meta.get("schemaHash")).matches("[0-9a-f]{64}");
    assertThat(meta.get("sourceHash")).isEqualTo(resp.jsonPath().getString("sourceHash"));
    assertThat(MAPPER.readTree((String) meta.get("claimedRef")).path("cloudPolicyId").asText()).isEqualTo("pol-1");
    assertThat(meta).doesNotContainKey("versionId");
    assertThat(row.policyModule).isEqualTo("guard.cedar.sample");
    assertThat(row.performedBy).isEqualTo("u-1");
  }

  @Test
  void responseCarriesModuleNameAndVerbatimSchemaMatchingAuditHashes() throws Exception {
    String tenant = "tenant-cedar-verbatim-" + RUN;
    var resp = signedAs(tenant, json(Map.of("source", SAMPLE))).post(PATH);
    resp.then().statusCode(200).body("exportable", equalTo(true)).body("moduleName", equalTo("guard.cedar.sample"));
    String schemaText = resp.jsonPath().getString("schemaText");
    String policies = resp.jsonPath().getString("policies");
    // 下载件即这两个原样字符串，其 SHA-256 须与审计哈希逐一相等
    Map<String, Object> meta = metadataOf(awaitExportEvent(tenant));
    assertThat(sha256(schemaText)).isEqualTo(meta.get("schemaHash"));
    assertThat(sha256(policies)).isEqualTo(meta.get("policiesHash"));
    assertThat(MAPPER.readTree(schemaText)).isEqualTo(MAPPER.readTree(resp.body().asString()).path("schema"));
  }

  @Test
  void notExportableStillNamesModuleWithoutSchemaText() throws Exception {
    String src = GUARD_MODULE + "@id(\"R-Y\")\nRule decide given req as GuardRequest, produce Verdict:\n"
        + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
    signed(json(Map.of("source", src, "locale", "en-US"))).post(PATH).then().statusCode(200)
        .body("exportable", equalTo(false))
        .body("moduleName", equalTo("guard.cedar.bad"))
        .body("schemaText", nullValue());
  }

  @Test
  void hmacGermanSourceWithVocabularyExports() throws Exception {
    // ADR 0051：源码模式携带词汇时按位置解析后导出
    String tenant = "tenant-cedar-vocab-" + RUN;
    String body = json(Map.of("source", io.aster.policy.parser.GermanReproductions.GUARD_SOURCE,
        "locale", "de-DE", "vocabulary", io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY));
    var resp = signedAs(tenant, body).post(PATH);
    resp.then().statusCode(200).body("exportable", equalTo(true)).body("ruleId", equalTo("DE-001"))
        .body("probes.count", equalTo(resp.jsonPath().getInt("probes.agreed")));
    // 词汇身份随响应与审计留痕：同一源码不同词汇的导出不共享溯源
    String expected = io.aster.policy.parser.CompileIdentity.scopesHash(
        io.aster.replay.core.ReplayExecutionCore.buildVocabularyScopes(
            io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY));
    assertThat(expected).matches("[0-9a-f]{64}");
    assertThat(resp.jsonPath().getString("scopesHash")).isEqualTo(expected);
    assertThat(metadataOf(awaitExportEvent(tenant))).containsEntry("scopesHash", expected);
  }

  @Test
  void exportWithoutVocabularyRecordsEmptyScopesHash() throws Exception {
    String tenant = "tenant-cedar-novocab-" + RUN;
    var resp = signedAs(tenant, json(Map.of("source", SAMPLE))).post(PATH);
    resp.then().statusCode(200).body("exportable", equalTo(true)).body("scopesHash", equalTo(""));
    assertThat(metadataOf(awaitExportEvent(tenant))).containsEntry("scopesHash", "");
  }

  @Test
  void oversizedVocabularyIsRejected() throws Exception {
    Map<String, Object> vocab = new LinkedHashMap<>(io.aster.policy.parser.GermanReproductions.GUARD_VOCABULARY);
    vocab.put("fields", java.util.Collections.nCopies(
        io.aster.policy.rest.model.VocabularyBounds.MAX_ENTRIES_PER_SECTION + 1, 1));
    String body = json(Map.of("source", SAMPLE, "vocabulary", vocab));
    signed(body).post(PATH).then().statusCode(400)
        .body("error", equalTo(io.aster.policy.rest.model.VocabularyBounds.ERROR_CODE));
  }

  @Test
  void hmacViewerRoleIsForbidden() throws Exception {
    String tenant = "tenant-cedar-viewer-" + RUN;
    String body = json(Map.of("source", SAMPLE));
    given().headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, tenant, "VIEWER", "u-1", List.of()))
        .contentType(ContentType.JSON).body(body).post(PATH).then().statusCode(403);
    assertThat(exportEventCount(tenant)).isZero();
  }

  @Test
  void bearerApiKeyChannelExports() throws Exception {
    given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON)
        .body(json(Map.of("source", SAMPLE))).post(PATH)
        .then().statusCode(200).body("exportable", equalTo(true));
    assertThat(metadataOf(awaitExportEvent(BEARER_TENANT))).containsEntry("target", "cedar");
  }

  @Test
  void auditFailureFailsTheRequest() throws Exception {
    doThrow(new AuditChainAppender.AuditAppendException("boom", null)).when(appender).appendSync(any());
    String tenant = "tenant-cedar-auditfail-" + RUN;
    signedAs(tenant, json(Map.of("source", SAMPLE))).post(PATH).then().statusCode(500)
        .body("error", equalTo("audit_unavailable"))
        .body("policies", nullValue());
    assertThat(exportEventCount(tenant)).isZero();
  }

  @Test
  void notExportableReturns200WithDiagnosticsAndNoAudit() throws Exception {
    String tenant = "tenant-cedar-noexport-" + RUN;
    String src = GUARD_MODULE + "@id(\"R-X\")\nRule decide given req as GuardRequest, produce Verdict:\n"
        + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
    String body = json(Map.of("source", src, "locale", "en-US"));
    signedAs(tenant, body).post(PATH).then().statusCode(200)
        .body("exportable", equalTo(false))
        .body("diagnostics.code", hasItem("E721"))
        .body("policies", nullValue())
        .body("schema", nullValue());
    assertThat(exportEventCount(tenant)).isZero();
  }

  @Test
  void exactlyOneOfSourceOrVersionId() throws Exception {
    String both = json(Map.of("source", SAMPLE, "versionId", 1));
    signed(both).post(PATH).then().statusCode(400).body("error", equalTo("exactly one of source or versionId"));
    String neither = json(Map.of("locale", "en-US"));
    signed(neither).post(PATH).then().statusCode(400).body("error", equalTo("exactly one of source or versionId"));
  }

  @Test
  void claimedRefRejectsControlAndOddCharacters() throws Exception {
    signed(json(Map.of("source", SAMPLE, "claimedRef", Map.of("version", "3\u0001")))).post(PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef invalid"));
    signed(json(Map.of("source", SAMPLE, "claimedRef", Map.of("cloud policy", "pol-1")))).post(PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef invalid"));
    signed(json(Map.of("source", SAMPLE, "claimedRef", Map.of("version", "")))).post(PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef invalid"));
  }

  @Test
  void saturatedGateReturnsBusy() throws Exception {
    String tenant = "tenant-cedar-busy-" + RUN;
    int held = 0;
    while (gate.tryAcquire()) {
      held++;
    }
    try {
      assertThat(held).isEqualTo(2);
      signedAs(tenant, json(Map.of("source", SAMPLE))).post(PATH).then().statusCode(503)
          .header("Retry-After", "1")
          .body("error", equalTo("cedar_export_busy"))
          .body("concurrencyLimit", equalTo(2));
    } finally {
      for (int i = 0; i < held; i++) {
        gate.release();
      }
    }
    assertThat(exportEventCount(tenant)).isZero();
    signedAs(tenant, json(Map.of("source", SAMPLE))).post(PATH).then().statusCode(200);
  }

  @Test
  void claimedRefIsBounded() throws Exception {
    Map<String, String> five = new LinkedHashMap<>();
    for (int i = 0; i < 5; i++) {
      five.put("k" + i, "v");
    }
    signed(json(Map.of("source", SAMPLE, "claimedRef", five))).post(PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef too large"));
    String longValue = "x".repeat(129);
    signed(json(Map.of("source", SAMPLE, "claimedRef", Map.of("version", longValue)))).post(PATH)
        .then().statusCode(400).body("error", equalTo("claimedRef too large"));
  }

  @Test
  void oversizedSourceIsRejected() throws Exception {
    String huge = "x".repeat(io.aster.policy.rest.model.CnlSourceLimits.MAX_ANON_SOURCE_LENGTH + 1);
    signed(json(Map.of("source", huge))).post(PATH).then().statusCode(400).body("error", equalTo("source too large"));
  }

  @Test
  void versionIdIsTenantScoped() throws Exception {
    Long versionId = DeployedMapParamProbeTest.deploy(TENANT_A, "guard.cedar.sample", "decide", SAMPLE);
    String body = json(Map.of("versionId", versionId));
    signedAs(TENANT_B, body).post(PATH).then().statusCode(404);
    signedAs(TENANT_A, body).post(PATH).then().statusCode(200).body("exportable", equalTo(true));
    Map<String, Object> meta = metadataOf(awaitExportEvent(TENANT_A));
    assertThat(meta).containsEntry("versionId", versionId.intValue());
  }

  // ---------- 辅助 ----------

  static RequestSpecification signed(String body) {
    return signedAs(TENANT, body);
  }

  static RequestSpecification signedAs(String tenant, String body) {
    return given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, tenant, "MEMBER", "u-1", List.of()))
        .contentType(ContentType.JSON).body(body);
  }

  static String sha256(String text) throws Exception {
    byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    return java.util.HexFormat.of().formatHex(d);
  }

  static String json(Object value) throws Exception {
    return MAPPER.writeValueAsString(value);
  }

  /** 轮询 ≤ 5 s 等导出事件落链。 */
  static AuditLog awaitExportEvent(String tenant) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      List<AuditLog> rows = QuarkusTransaction.requiringNew().call(() ->
          AuditLog.<AuditLog>find("tenantId = ?1 and eventType = 'POLICY_EXPORTED'", tenant).list());
      if (!rows.isEmpty()) return rows.get(0);
      Thread.sleep(100);
    }
    throw new AssertionError("5 s 内未在审计链找到租户 " + tenant + " 的 POLICY_EXPORTED 事件");
  }

  static long exportEventCount(String tenant) throws InterruptedException {
    Thread.sleep(300);
    return QuarkusTransaction.requiringNew().call(() ->
        AuditLog.count("tenantId = ?1 and eventType = 'POLICY_EXPORTED'", tenant));
  }

  static Map<String, Object> metadataOf(AuditLog row) throws Exception {
    return MAPPER.readValue(row.metadata, new TypeReference<Map<String, Object>>() { });
  }

  static String read(String resource) {
    try (InputStream in = CedarExportResourceTest.class.getClassLoader().getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
