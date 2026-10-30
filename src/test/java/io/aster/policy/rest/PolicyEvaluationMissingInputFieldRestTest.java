package io.aster.policy.rest;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输入缺少规则读取的字段时的 HTTP 契约（生产复测第 6 轮第 4 项）：evaluate-source / evaluate-json
 * 回 400 + {@code code=missing_input_field} + {@code field=<字段路径>}，error 不含 Java 异常类、Polyglot 等内部细节；
 * 此前 evaluate-source 回 200 + 「CNL 动态执行失败: Polyglot 执行失败: java.lang.RuntimeException: …」。
 * 缺少没读到的字段、带多余字段时仍 200 正常执行。
 *
 * <p>复用 {@link EvaluateSourceWatchdogTest.HmacProfile}，避免为本类单独重启 Quarkus。
 */
@QuarkusTest
@TestProfile(EvaluateSourceWatchdogTest.HmacProfile.class)
class PolicyEvaluationMissingInputFieldRestTest {

    private static final String HMAC_KEY = "it-internal-caller-key-32bytes-min!";
    private static final String TENANT = "tenant-missing-field";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = String.join("\n",
        "Module finance.loan.", "",
        "Define Applicant has id as Text, creditScore as Int, age as Int.", "",
        "Rule evaluateLoan given applicant as Applicant, produce Text:",
        "  If applicant.creditScore greater than 750",
        "    Return \"Good credit\".",
        "  Return \"Manual review\".");

    private static Map<String, Object> applicant(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Response evaluateSource(Map<String, Object> context) throws Exception {
        String path = "/api/v1/policies/evaluate-source";
        String body = MAPPER.writeValueAsString(Map.of("source", SOURCE, "context", context,
            "functionName", "evaluateLoan", "locale", "en-US"));
        return given().header("Content-Type", "application/json")
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", body, TENANT, "MEMBER", "", List.of()))
            .body(body)
            .when().post(path);
    }

    private static Response evaluateJson(Map<String, Object> context) throws Exception {
        CoreModel.Module core = new CoreLowering().lowerModule(InProcessCnlParser.parse(SOURCE, "en-US").module());
        String body = MAPPER.writeValueAsString(Map.of("policy", MAPPER.writeValueAsString(core), "context", context));
        return given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/policies/evaluate-json");
    }

    private static void assertMissingField(Response rejected, String path) {
        assertThat(rejected.statusCode()).as(rejected.asString()).isEqualTo(400);
        assertThat(rejected.jsonPath().getString("code")).isEqualTo("missing_input_field");
        assertThat(rejected.jsonPath().getString("field")).isEqualTo(path);
        assertThat(rejected.jsonPath().getString("error"))
            .contains(path)
            .doesNotContain("java.", "Polyglot", "RuntimeException", "Map 中不存在键");
        assertThat(rejected.asString()).doesNotContain("\tat ", "Caused by");
    }

    private static void assertResult(Response ok, String expected) {
        assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
        assertThat(ok.jsonPath().getString("result")).isEqualTo(expected);
        assertThat(ok.jsonPath().getString("error")).isNull();
        assertThat(ok.jsonPath().getString("field")).isNull();
    }

    @Test
    void evaluate_source_缺少读取的字段_400_missing_input_field_带路径() throws Exception {
        assertMissingField(evaluateSource(Map.of("applicant", applicant("id", "A1", "age", 35))),
            "applicant.creditScore");
    }

    @Test
    void evaluate_source_缺少没读到的字段或带多余字段_照常200() throws Exception {
        assertResult(evaluateSource(Map.of("applicant", applicant("creditScore", 800, "age", 35))), "Good credit");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("applicant", applicant("id", "A1", "creditScore", 800, "age", 35, "nickname", "Al"));
        extra.put("foo", 1);
        assertResult(evaluateSource(extra), "Good credit");
    }

    @Test
    void evaluate_json_缺少读取的字段_400_missing_input_field_带路径() throws Exception {
        assertMissingField(evaluateJson(Map.of("applicant", applicant("id", "A1"))), "applicant.creditScore");
    }
}
