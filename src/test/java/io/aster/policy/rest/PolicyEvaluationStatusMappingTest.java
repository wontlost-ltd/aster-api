package io.aster.policy.rest;

import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * 评估端点的失败状态码（生产 E2E F11）。此前所有失败都是 HTTP 200 + error 文本，
 * 「没有已批准且启用的版本」这类可处理原因被吞成 "Policy evaluation failed"。
 *
 * <p>契约：失败返回对应状态，响应体仍是 EvaluationResponse，保留 error 并新增 code；
 * 批量为部分成功语义，单条失败内联在 results 中、整批 200。
 */
@QuarkusTest
class PolicyEvaluationStatusMappingTest {

    static final String TENANT = "tenant-status-mapping";
    static final String MODULE = "demo.status";

    static final String SOURCE = String.join("\n",
        "Module demo.status.", "",
        "Rule add2 given a as Int, b as Int, produce Int:",
        "  Return a plus b.");

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

    /** 部署一个版本；activate=false 时只留草稿（无默认版本），模拟「已创建但未批准/激活」。 */
    static void deploy(String function, boolean activate) {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = new PolicyCatalog();
            catalog.id = UUID.randomUUID(); catalog.tenantId = TENANT; catalog.moduleName = MODULE;
            catalog.functionName = function; catalog.domain = "test"; catalog.tags = "{}";
            catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
            catalog.persist();
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + "." + function; version.version = System.currentTimeMillis();
            version.moduleName = MODULE; version.functionName = function; version.content = SOURCE;
            version.locale = "en-US"; version.active = activate;
            version.status = activate ? VersionStatus.APPROVED : VersionStatus.DRAFT;
            version.tenantId = TENANT; version.createdAt = Instant.now(); version.createdBy = "status-test";
            version.persist();
            if (activate) {
                catalog.defaultVersionId = version.id;
                catalog.persist();
            }
        });
    }

    private static io.restassured.response.ValidatableResponse evaluate(String function) {
        return given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON)
            .body("""
                {"policyModule": "%s", "policyFunction": "%s", "context": [7, 5]}
                """.formatted(MODULE, function))
            .when().post("/api/v1/policies/evaluate")
            .then();
    }

    @Test
    void 成功_200_响应体不带code() {
        deploy("add2", true);
        evaluate("add2").statusCode(200)
            .body("result", equalTo(12))
            .body("error", nullValue())
            .body("code", nullValue());
    }

    @Test
    void 只有草稿版本_409_no_executable_version() {
        deploy("add2", false);
        evaluate("add2").statusCode(409)
            .contentType(ContentType.JSON)
            .body("code", equalTo("no_executable_version"))
            .body("error", containsString("no executable version"))
            .body("result", nullValue())
            .body("evidence.correlationId", notNullValue());
    }

    @Test
    void 策略不存在_404_policy_not_found() {
        evaluate("missing").statusCode(404)
            .body("code", equalTo("policy_not_found"))
            .body("error", containsString(MODULE + ".missing"));
    }

    @Test
    void 请求校验失败_400() {
        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON)
            .body("{\"policyModule\": \"\", \"policyFunction\": \"\", \"context\": []}")
            .when().post("/api/v1/policies/evaluate")
            .then().statusCode(400);
    }

    @Test
    void evaluate_json_非法_Core_IR_400_invalid_request() {
        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON)
            .body("{\"policy\": \"{not json\", \"context\": {}}")
            .when().post("/api/v1/policies/evaluate-json")
            .then().statusCode(400)
            .body("code", equalTo("invalid_request"))
            .body("error", notNullValue());
    }

    @Test
    void evaluate_json_没有可执行函数_404_function_not_found() {
        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON)
            .body("{\"policy\": \"{\\\"name\\\":\\\"demo.empty\\\",\\\"decls\\\":[]}\", \"context\": {}}")
            .when().post("/api/v1/policies/evaluate-json")
            .then().statusCode(404)
            .body("code", equalTo("function_not_found"));
    }

    @Test
    void 批量_单条失败内联带code_整批仍200() {
        deploy("add2", true);
        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON)
            .body("""
                {"requests": [
                  {"policyModule": "%1$s", "policyFunction": "add2", "context": [1, 2]},
                  {"policyModule": "%1$s", "policyFunction": "missing", "context": [1, 2]}
                ]}
                """.formatted(MODULE))
            .when().post("/api/v1/policies/evaluate/batch")
            .then().statusCode(200)
            .body("successCount", equalTo(1))
            .body("failureCount", equalTo(1))
            .body("results.code", hasItem("policy_not_found"))
            .body("results.error", hasItem(not(containsString("Exception"))));
    }
}
