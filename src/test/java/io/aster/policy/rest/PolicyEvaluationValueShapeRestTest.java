package io.aster.policy.rest;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.runtime.PolyglotValues;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入参值边界在三个评估端点上的 HTTP 契约（aster-cloud#639 复审）：嵌套恰好到 {@link PolyglotValues#MAX_DEPTH}
 * 正常执行且原样返回；多一层回 400 + {@code input_too_deep}，而不是栈溢出或 500。
 *
 * <p>环与非字符串键无法用 JSON 表达，由 {@code PolyglotValuesTest} 在转换层覆盖。
 * 复用 {@link EvaluateSourceWatchdogTest.HmacProfile}，避免为本类单独重启 Quarkus。
 */
@QuarkusTest
@TestProfile(EvaluateSourceWatchdogTest.HmacProfile.class)
class PolicyEvaluationValueShapeRestTest {

    private static final String HMAC_KEY = "it-internal-caller-key-32bytes-min!";
    private static final String TENANT = "tenant-value-shape";
    private static final String MODULE = "demo.shape";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = String.join("\n",
        "Module demo.shape.", "",
        "Rule echo given x as Map Text to Int, produce Map Text to Int:",
        "  Return x.");

    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;

    @BeforeEach @Transactional
    void clean() {
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
        PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
        PolicyVersion.delete("tenantId = ?1", TENANT);
        PolicyCatalog.delete("tenantId = ?1", TENANT);
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
    }

    /** n 层嵌套映射，最外层计为第 1 层。 */
    private static Map<String, Object> nested(int levels) {
        Map<String, Object> current = new LinkedHashMap<>();
        for (int i = 1; i < levels; i++) {
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("a", current);
            current = outer;
        }
        return current;
    }

    private static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    private static void deploy() {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = new PolicyCatalog();
            catalog.id = UUID.randomUUID(); catalog.tenantId = TENANT; catalog.moduleName = MODULE;
            catalog.functionName = "echo"; catalog.domain = "test"; catalog.tags = "{}";
            catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
            catalog.persist();
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + ".echo"; version.version = System.currentTimeMillis();
            version.moduleName = MODULE; version.functionName = "echo"; version.content = SOURCE;
            version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
            version.tenantId = TENANT; version.createdAt = Instant.now(); version.createdBy = "shape-test";
            version.persist();
            catalog.defaultVersionId = version.id;
            catalog.persist();
        });
    }

    private static Response evaluate(Map<String, Object> x) throws Exception {
        String body = json(Map.of("policyModule", MODULE, "policyFunction", "echo", "context", List.of(x)));
        return given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/policies/evaluate");
    }

    private static Response evaluateJson(Map<String, Object> x) throws Exception {
        CoreModel.Module core = new CoreLowering().lowerModule(InProcessCnlParser.parse(SOURCE, "en-US").module());
        String body = json(Map.of("policy", MAPPER.writeValueAsString(core), "context", Map.of("x", x)));
        return given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/policies/evaluate-json");
    }

    private static Response evaluateSource(Map<String, Object> x) throws Exception {
        String path = "/api/v1/policies/evaluate-source";
        String body = json(Map.of("source", SOURCE, "context", Map.of("x", x), "functionName", "echo",
            "locale", "en-US"));
        return given().header("Content-Type", "application/json")
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", body, TENANT, "MEMBER", "", List.of()))
            .body(body)
            .when().post(path);
    }

    private static void assertEchoed(Response ok, Map<String, Object> input) throws Exception {
        assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
        assertThat(MAPPER.readTree(ok.asString()).get("result")).isEqualTo(MAPPER.valueToTree(input));
    }

    private static void assertTooDeep(Response rejected) {
        assertThat(rejected.statusCode()).as(rejected.asString()).isEqualTo(400);
        assertThat(rejected.jsonPath().getString("code")).isEqualTo("input_too_deep");
        assertThat(rejected.jsonPath().getString("error")).contains(String.valueOf(PolyglotValues.MAX_DEPTH));
    }

    @Test
    void evaluate_上限内原样返回_超限400_input_too_deep() throws Exception {
        deploy();
        assertEchoed(evaluate(nested(PolyglotValues.MAX_DEPTH)), nested(PolyglotValues.MAX_DEPTH));
        assertTooDeep(evaluate(nested(PolyglotValues.MAX_DEPTH + 1)));
    }

    @Test
    void evaluate_json_上限内原样返回_超限400_input_too_deep() throws Exception {
        assertEchoed(evaluateJson(nested(PolyglotValues.MAX_DEPTH)), nested(PolyglotValues.MAX_DEPTH));
        assertTooDeep(evaluateJson(nested(PolyglotValues.MAX_DEPTH + 1)));
    }

    @Test
    void evaluate_source_上限内原样返回_超限400_input_too_deep() throws Exception {
        assertEchoed(evaluateSource(nested(PolyglotValues.MAX_DEPTH)), nested(PolyglotValues.MAX_DEPTH));
        assertTooDeep(evaluateSource(nested(PolyglotValues.MAX_DEPTH + 1)));
    }

    @Test
    void 批量_形态违例单条内联input_too_deep_其余条目照常成功() throws Exception {
        deploy();
        Map<String, Object> ok = Map.of("score", 1);
        String body = json(Map.of("requests", List.of(
            Map.of("policyModule", MODULE, "policyFunction", "echo", "context", List.of(nested(PolyglotValues.MAX_DEPTH + 1))),
            Map.of("policyModule", MODULE, "policyFunction", "echo", "context", List.of(ok)))));

        Response resp = given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/policies/evaluate/batch");

        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getInt("successCount")).isEqualTo(1);
        assertThat(resp.jsonPath().getInt("failureCount")).isEqualTo(1);
        assertThat(resp.jsonPath().getList("results.code")).contains("input_too_deep");
        assertThat(MAPPER.readTree(resp.asString()).get("results").findValues("result"))
            .contains(MAPPER.valueToTree(ok));
    }
}
