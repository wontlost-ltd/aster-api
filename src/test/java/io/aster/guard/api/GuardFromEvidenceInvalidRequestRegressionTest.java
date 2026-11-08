package io.aster.guard.api;

import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * {@code POST /api/v1/guard/decisions/from-evidence} 的非法请求响应回归（同 {@link GuardInvalidRequestRegressionTest}）：
 * 该端点只接受 HMAC，需要 {@link GuardFromEvidenceResourceTest.AuthProfile}，所以单独成类、单独 golden。
 * golden 在改读原始请求体之前的代码上录制。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class GuardFromEvidenceInvalidRequestRegressionTest {
  static final String GOLDEN = "guard/invalid-request-responses-from-evidence.golden.json";
  private static final String PATH = "/api/v1/guard/decisions/from-evidence";
  private static final String TENANT = "tenant-guard-evidence-invalid-golden";
  /** 与 {@link GuardFromEvidenceResourceTest.AuthProfile} 配置的 HMAC 密钥相同。 */
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";

  private static Map<String, String> cases() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("invalidJson", "{\"correlationId\":");
    cases.put("actionTypeMismatch", "{\"correlationId\":\"ev-golden\",\"action\":{\"principal\":[1]}}");
    cases.put("emptyBody", "");
    return cases;
  }

  private static Map<String, Object> observe(String body) {
    Response resp = given()
        .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body,
            TENANT, "member", "u-req", List.of()))
        .contentType("application/json")
        .body(body.getBytes(StandardCharsets.UTF_8))
        .post(PATH);
    Map<String, Object> observed = new LinkedHashMap<>();
    observed.put("status", resp.statusCode());
    observed.put("body", GuardInvalidRequestRegressionTest.normalize(resp.asString()));
    return observed;
  }

  @Test
  void invalidRequestResponsesAreByteIdentical() throws IOException {
    Map<String, Object> actual = new LinkedHashMap<>();
    cases().forEach((name, body) -> actual.put(name, observe(body)));
    GuardInvalidRequestRegressionTest.assertGolden(GOLDEN, actual);
  }
}
