package io.aster.guard.api;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批输入视图 / 解除端点的双通道鉴权端到端（ADR 0042 §12 D5）：cloud 走 HMAC 内部通道，
 * 身份与已验证业务角色取自签名头，语义与 approve / reject 一致；未验签或越界子路径一律 401。
 */
@QuarkusTest
@TestProfile(GuardApprovalInputHmacTest.AuthProfile.class)
class GuardApprovalInputHmacTest {

  /** apikey 鉴权与 HMAC 密钥同时打开，与生产的双通道形态一致。 */
  public static class AuthProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "aster.plan-gate.hmac-key", HMAC_KEY,
          "aster.security.apikey.enabled", "true",
          "aster.security.signature.enabled", "false",
          "aster.security.evaluate-source.public", "false",
          "aster.security.evaluate-source.trial.enabled", "false");
    }
  }

  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
  private static final String TENANT = "tenant-guard-input-hmac-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String DPO = "Data Protection Officer";
  private static final String APPROVALS = "/api/v1/guard/approvals";
  private static final String BEARER_KEY = "ak_test_guard_input_hmac";
  private static final String SOURCE = DeployedMapParamProbeTest.SOURCE
      .replace("@id(\"PROBE-001\")", "@id(\"CUST-DEL-001\")\n@control(\"GDPR:ART17\")");

  @Inject ApiKeyVerifierService verifier;

  /** 租户独占且只追加：策略只部署一次，各用例各自开新的待审批。 */
  private static boolean deployed;

  @BeforeEach
  void setUp() {
    if (!deployed) {
      DeployedMapParamProbeTest.deploy(TENANT, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
          SOURCE);
      deployed = true;
    }
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-gi-1", "agent-gi", TENANT, "pro", "active", "MEMBER"));
  }

  /** Agent 经 Bearer 提交决策，产生一条 PENDING 审批，返回审批 ID。 */
  private static String createPending() {
    Map<String, Object> action = Map.of(
        "principal", Map.of("id", "agent-42", "type", "agent", "roles", List.of("ops-bot")),
        "action", Map.of("name", "delete_customer_record"),
        "resource", Map.of("type", "customer", "id", "C-1001"),
        "context", Map.of("amount", 250000),
        "agent", Map.of("model", "claude-fable-5-1", "session", "s-1"));
    Response r = given().header("Authorization", "Bearer " + BEARER_KEY).contentType(ContentType.JSON)
        .body(Map.of("policyModule", DeployedMapParamProbeTest.MODULE,
            "policyFunction", DeployedMapParamProbeTest.FUNCTION, "action", action))
        .post("/api/v1/guard/decisions");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    return r.jsonPath().getString("approval.id");
  }

  /** 与 cloud signInternalCallerHeaders 一致（canonical v3）：租户、角色、用户与业务角色均签入。 */
  private static Map<String, String> signed(String method, String path, String body, String userId,
                                            List<String> businessRoles) {
    return InternalCallerSigning.headers(HMAC_KEY, method, path, "", body, TENANT, "member", userId, businessRoles);
  }

  private static Response hmacGet(String path, String userId, List<String> roles) {
    return given().headers(signed("GET", path, "", userId, roles)).get(path);
  }

  private static Response hmacPost(String path, String body, String userId, List<String> roles) {
    return given().headers(signed("POST", path, body, userId, roles)).contentType(ContentType.JSON).body(body)
        .post(path);
  }

  @Test
  void HMAC内部调用可读取输入视图() {
    String id = createPending();
    Response r = hmacGet(APPROVALS + "/" + id + "/input", "dpo-1", List.of(DPO));
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("status")).isEqualTo("available");
    assertThat(r.jsonPath().getString("inputSnapshotHash")).isNotBlank();
  }

  @Test
  void HMAC内部调用可解除原值_且按签名身份计入() {
    String id = createPending();
    Response r = hmacPost(APPROVALS + "/" + id + "/input/reveal", "", "dpo-1", List.of(DPO));
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("inputSnapshotHash")).isNotBlank();

    // 解除按人记录：dpo-1 的解除视图哈希只对 dpo-1 自己有效，dpo-2 提交它 → 409
    String revealedHash = r.jsonPath().getString("inputSnapshotHash");
    Map<String, Object> body = new HashMap<>(Map.of("inputSnapshotHash", revealedHash));
    String json = toJson(body);
    Response other = hmacPost(APPROVALS + "/" + id + "/approve", json, "dpo-2", List.of(DPO));
    assertThat(other.statusCode()).as(other.asString()).isEqualTo(409);
    Response self = hmacPost(APPROVALS + "/" + id + "/approve", json, "dpo-1", List.of(DPO));
    assertThat(self.statusCode()).as(self.asString()).isEqualTo(200);
  }

  @Test
  void 解除按已验证业务角色判定_签名角色为空时拒绝() {
    String id = createPending();
    Response r = hmacPost(APPROVALS + "/" + id + "/input/reveal", "", "dpo-1", List.of());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("reveal_not_permitted");
  }

  @Test
  void 签名之外自报的业务角色头不被采信() {
    String id = createPending();
    // 签名时角色为空，再追加一个未签入的 X-User-Business-Roles → 验签失败，不会静默降级为自报角色
    Map<String, String> h = signed("POST", APPROVALS + "/" + id + "/input/reveal", "", "dpo-1", List.of());
    h.put("X-User-Business-Roles", DPO);
    Response r = given().headers(h).contentType(ContentType.JSON).body("").post(APPROVALS + "/" + id + "/input/reveal");
    assertThat(r.statusCode()).as(r.asString()).isIn(401, 403);
  }

  @Test
  void 越界子路径即使带合法签名也不放行() {
    // 子路径本身无资源方法；真正的路径收窄由 InternalCallerFilterTest 的 classify 断言保证
    String id = createPending();
    for (String p : List.of(APPROVALS + "/" + id + "/input/other", APPROVALS + "/" + id + "/input/reveal/x",
        APPROVALS + "/" + id + "/inputX", APPROVALS + "/" + id + "/reveal")) {
      Response r = hmacGet(p, "dpo-1", List.of(DPO));
      // 无匹配资源方法的路径由路由先给 404；命中资源方法但未被放行的才是 401。两者都不得是 2xx
      assertThat(r.statusCode()).as(p + " -> " + r.asString()).isIn(401, 404);
    }
  }

  @Test
  void 无凭证访问输入端点返回401() {
    String id = createPending();
    given().header("X-Tenant-Id", TENANT).header("X-User-Id", "dpo-1")
        .get(APPROVALS + "/" + id + "/input").then().statusCode(401);
    given().header("X-Tenant-Id", TENANT).header("X-User-Id", "dpo-1")
        .contentType(ContentType.JSON).post(APPROVALS + "/" + id + "/input/reveal").then().statusCode(401);
  }

  @Test
  void Bearer通道仍可读取输入视图() {
    String id = createPending();
    Response r = given().header("Authorization", "Bearer " + BEARER_KEY).get(APPROVALS + "/" + id + "/input");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
  }

  private static String toJson(Map<String, Object> m) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
