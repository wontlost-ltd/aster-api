package io.aster.policy.rest;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.InProcessCnlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * aster-api#558 端到端：{@code /evaluate-json} 按声明类型转换 Decimal / Long 入参。
 * 第一个用例是 aster-lang-test conformance {@code package-v1/src/a-decimal}（源码逐字），期望值取其 {@code expected.json}
 * 的 {@code fractional-input}（{@code source:"ts"}，即平台此前算不出、只能取自 ts 解释器的那一条）。
 */
@QuarkusTest
class EvaluateJsonDecimalInputTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TENANT = "tenant-evaluate-json-558";

  private static final String A_DECIMAL = String.join("\n",
      "Module fixture.decimal.",
      "",
      "Rule withTax given amount as Decimal, produce Decimal:",
      "  Return Decimal.round(amount times 1.0825m, 2, \"HALF_EVEN\").",
      "");

  private static final String NESTED = String.join("\n",
      "Module t.nested.",
      "",
      "Define Claim has amount as Decimal, lines as List of Decimal.",
      "",
      "Rule total given claim as Claim, produce Decimal:",
      "  Return claim.amount plus List.length(claim.lines).",
      "");

  private static final String LONG = String.join("\n",
      "Module t.long.",
      "",
      "Rule echoLong given n as Long, produce Long:",
      "  Return n plus 1.",
      "");

  private static Response evaluateJson(String source, Map<String, Object> context) throws Exception {
    CoreModel.Module core = new CoreLowering().lowerModule(InProcessCnlParser.parse(source, "en-US").module());
    String body = MAPPER.writeValueAsString(Map.of("policy", MAPPER.writeValueAsString(core), "context", context));
    return given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(body)
        .when().post("/api/v1/policies/evaluate-json");
  }

  private static JsonNode ok(Response r) throws Exception {
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(r.asString());
    assertThat(body.path("error").isMissingNode() || body.path("error").isNull()).as(r.asString()).isTrue();
    return body.get("result");
  }

  @Test
  void conformanceADecimalFractionalInputMatchesTs() throws Exception {
    assertThat(ok(evaluateJson(A_DECIMAL, Map.of("amount", "12.34"))).asText()).isEqualTo("13.36");
    assertThat(ok(evaluateJson(A_DECIMAL, Map.of("amount", 12.34))).asText()).isEqualTo("13.36");
    assertThat(ok(evaluateJson(A_DECIMAL, Map.of("amount", 10))).asText()).isEqualTo("10.82");
  }

  @Test
  void invalidDecimalStringIs400WithPath() throws Exception {
    Response r = evaluateJson(A_DECIMAL, Map.of("amount", "12,34"));
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    assertThat(r.asString()).contains("amount: expected Decimal");
  }

  @Test
  void nestedDataFieldAndListElements() throws Exception {
    Map<String, Object> claim = Map.of("amount", "0.1", "lines", java.util.List.of("0.2", 0.3));
    assertThat(ok(evaluateJson(NESTED, Map.of("claim", claim))).asText()).isEqualTo("2.1");
    Response bad = evaluateJson(NESTED, Map.of("claim", Map.of("amount", "1", "lines", java.util.List.of("x"))));
    assertThat(bad.statusCode()).as(bad.asString()).isEqualTo(400);
    assertThat(bad.asString()).contains("claim.lines[0]: expected Decimal");
  }

  @Test
  void longStringBeyond2Pow53IsExact() throws Exception {
    JsonNode result = ok(evaluateJson(LONG, Map.of("n", "9007199254740993")));
    assertThat(result.isIntegralNumber()).as(result.toString()).isTrue();
    assertThat(result.bigIntegerValue().toString()).isEqualTo("9007199254740994");
  }
}
