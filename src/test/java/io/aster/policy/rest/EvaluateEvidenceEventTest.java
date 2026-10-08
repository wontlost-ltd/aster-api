package io.aster.policy.rest;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.aster.replay.core.parser.ReplayMappers;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * ADR 0041 §2.2–§2.3 端到端：三条 /evaluate* 端点生成 correlationId、在响应 evidence 中回传，
 * 并把证据键随 POLICY_EVALUATION 事件异步落入审计链。
 *
 * <p>沿用 {@link PolicyEvaluationReplayOrderingTest.HmacProfile}：该 profile 下 /evaluate-source 只接受
 * HMAC 已验证的内部调用方，而 simulate 模式本身也只对 HMAC 已验证调用方生效，两者都需要签名请求。
 * 事件行按 audit_logs.correlation_id 列定位（{@link AuditLog#findByCorrelationIds}）。
 */
@QuarkusTest
@TestProfile(PolicyEvaluationReplayOrderingTest.HmacProfile.class)
class EvaluateEvidenceEventTest {

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-evidence-" + RUN;
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
  private static final String ROLE = "MEMBER";
  private static final String SOURCE_PATH = "/api/v1/policies/evaluate-source";
  private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;
  private static final Map<String, Object> AGENT =
      Map.of("provider", "anthropic", "model", "claude-fable-5-1", "session", "s-1");
  private static final String VERDICT_SOURCE = String.join("\n",
      "Module evidence.src.", "",
      "@id(\"EV-SRC-001\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule main given amount as Int, produce Verdict:",
      "  Return Verdict.allow().");

  private static boolean deployed;

  /** 只在「成功事件后抛错」用例里打桩，其余用例走真实实现。 */
  @InjectSpy
  ToolchainIdentityProvider toolchainIdentityProvider;

  @BeforeEach
  void deployOnce() {
    if (deployed) return;
    DeployedMapParamProbeTest.deploy(TENANT, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
        DeployedMapParamProbeTest.SOURCE);
    deployed = true;
  }

  @Test
  void evaluate响应带correlationId且链事件含证据键() throws Exception {
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
            "context", List.of(guardRequest("read_customer_record")), "agent", AGENT))
        .post("/api/v1/policies/evaluate");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, cid));
    assertThat(md).containsEntry("correlationId", cid).containsEntry("outcome", "ALLOW")
        .containsEntry("ruleId", "PROBE-001")
        .containsEntry("agentProvider", "anthropic").containsEntry("agentModel", "claude-fable-5-1")
        .containsEntry("agentSession", "s-1").containsEntry("agentSource", "declared")
        .containsEntry("contextSize", 1);
    // /evaluate 本期不写版本（ADR 0041 修正 #2）
    assertThat(md).doesNotContainKey("policyVersionId");
  }

  @Test
  void evaluate无agent时仍生成correlationId且不写agent键() throws Exception {
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
            "context", List.of(guardRequest("delete_customer_record"))))
        .post("/api/v1/policies/evaluate");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, cid));
    assertThat(md).containsEntry("outcome", "REQUIRE_APPROVAL").doesNotContainKeys("agentProvider", "agentSource");
    assertThat(md).containsEntry("role", "Data Protection Officer").containsKey("reason");
  }

  @Test
  void evaluate失败也生成correlationId并落链() throws Exception {
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", "evidence.missing", "policyFunction", "nope",
            "context", List.of(Map.of("x", 1)), "agent", AGENT))
        .post("/api/v1/policies/evaluate");
    assertThat(resp.jsonPath().getString("error")).isNotBlank();
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();

    AuditLog row = awaitEvent(TENANT, cid);
    assertThat(row.success).isFalse();
    assertThat(metadataOf(row)).containsEntry("agentProvider", "anthropic").doesNotContainKey("outcome");
  }

  @Test
  void agent非法则400且不落链() throws Exception {
    String tenant = "tenant-evidence-bad-" + RUN;
    Map<String, Object> badAgent = Map.of("model", "m-only");
    Response r1 = given().header("X-Tenant-Id", tenant).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
            "context", List.of(guardRequest("read_customer_record")), "agent", badAgent))
        .post("/api/v1/policies/evaluate");
    assertThat(r1.statusCode()).as(r1.asString()).isEqualTo(400);
    assertThat(r1.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");

    Map<String, Object> jsonBody = new HashMap<>(Map.of("policy", coreIrJsonOf("evidence.json"),
        "context", Map.of("amount", 1), "agent", badAgent));
    Response r2 = given().header("X-Tenant-Id", tenant).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(jsonBody).post("/api/v1/policies/evaluate-json");
    assertThat(r2.statusCode()).as(r2.asString()).isEqualTo(400);
    assertThat(r2.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");

    Response r3 = evaluateSource(tenant, badAgent, false);
    assertThat(r3.statusCode()).as(r3.asString()).isEqualTo(400);
    assertThat(r3.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");

    Thread.sleep(1000);
    assertThat(countEvents(tenant)).isZero();
  }

  @Test
  void evaluateSource的simulate模式不输出evidence也不落链() throws Exception {
    String tenant = "tenant-evidence-sim-" + RUN;
    Response resp = evaluateSource(tenant, AGENT, true);
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("decision.outcome")).isEqualTo("ALLOW");
    assertThat(MAPPER.readTree(resp.asString()).has("evidence")).as(resp.asString()).isFalse();

    Thread.sleep(1000);
    assertThat(countEvents(tenant)).isZero();
  }

  @Test
  void evaluateSource非simulate输出evidence并落链() throws Exception {
    Response resp = evaluateSource(TENANT, AGENT, false);
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, cid));
    assertThat(md).containsEntry("outcome", "ALLOW").containsEntry("ruleId", "EV-SRC-001")
        .containsEntry("controls", List.of("EU_AI_ACT:ART14")).containsEntry("agentModel", "claude-fable-5-1")
        .containsEntry("agentSource", "declared").containsEntry("sourceFormat", "cnl");
  }

  /** 成功事件已发布后回放元数据阶段抛错：错误响应仍须带同一个 correlationId（ADR 0041 §2.2）。 */
  @Test
  void evaluateSource成功事件后抛错仍回evidence() throws Exception {
    doThrow(new IllegalStateException("toolchain boom")).when(toolchainIdentityProvider).currentToolchainId();

    Response resp = evaluateSource(TENANT, AGENT, false, true);
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("error")).contains("toolchain boom");
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();

    AuditLog row = awaitEvent(TENANT, cid);
    assertThat(row.success).isTrue();
    assertThat(metadataOf(row)).containsEntry("correlationId", cid).containsEntry("agentModel", "claude-fable-5-1");
  }

  @Test
  void evaluateJson输出evidence并落链() throws Exception {
    Map<String, Object> body = Map.of("policy", coreIrJsonOf("evidence.json"), "context", Map.of("amount", 1),
        "agent", AGENT);
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(body).post("/api/v1/policies/evaluate-json");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, cid));
    assertThat(md).containsEntry("outcome", "ALLOW").containsEntry("ruleId", "EV-SRC-001")
        .containsEntry("agentProvider", "anthropic").containsEntry("sourceFormat", "json");
  }

  // ---------------------------------------------------------------- helpers

  private static Map<String, Object> guardRequest(String action) {
    Map<String, Object> m = new HashMap<>();
    m.put("principal_id", "x");
    m.put("principal_type", "agent");
    m.put("principal_roles", List.of());
    m.put("action", action);
    m.put("resource_type", "");
    m.put("resource_id", "");
    m.put("context", Map.of());
    m.put("agent_model", "");
    return m;
  }

  /** 把 Verdict 源码改名后编译为 Core IR JSON，作为 /evaluate-json 的 policy 载荷。 */
  private static String coreIrJsonOf(String module) throws Exception {
    String cnl = VERDICT_SOURCE.replace("Module evidence.src.", "Module " + module + ".");
    var parsed = InProcessCnlParser.parse(cnl, null, null);
    CoreModel.Module core = new CoreLowering().lowerModule(parsed.module());
    return ReplayMappers.DEFAULT.writeValueAsString(core);
  }

  /** HMAC 已验证的 /evaluate-source 请求；simulate 只对已验证调用方生效。 */
  private static Response evaluateSource(String tenant, Map<String, Object> agent, boolean simulate) throws Exception {
    return evaluateSource(tenant, agent, simulate, false);
  }

  /** replayCapture 只对 HMAC 已验证调用方生效，会走到成功事件之后的回放元数据阶段。 */
  private static Response evaluateSource(String tenant, Map<String, Object> agent, boolean simulate,
                                         boolean replayCapture) throws Exception {
    Map<String, Object> req = new HashMap<>();
    req.put("source", VERDICT_SOURCE);
    req.put("context", Map.of("amount", 1));
    req.put("functionName", "main");
    req.put("locale", "en-US");
    req.put("agent", agent);
    String body = MAPPER.writeValueAsString(req);
    long ts = System.currentTimeMillis() / 1000;
    String nonce = "evidence-" + System.nanoTime();
    String canonical = "POST\n" + SOURCE_PATH + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body) + "\n" + tenant + "\n" + ROLE;
    return given().contentType(ContentType.JSON)
        .header("X-Internal-Caller", "cloud-bff")
        .header("X-Aster-Timestamp", Long.toString(ts))
        .header("X-Aster-Nonce", nonce)
        .header("X-Internal-Signature", sign(canonical))
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", "u-ev")
        .header("X-User-Role", ROLE)
        .body(body)
        .post(SOURCE_PATH + "?simulate=" + simulate + "&replayCapture=" + replayCapture);
  }

  /** 轮询 ≤ 5 s 等异步追加器落链；按 correlation_id 列定位事件行。 */
  private static AuditLog awaitEvent(String tenant, String cid) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      List<AuditLog> rows = QuarkusTransaction.requiringNew().call(() -> AuditLog.findByCorrelationIds(tenant, List.of(cid)));
      AuditLog hit = rows.stream().filter(r -> "POLICY_EVALUATION".equals(r.eventType)).findFirst().orElse(null);
      if (hit != null) return hit;
      Thread.sleep(100);
    }
    throw new AssertionError("5 s 内未在审计链找到 correlationId=" + cid);
  }

  private static long countEvents(String tenant) {
    return QuarkusTransaction.requiringNew().call(() ->
        AuditLog.count("tenantId = ?1 and eventType = 'POLICY_EVALUATION'", tenant));
  }

  private static Map<String, Object> metadataOf(AuditLog row) throws Exception {
    return MAPPER.readValue(row.metadata, new TypeReference<Map<String, Object>>() { });
  }

  private static String sha256Hex(String body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static String sign(String canonical) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
