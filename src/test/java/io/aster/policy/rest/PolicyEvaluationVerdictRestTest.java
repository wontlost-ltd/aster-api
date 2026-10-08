package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0039 §5.1 端到端：/evaluate-source 响应上的治理字段 decision / ruleId / controls。
 *
 * <p>覆盖两条契约：
 * <ol>
 *   <li>返回 Verdict 且带 {@code @id} / {@code @control} 的策略 → 响应含结构化 decision（键按字母序、
 *       缺省键不写出）、ruleId、controls。</li>
 *   <li>返回 Bool 的普通策略 → 三个字段都不出现在 JSON 中（非治理策略响应字节不变）。</li>
 * </ol>
 *
 * <p>沿用 {@link PolicyEvaluationReplayOrderingTest.HmacProfile}：该 profile 下 /evaluate-source 只接受 HMAC
 * 已验证的内部调用方请求，因此本类所有请求都签名。
 */
@QuarkusTest
@TestProfile(PolicyEvaluationReplayOrderingTest.HmacProfile.class)
class PolicyEvaluationVerdictRestTest {

    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
    private static final String TENANT = "tenant-verdict-rest-test";
    private static final String ROLE = "MEMBER";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VERDICT_SOURCE = String.join("\n",
        "Module probe.",
        "",
        "@id(\"VB-001\")",
        "@control(\"EU_AI_ACT:ART14\")",
        "Rule main given amount as Int, produce Verdict:",
        "  Return Verdict.require_approval(\"Senior Underwriter\", \"over cap\").");

    private static final String BOOL_SOURCE = String.join("\n",
        "Module probe.",
        "",
        "Rule main given amount as Int, produce Bool:",
        "  Return amount at least 0.");

    @Test
    void verdictPolicy_responseCarriesDecisionRuleIdAndControls() throws Exception {
        JsonNode body = evaluate(VERDICT_SOURCE, "verdict");

        assertTrue(body.path("error").isNull(), body.toString());
        assertEquals(
            "{\"outcome\":\"REQUIRE_APPROVAL\",\"reason\":\"over cap\",\"role\":\"Senior Underwriter\"}",
            MAPPER.writeValueAsString(body.get("decision")),
            body.toString());
        assertEquals("VB-001", body.path("ruleId").asText(), body.toString());
        assertEquals("[\"EU_AI_ACT:ART14\"]", MAPPER.writeValueAsString(body.get("controls")), body.toString());
    }

    @Test
    void boolPolicy_responseOmitsGovernanceFields() throws Exception {
        JsonNode body = evaluate(BOOL_SOURCE, "bool");

        assertTrue(body.path("error").isNull(), body.toString());
        assertTrue(body.path("result").asBoolean(), body.toString());
        assertFalse(body.has("decision"), body.toString());
        assertFalse(body.has("ruleId"), body.toString());
        assertFalse(body.has("controls"), body.toString());
    }

    /** 发起一次 HMAC 已验证的 evaluate-source 请求并返回解析后的 JSON 响应体（断言 200）。 */
    private static JsonNode evaluate(String source, String tag) throws Exception {
        var request = MAPPER.createObjectNode();
        request.put("source", source);
        request.putObject("context").put("amount", 1);
        request.put("functionName", "main");
        request.put("locale", "en-US");
        String body = MAPPER.writeValueAsString(request);

        String query = "trace=false&replayCapture=false";
        Response resp = given()
            .header("Content-Type", "application/json")
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, query, body, TENANT, ROLE, "", List.of()))
            .body(body)
            .when()
            .post(PATH + "?" + query);
        assertEquals(200, resp.statusCode(), resp.asString());
        return MAPPER.readTree(resp.asString());
    }
}
