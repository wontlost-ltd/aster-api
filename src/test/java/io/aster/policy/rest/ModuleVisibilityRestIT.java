package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.PolicyVersion;
import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MODULE_NOT_VISIBLE 归一化的 HTTP 层回归（aster-api#458 ③，ADR 0015 不泄露模块存在性）。
 *
 * <p>{@code EvaluationDiagnosticsTest} 只比较 {@code EvaluationDiagnostics.module} 产出的 entity；这里真正发
 * HMAC 签名的 {@code POST /evaluate-source}，让 {@code ModuleResolver} → {@code DynamicCnlExecutor} →
 * {@code PolicyEvaluationResource} 的 catch 分支 → JAX-RS 异常处理链全部走一遍，断言「模块只有隐藏版本」
 * 「模块有隐藏版本但请求了不存在的版本」「模块根本不存在」三种情形的 400 响应逐字段相同，并按
 * {@code Accept-Language} 本地化。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(ModuleVisibilityRestIT.Profile.class)
class ModuleVisibilityRestIT {

    private static final String TENANT = "module-visibility-rest-it";
    private static final String HMAC_KEY = "module-visibility-458-key-32bytes!!";
    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 三种对外必须不可区分的情形：隐藏版本本身 / 隐藏模块的不存在版本 / 不存在的模块。 */
    private static final List<String> IMPORTS = List.of(
        "Use risk.Hidden version 1 as Score.",
        "Use risk.Hidden version 2 as Score.",
        "Use risk.Absent version 1 as Score.");

    @BeforeEach
    void persistHiddenLibrary() {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyVersion.delete("tenantId = ?1", TENANT);
            PolicyVersion hidden = new PolicyVersion("policy-" + TENANT + "-risk.Hidden-1", "risk.Hidden", "f",
                """
                Module risk.Hidden.

                Rule f given amount as Int, produce Int:
                  Return amount plus 1.
                """, "module-test", null);
            hidden.tenantId = TENANT;
            hidden.version = 1L;
            hidden.libraryVisible = false;
            hidden.persist();
        });
    }

    @AfterEach
    void cleanDb() {
        QuarkusTransaction.requiringNew().run(() -> PolicyVersion.delete("tenantId = ?1", TENANT));
    }

    private static String source(String importLine) {
        return String.join("\n",
            "Module app.Root.",
            importLine,
            "",
            "Rule run given amount as Int, produce Int:",
            "  Return amount.");
    }

    /** cloud BFF 视角的 HMAC 内部调用；请求体不带 locale，诊断语言由 Accept-Language 决定。 */
    private static Response evaluate(String importLine, String acceptLanguage) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("source", source(importLine));
        req.put("context", Map.of("amount", 1));
        req.put("functionName", "run");
        String body = MAPPER.writeValueAsString(req);
        var spec = given().contentType(ContentType.JSON)
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, TENANT, "MEMBER", "u-458",
                List.of()))
            .body(body);
        if (acceptLanguage != null) {
            spec = spec.header("Accept-Language", acceptLanguage);
        }
        return spec.post(PATH);
    }

    @ParameterizedTest(name = "Accept-Language={0} → {1}")
    @CsvSource(nullValues = "NULL", value = {
        "NULL, en",
        "zh-CN, zh",
        "'de-DE,de;q=0.9,en;q=0.8', de",
        "hi-IN, hi",
    })
    void hiddenAndAbsentModules_produceIdentical400Responses(String acceptLanguage, String lang) throws Exception {
        List<Response> responses = new java.util.ArrayList<>();
        for (String importLine : IMPORTS) {
            responses.add(evaluate(importLine, acceptLanguage));
        }
        Response reference = responses.get(0);
        assertThat(reference.statusCode()).as(reference.asString()).isEqualTo(400);
        assertThat(reference.contentType()).startsWith("application/json");
        JsonNode expected = MAPPER.readTree(reference.asString());

        // 经 REST 边界后的契约：code / reason 稳定，候选为空，error 与 message 同一句按请求语言取
        String message = EvaluationDiagnostics.text(lang, "module.NOT_VISIBLE");
        assertThat(expected.at("/diagnostics/0/code").asText()).isEqualTo("MODULE_NOT_VISIBLE");
        assertThat(expected.at("/diagnostics/0/reason").asText()).isEqualTo("NOT_VISIBLE");
        assertThat(expected.at("/diagnostics/0/candidates").isArray()).isTrue();
        assertThat(expected.at("/diagnostics/0/candidates")).isEmpty();
        assertThat(expected.at("/diagnostics/0/message").asText()).isEqualTo(message);
        assertThat(expected.at("/error").asText()).isEqualTo(message);
        assertThat(expected.toString()).doesNotContain("risk.Hidden").doesNotContain("risk.Absent")
            .doesNotContain("NOT_FOUND");

        for (int i = 1; i < responses.size(); i++) {
            Response other = responses.get(i);
            assertThat(other.statusCode()).as(IMPORTS.get(i) + ": " + other.asString()).isEqualTo(400);
            assertThat(MAPPER.readTree(other.asString())).as(IMPORTS.get(i)).isEqualTo(expected);
        }
    }

    @Test
    void visibleModule_stillResolvesThroughSameEndpoint() throws Exception {
        // 对照：同一端点、同一租户、可见的库模块能正常跨模块执行，证明 400 来自可见性归一化而非环境问题
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyVersion visible = new PolicyVersion("policy-" + TENANT + "-risk.Open-1", "risk.Open", "f",
                """
                Module risk.Open.

                Rule f given amount as Int, produce Int:
                  Return amount plus 41.
                """, "module-test", null);
            visible.tenantId = TENANT;
            visible.version = 1L;
            visible.libraryVisible = true;
            visible.persist();
        });
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("source", String.join("\n",
            "Module app.Root.",
            "Use risk.Open version 1 as Score.",
            "",
            "Rule run given amount as Int, produce Int:",
            "  Return Score.f(amount)."));
        req.put("context", Map.of("amount", 1));
        req.put("functionName", "run");
        String body = MAPPER.writeValueAsString(req);
        Response r = given().contentType(ContentType.JSON)
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, TENANT, "MEMBER", "u-458",
                List.of()))
            .body(body)
            .post(PATH);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getInt("result")).isEqualTo(42);
    }

    /** 跨模块特性开（同 ModuleResolverIT.ModulesEnabledProfile）+ evaluate-source 只接受 HMAC 内部调用（与生产口径一致）。 */
    public static class Profile extends PostgresAnalyticsTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var overrides = new java.util.HashMap<>(super.getConfigOverrides());
            overrides.put("aster.modules.enabled", "true");
            overrides.put("aster.plan-gate.hmac-key", HMAC_KEY);
            overrides.put("aster.security.signature.enabled", "false");
            overrides.put("aster.security.evaluate-source.public", "false");
            overrides.put("aster.security.evaluate-source.trial.enabled", "false");
            return overrides;
        }
    }
}
