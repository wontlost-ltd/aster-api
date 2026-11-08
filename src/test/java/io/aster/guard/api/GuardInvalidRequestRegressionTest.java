package io.aster.guard.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 非法请求响应回归：{@code POST /api/v1/guard/decisions} 改为自行解析原始请求体（重复键检测、actionHash）之后，
 * 今天已有的非法请求必须得到逐字节相同的状态码与响应体。
 *
 * <p>golden 由改动前的实现录制（文件不存在时本测试写出文件并失败，提示提交）；此后只做比对。
 * 策略地址指向不存在的模块，使能通过校验的用例稳定落在 404 {@code policy_not_found}。
 * 全局异常映射器的错误体带随机 {@code traceId} 与当前 {@code timestamp}，比对前把这两个值替换为占位符，其余字节原样比对。
 */
@QuarkusTest
class GuardInvalidRequestRegressionTest {
  static final String GOLDEN = "guard/invalid-request-responses.golden.json";
  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final String TENANT = "tenant-guard-invalid-golden";
  private static final String ADDRESS = "\"policyModule\":\"guard.golden.missing\",\"policyFunction\":\"decide\"";
  private static final String ACTION = "\"action\":{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"}}";

  /** 用例名 → [Idempotency-Key（可为 null）, 原始请求体]。顺序即 golden 文件顺序。 */
  private static Map<String, String[]> cases() {
    Map<String, String[]> cases = new LinkedHashMap<>();
    cases.put("invalidJson", new String[]{null, "{\"policyModule\":"});
    cases.put("actionMissing", new String[]{null, "{" + ADDRESS + "}"});
    cases.put("principalIdNumber", new String[]{null, "{" + ADDRESS
        + ",\"action\":{\"principal\":{\"id\":42},\"action\":{\"name\":\"refund\"}}}"});
    cases.put("waitSecondsString", new String[]{null, "{" + ADDRESS + "," + ACTION + ",\"waitSeconds\":\"soon\"}"});
    cases.put("emptyBody", new String[]{null, ""});
    cases.put("policyModuleMissing", new String[]{null, "{\"policyFunction\":\"decide\"," + ACTION + "}"});
    cases.put("idempotencyKeyTooLong", new String[]{"k".repeat(256), "{" + ADDRESS + "," + ACTION + "}"});
    cases.put("contextDepth9", new String[]{null, "{" + ADDRESS
        + ",\"action\":{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},\"context\":"
        + nested(9) + "}}"});
    return cases;
  }

  /** 深度为 depth 的嵌套对象：{"a":{"a":…{"a":1}…}}。 */
  private static String nested(int depth) {
    return "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
  }

  private static Map<String, Object> observe(String idempotencyKey, String body) {
    var spec = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-golden")
        .contentType("application/json")
        .body(body.getBytes(StandardCharsets.UTF_8));
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    Response resp = spec.when().post("/api/v1/guard/decisions");
    Map<String, Object> observed = new LinkedHashMap<>();
    observed.put("status", resp.statusCode());
    observed.put("body", normalize(resp.asString()));
    return observed;
  }

  /** 只替换两个逐次变化的值；键序、其余字段与转义保持原样。 */
  static String normalize(String body) {
    return body.replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"<traceId>\"")
        .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<timestamp>\"");
  }

  @Test
  void invalidRequestResponsesAreByteIdentical() throws IOException {
    Map<String, Object> actual = new LinkedHashMap<>();
    cases().forEach((name, c) -> actual.put(name, observe(c[0], c[1])));
    assertGolden(GOLDEN, actual);
  }

  /** 与 golden 逐例比对；golden 不存在时写出并失败（录制只发生在改动前的代码上）。 */
  static void assertGolden(String resource, Map<String, Object> actual) throws IOException {
    Map<String, Map<String, Object>> golden = readGolden(resource);
    if (golden == null) {
      Path source = Path.of("src/test/resources", resource);
      Files.createDirectories(source.getParent());
      Files.writeString(source, JSON.writeValueAsString(actual) + "\n", StandardCharsets.UTF_8);
      fail("golden 不存在，已按当前实现写出 " + source + "，审阅后提交再重跑");
    }
    List<String> names = new ArrayList<>(actual.keySet());
    assertThat(golden.keySet()).containsExactlyElementsOf(names);
    for (String name : names) {
      assertThat(actual.get(name)).as(name).isEqualTo(golden.get(name));
    }
  }

  private static Map<String, Map<String, Object>> readGolden(String resource) throws IOException {
    try (InputStream in = GuardInvalidRequestRegressionTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { });
    }
  }
}
