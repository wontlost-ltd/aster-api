package io.aster.policy.rest;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.compiler.PolicyExecutionGate;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.aster.replay.core.parser.ReplayMappers;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ADR 0041 §2.2–§2.3 端到端：三条 /evaluate* 端点生成 correlationId、在响应 evidence 中回传，
 * 并把证据键随 POLICY_EVALUATION 事件异步落入审计链。
 *
 * <p>沿用 {@link PolicyEvaluationReplayOrderingTest.HmacProfile}：该 profile 下 /evaluate-source 只接受
 * HMAC 已验证的内部调用方，而 simulate 模式本身也只对 HMAC 已验证调用方生效，两者都需要签名请求。
 * 事件行按 audit_logs.correlation_id 列定位（{@link AuditLog#findByCorrelationIds}）。
 *
 * <p>版本解析缓存 TTL 缩短为 {@value #VERSION_TTL_MS} ms（{@link ShortVersionTtlProfile}），让「不手动失效、
 * 等 TTL 过期」的版本切换用例在秒级完成；其余配置与 HmacProfile 一致。
 */
@QuarkusTest
@TestProfile(EvaluateEvidenceEventTest.ShortVersionTtlProfile.class)
class EvaluateEvidenceEventTest {

  static final long VERSION_TTL_MS = 5000;

  public static class ShortVersionTtlProfile extends PolicyEvaluationReplayOrderingTest.HmacProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = new HashMap<>(super.getConfigOverrides());
      m.put("aster.policy.version-cache.ttl-ms", String.valueOf(VERSION_TTL_MS));
      return m;
    }
  }

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
  private static final String GOVERNED_SOURCE =
      VERDICT_SOURCE.replace("Module evidence.src.", "Module evidence.gov.\nProfile \"governed\".");

  /** 已部署的声明 governed 档案且合规（有 @id、无未登记控制点）的模块，走 /evaluate 的数据库加载路径。 */
  private static final String GOVERNED_MODULE = "evidence.gov.deployed";
  private static final String GOVERNED_DEPLOYED_SOURCE = DeployedMapParamProbeTest.SOURCE
      .replace("Module guard.probe.", "Module " + GOVERNED_MODULE + ".\nProfile \"governed\".")
      .replace("@id(\"PROBE-001\")", "@id(\"GOV-001\")\n@control(\"EU_AI_ACT:ART14\")");

  private static boolean deployed;
  /** 走 /evaluate 数据库加载路径的已部署版本 id，用于核对事件里的 policyVersionId。 */
  private static Long probeVersionId;

  @Inject
  PolicyEvaluationService policyEvaluationService;

  /** 只做观察（不打桩）：核对门禁读阶段拿到的版本 id 与事件所写一致。%test 下门禁为 off，但读阶段仍以版本 id 进入。 */
  @InjectSpy
  PolicyExecutionGate executionGate;

  /** 只在「成功事件后抛错」用例里打桩，其余用例走真实实现。 */
  @InjectSpy
  ToolchainIdentityProvider toolchainIdentityProvider;

  @BeforeEach
  void deployOnce() {
    if (deployed) return;
    probeVersionId = DeployedMapParamProbeTest.deploy(TENANT, DeployedMapParamProbeTest.MODULE,
        DeployedMapParamProbeTest.FUNCTION, DeployedMapParamProbeTest.SOURCE);
    DeployedMapParamProbeTest.deploy(TENANT, GOVERNED_MODULE, DeployedMapParamProbeTest.FUNCTION,
        GOVERNED_DEPLOYED_SOURCE);
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
    assertThat(md).doesNotContainKeys("profile", "inputRefs", "verifiedInputsRequired");
    assertThat(MAPPER.readTree(resp.asString()).has("profile")).as(resp.asString()).isFalse();

    // issue #459：事件指向被执行的封存版本；sourceHash 是源码内容哈希（与 guard 收据、版本行同口径），
    // 测试部署的版本无 envelope，故不写 sourceEnvelopeSha256。
    PolicyVersion executed = QuarkusTransaction.requiringNew().call(() -> PolicyVersion.findById(probeVersionId));
    assertThat(md).containsEntry("policyVersionId", probeVersionId.intValue())
        .containsEntry("sourceHash", executed.sourceHash)
        .containsEntry("sourceHash", PolicyVersion.computeSourceHash(DeployedMapParamProbeTest.SOURCE))
        .doesNotContainKey("sourceEnvelopeSha256");
    // 响应契约不变：版本键只进链，不回给调用方
    assertThat(MAPPER.readTree(resp.asString()).has("policyVersionId")).isFalse();
    assertThat(MAPPER.readTree(resp.asString()).has("sourceHash")).isFalse();
    assertThat(MAPPER.readTree(resp.asString()).has("sourceEnvelopeSha256")).isFalse();
  }

  /**
   * issue #459：按请求绑定——同一请求内门禁读阶段拿到的 versionId 与该请求证据事件的 policyVersionId 相等
   * （全新模块，每个版本首评必经编译路径）；激活切换并显式失效缓存后，新事件指向新版本；
   * sourceHash 始终是内容哈希，带 envelope 的版本另写 sourceEnvelopeSha256。
   */
  @Test
  void evaluate版本与门禁按请求一致且显式失效后指向新版本() throws Exception {
    String module = freshModule("switch");
    String function = DeployedMapParamProbeTest.FUNCTION;
    String source = sourceFor(module);
    Long v1 = DeployedMapParamProbeTest.deploy(TENANT, module, function, source);
    Map<String, Object> md1 = evaluateAndAssertGateBound(module, function);
    assertThat(md1).containsEntry("policyVersionId", v1.intValue())
        .containsEntry("sourceHash", PolicyVersion.computeSourceHash(source))
        .doesNotContainKey("sourceEnvelopeSha256");

    String envelope = "e".repeat(64);
    String source2 = source.replace("@id(\"PROBE-001\")", "@id(\"PROBE-002\")");
    Long v2 = activateNewVersion(module, function, source2, envelope);
    policyEvaluationService.invalidateCache(TENANT, module, function).await().indefinitely();

    Map<String, Object> md2 = evaluateAndAssertGateBound(module, function);
    assertThat(md2).containsEntry("policyVersionId", v2.intValue()).containsEntry("ruleId", "PROBE-002")
        .containsEntry("sourceHash", PolicyVersion.computeSourceHash(source2))
        .containsEntry("sourceEnvelopeSha256", envelope);
  }

  /**
   * issue #459：不手动失效，只等版本解析缓存 TTL 过期。切换后 TTL 内的请求仍整体指向旧版本
   * （门禁、执行、事件一致，不会出现「校验新版本、事件写旧版本」），TTL 过后指向新版本。
   */
  @Test
  void evaluate版本切换后等解析缓存TTL过期即指向新版本() throws Exception {
    String module = freshModule("ttl");
    String function = DeployedMapParamProbeTest.FUNCTION;
    String source = sourceFor(module);
    Long v1 = DeployedMapParamProbeTest.deploy(TENANT, module, function, source);
    assertThat(evaluateAndAssertGateBound(module, function)).containsEntry("policyVersionId", v1.intValue());
    // 计时起点取「首次确认事件指向 v1」的时刻：解析缓存条目在此之前已写入，故据此算出的剩余 TTL 只会偏小，
    // 不会把实际已过期的请求当作「TTL 内」。
    long confirmedV1At = System.currentTimeMillis();

    Long v2 = activateNewVersion(module, function, source.replace("@id(\"PROBE-001\")", "@id(\"PROBE-003\")"), null);
    // 仍在 TTL 内：旧解析继续生效，且本次请求的门禁与事件一起指向 v1（事件版本按请求绑定）。
    // 该断言必须执行：若切换 + 请求 + 落链已耗尽 TTL，则是测试 TTL 过短，直接失败而不是跳过。
    Map<String, Object> stale = evaluateDeployedMetadata(module, function);
    long elapsed = System.currentTimeMillis() - confirmedV1At;
    assertThat(elapsed).as("切换后首个请求须落在解析缓存 TTL 内；超出请调大 VERSION_TTL_MS").isLessThan(VERSION_TTL_MS);
    assertThat(stale).containsEntry("policyVersionId", v1.intValue());

    Thread.sleep(Math.max(0, VERSION_TTL_MS - (System.currentTimeMillis() - confirmedV1At)) + 500);
    Map<String, Object> fresh = evaluateAndAssertGateBound(module, function);
    assertThat(fresh).containsEntry("policyVersionId", v2.intValue()).containsEntry("ruleId", "PROBE-003");
  }

  /** 首次走编译+执行（Core JSON 宽松重解析取档案），第二次命中 policy-results 缓存，两次都须带 profile。 */
  @Test
  void evaluate声明档案时冷启动与缓存命中均带profile() throws Exception {
    Map<String, Object> body = Map.of("policyModule", GOVERNED_MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
        "context", List.of(guardRequest("read_customer_record")), "agent", AGENT);

    assertEvaluateCarriesProfile(body);
    assertThat(policyEvaluationService.snapshotTenantCacheKeys(TENANT))
        .anyMatch(k -> GOVERNED_MODULE.equals(k.getPolicyModule()));
    assertEvaluateCarriesProfile(body);
  }

  private static void assertEvaluateCarriesProfile(Map<String, Object> body) throws Exception {
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(body).post("/api/v1/policies/evaluate");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("profile")).as(resp.asString()).isEqualTo("governed");
    assertThat(resp.jsonPath().getString("ruleId")).isEqualTo("GOV-001");
    String cid = resp.jsonPath().getString("evidence.correlationId");
    // 规则读 req.action；/evaluate 的输入是位置数组、没有匹配键 → 证据形状为 ["*"]（Task 16b）
    assertThat(metadataOf(awaitEvent(TENANT, cid))).containsEntry("profile", "governed")
        .containsEntry("ruleId", "GOV-001").containsEntry("inputRefs", List.of("*"))
        .containsEntry("verifiedInputsRequired", false);
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

  /** issue #459：/evaluate 失败（无版本可解析）时不写版本键，不编造。 */
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
    assertThat(metadataOf(row)).containsEntry("agentProvider", "anthropic")
        .doesNotContainKeys("outcome", "policyVersionId", "sourceHash", "sourceEnvelopeSha256");
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
    // #445：未带 Accept-Language 时文案为英文，不再是中文硬编码
    assertThat(r1.jsonPath().getString("error")).isEqualTo("agent.provider is required");

    Map<String, Object> jsonBody = new HashMap<>(Map.of("policy", coreIrJsonOf("evidence.json"),
        "context", Map.of("amount", 1), "agent", badAgent));
    Response r2 = given().header("X-Tenant-Id", tenant).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(jsonBody).post("/api/v1/policies/evaluate-json");
    assertThat(r2.statusCode()).as(r2.asString()).isEqualTo(400);
    assertThat(r2.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");

    Response r3 = evaluateSource(tenant, badAgent, false);
    assertThat(r3.statusCode()).as(r3.asString()).isEqualTo(400);
    assertThat(r3.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");

    // #445：Accept-Language 指定德语时文案随之切换，code 不变
    Response r4 = given().header("X-Tenant-Id", tenant).header("X-User-Id", "u-ev").header("Accept-Language", "de-DE")
        .contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
            "context", List.of(guardRequest("read_customer_record")), "agent", badAgent))
        .post("/api/v1/policies/evaluate");
    assertThat(r4.statusCode()).as(r4.asString()).isEqualTo(400);
    assertThat(r4.jsonPath().getString("diagnostics[0].code")).isEqualTo("invalid_agent");
    assertThat(r4.jsonPath().getString("error")).isEqualTo("agent.provider ist erforderlich");

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
        .containsEntry("agentSource", "declared").containsEntry("sourceFormat", "cnl")
        .doesNotContainKeys("profile", "inputRefs", "verifiedInputsRequired");
    assertThat(MAPPER.readTree(resp.asString()).has("profile")).as(resp.asString()).isFalse();
    // issue #459：即时源码无已保存版本 → 不写 policyVersionId / sourceEnvelopeSha256，
    // 但写与封存同算法的源码内容 sourceHash（与 guard 收据、版本行可直接比对）
    assertThat(md).doesNotContainKeys("policyVersionId", "sourceEnvelopeSha256")
        .containsEntry("sourceHash", PolicyVersion.computeSourceHash(VERDICT_SOURCE));
    assertThat(md.get("sourceHash")).asString().matches("^[0-9a-f]{64}$");
    assertThat(MAPPER.readTree(resp.asString()).has("sourceHash")).isFalse();
  }

  @Test
  void evaluateSource声明档案时响应与链事件带profile() throws Exception {
    Response resp = evaluateSource(TENANT, GOVERNED_SOURCE, AGENT, false, false);
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("profile")).as(resp.asString()).isEqualTo("governed");
    String cid = resp.jsonPath().getString("evidence.correlationId");

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, cid));
    // 规则不读输入：inputRefs 写 []（不省略）；verifiedInputsRequired 为写事件时的快照（Task 16b）
    assertThat(md).containsEntry("profile", "governed").containsEntry("ruleId", "EV-SRC-001")
        .containsEntry("inputRefs", List.of()).containsEntry("verifiedInputsRequired", false);
  }

  @Test
  void evaluateJson声明档案时响应与链事件带profile() throws Exception {
    Map<String, Object> body = Map.of("policy", coreIrJsonOf(GOVERNED_SOURCE, "evidence.govjson"),
        "context", Map.of("amount", 1), "agent", AGENT);
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(body).post("/api/v1/policies/evaluate-json");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("profile")).as(resp.asString()).isEqualTo("governed");
    String cid = resp.jsonPath().getString("evidence.correlationId");

    assertThat(metadataOf(awaitEvent(TENANT, cid))).containsEntry("profile", "governed")
        .containsEntry("inputRefs", List.of()).containsEntry("verifiedInputsRequired", false);
  }

  /** 读第一个参数（标量 amount）的档案规则：写事件时映射为 context.<实际匹配键>。 */
  private static final String READS_AMOUNT_SOURCE = String.join("\n",
      "Module evidence.reads.",
      "Profile \"governed\".", "",
      "@id(\"EV-READ-001\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule main given amount as Int, produce Verdict:",
      "  If amount greater than 10:",
      "    Return Verdict.deny(\"too much\").",
      "  Return Verdict.allow().");

  @Test
  void evaluateSource事件的inputRefs映射为实际匹配键() throws Exception {
    Response resp = evaluateSource(TENANT, READS_AMOUNT_SOURCE, AGENT, false, false);
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, resp.jsonPath().getString("evidence.correlationId")));
    assertThat(md).containsEntry("inputRefs", List.of("context.amount"));
  }

  /**
   * ADR 0046 §11.6 第 19 条：/evaluate-json 提交的 Core IR 不可信，伪造的 {@code inputRefs: []} 不得变成空引用集的证据事件；
   * 元数据按函数体重算，事件写映射后的 context.amount。
   */
  @Test
  void evaluateJson伪造的inputRefs不进证据事件() throws Exception {
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode)
        MAPPER.readTree(coreIrJsonOf(READS_AMOUNT_SOURCE, "evidence.forged"));
    for (var decl : tree.withArray("decls")) {
      if ("main".equals(decl.path("name").asText())) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) decl).putArray("inputRefs");
      }
    }
    Map<String, Object> body = Map.of("policy", MAPPER.writeValueAsString(tree), "context", Map.of("amount", 1),
        "agent", AGENT);
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(body).post("/api/v1/policies/evaluate-json");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);

    Map<String, Object> md = metadataOf(awaitEvent(TENANT, resp.jsonPath().getString("evidence.correlationId")));
    assertThat(md).containsEntry("profile", "governed").containsEntry("inputRefs", List.of("context.amount"));
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
        .containsEntry("agentProvider", "anthropic").containsEntry("sourceFormat", "json")
        .doesNotContainKeys("profile", "inputRefs", "verifiedInputsRequired");
  }

  /** ADR 0041 §11 D2：/evaluate、/evaluate-source、/evaluate-json 的事件（含失败与无 agent）都带 inputSource=declared。 */
  @Test
  void evaluateEventsCarryInputSourceDeclared() throws Exception {
    Response evaluate = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE, "policyFunction", DeployedMapParamProbeTest.FUNCTION,
            "context", List.of(guardRequest("read_customer_record"))))
        .post("/api/v1/policies/evaluate");
    Response failed = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", "evidence.missing", "policyFunction", "nope", "context", List.of(Map.of("x", 1))))
        .post("/api/v1/policies/evaluate");
    Response source = evaluateSource(TENANT, AGENT, false);
    Response json = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policy", coreIrJsonOf("evidence.json"), "context", Map.of("amount", 1), "agent", AGENT))
        .post("/api/v1/policies/evaluate-json");

    for (Response resp : List.of(evaluate, failed, source, json)) {
      String cid = resp.jsonPath().getString("evidence.correlationId");
      assertThat(cid).as(resp.asString()).isNotBlank();
      assertThat(metadataOf(awaitEvent(TENANT, cid))).as(resp.asString()).containsEntry("inputSource", "declared");
    }
  }

  // ---------------------------------------------------------------- helpers

  /** 模块段须是合法标识符：RUN 可能以数字开头（CNL 语法错误），故映射为纯字母。 */
  private static String freshModule(String tag) {
    String letters = RUN.chars().mapToObj(c -> String.valueOf((char) ('a' + Character.digit(c, 16))))
        .reduce("", String::concat);
    return "evidence." + tag + "." + letters;
  }

  private static String sourceFor(String module) {
    return DeployedMapParamProbeTest.SOURCE.replace("Module guard.probe.", "Module " + module + ".");
  }

  /** 停用现活跃版本，新建并激活一个版本（可带 envelope），返回其 id；不触碰任何缓存。 */
  private static Long activateNewVersion(String module, String function, String content, String envelope) {
    return QuarkusTransaction.requiringNew().call(() -> {
      PolicyVersion.update("active = false where tenantId = ?1 and moduleName = ?2 and functionName = ?3",
          TENANT, module, function);
      PolicyVersion next = new PolicyVersion();
      next.policyId = module + "." + function; next.version = System.nanoTime();
      next.moduleName = module; next.functionName = function; next.content = content;
      next.sourceEnvelopeSha256 = envelope;
      next.locale = "en-US"; next.active = true; next.status = VersionStatus.APPROVED;
      next.tenantId = TENANT; next.createdAt = Instant.now(); next.createdBy = "probe";
      next.persist();
      PolicyCatalog.update("defaultVersionId = ?1 where tenantId = ?2 and moduleName = ?3 and functionName = ?4",
          next.id, TENANT, module, function);
      return next.id;
    });
  }

  /**
   * 一次 /evaluate 请求：清空 spy 记录 → 请求 → 捕获本请求内门禁读阶段的 versionId，断言与该请求事件的
   * policyVersionId 相等；返回事件 metadata。调用方须保证本次必走编译路径（新版本首评）。
   */
  private Map<String, Object> evaluateAndAssertGateBound(String module, String function) throws Exception {
    clearInvocations(executionGate);
    Map<String, Object> md = evaluateDeployedMetadata(module, function);
    ArgumentCaptor<Long> gateVersion = ArgumentCaptor.forClass(Long.class);
    verify(executionGate, atLeastOnce()).prefetch(gateVersion.capture());
    assertThat(gateVersion.getAllValues()).as("本请求门禁读阶段的版本").containsOnly(
        ((Number) md.get("policyVersionId")).longValue());
    return md;
  }

  private static Map<String, Object> evaluateDeployedMetadata(String module, String function) throws Exception {
    return metadataOf(awaitEvent(TENANT, evaluateDeployed(module, function)));
  }

  /** 评估已部署策略并返回响应里的 correlationId。 */
  private static String evaluateDeployed(String module, String function) {
    Response resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(ContentType.JSON)
        .body(Map.of("policyModule", module, "policyFunction", function,
            "context", List.of(guardRequest("read_customer_record")), "agent", AGENT))
        .post("/api/v1/policies/evaluate");
    assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();
    return cid;
  }

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
    return coreIrJsonOf(VERDICT_SOURCE, module);
  }

  private static String coreIrJsonOf(String source, String module) throws Exception {
    String cnl = source.replaceFirst("Module [a-z.]+\\.", "Module " + module + ".");
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
    return evaluateSource(tenant, VERDICT_SOURCE, agent, simulate, replayCapture);
  }

  private static Response evaluateSource(String tenant, String source, Map<String, Object> agent, boolean simulate,
                                         boolean replayCapture) throws Exception {
    Map<String, Object> req = new HashMap<>();
    req.put("source", source);
    req.put("context", Map.of("amount", 1));
    req.put("functionName", "main");
    req.put("locale", "en-US");
    req.put("agent", agent);
    String body = MAPPER.writeValueAsString(req);
    String query = "simulate=" + simulate + "&replayCapture=" + replayCapture;
    return given().contentType(ContentType.JSON)
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", SOURCE_PATH, query, body, tenant, ROLE, "u-ev", List.of()))
        .body(body)
        .post(SOURCE_PATH + "?" + query);
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
}
