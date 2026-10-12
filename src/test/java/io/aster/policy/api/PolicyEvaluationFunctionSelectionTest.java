package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.parser.EntryFunctionException;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * /evaluate 路径（PolicyEvaluationService → TrufflePolicyRuntime）同样必须执行所请求的函数。
 *
 * <p>部署记录按 module.function 定位，但 coreJson 是整个模块；此前运行时只 eval 整段 IR，
 * 由 Truffle 按 main → 第一个函数选入口：请求第二个函数拿到第一个函数的结果，
 * 请求不存在的函数也照样执行第一个函数而不报错。
 */
@QuarkusTest
class PolicyEvaluationFunctionSelectionTest {

    static final String TENANT = "tenant-function-selection";
    static final String MODULE = "demo.select";

    static final String ADD_FIRST = String.join("\n",
        "Module demo.select.", "",
        "Rule add2 given a as Int, b as Int, produce Int:",
        "  Return a plus b.", "",
        "Rule twice given a as Int, b as Int, produce Int:",
        "  Return add2(a, b) times 2.");

    static final String TWICE_FIRST = String.join("\n",
        "Module demo.select.", "",
        "Rule twice given a as Int, b as Int, produce Int:",
        "  Return add2(a, b) times 2.", "",
        "Rule add2 given a as Int, b as Int, produce Int:",
        "  Return a plus b.");

    static final String ZH_ADD_FIRST = String.join("\n",
        "模块 demo.select。", "",
        "规则 add2 给定 a 作为 整数，b 作为 整数 产出 整数：",
        "  返回 a 加上 b。", "",
        "规则 twice 给定 a 作为 整数，b 作为 整数 产出 整数：",
        "  返回 add2(a, b) 乘以 2。");

    static final String WITH_MAIN = String.join("\n",
        "Module demo.select.", "",
        "Rule main given a as Int, b as Int, produce Int:",
        "  Return a plus b.", "",
        "Rule helper given a as Int, b as Int, produce Int:",
        "  Return a times b.");

    @Inject PolicyEvaluationService evaluationService;
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

    /** 在独立事务中部署目录 + 活跃版本（与 DeployedMapParamProbeTest.deploy 同形，多一个 locale）。 */
    static void deploy(String function, String source, String locale) {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = new PolicyCatalog();
            catalog.id = UUID.randomUUID(); catalog.tenantId = TENANT; catalog.moduleName = MODULE;
            catalog.functionName = function; catalog.domain = "test"; catalog.tags = "{}";
            catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
            catalog.persist();
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + "." + function; version.version = System.currentTimeMillis();
            version.moduleName = MODULE; version.functionName = function; version.content = source;
            version.locale = locale; version.active = true; version.status = VersionStatus.APPROVED;
            version.tenantId = TENANT; version.createdAt = Instant.now(); version.createdBy = "selection-test";
            version.persist();
            catalog.defaultVersionId = version.id; catalog.persist();
        });
    }

    private Object evaluate(String function) {
        PolicyEvaluationResult result = evaluationService
            .evaluatePolicy(TENANT, MODULE, function, new Object[]{7, 5}).await().indefinitely();
        return result.getResult();
    }

    private static String requestBody(String function) {
        return """
            {"policyModule": "%s", "policyFunction": "%s", "context": [7, 5]}
            """.formatted(MODULE, function);
    }

    @Test
    void 请求第二个函数时返回第二个函数的结果() {
        deploy("twice", ADD_FIRST, "en-US");

        assertThat(evaluate("twice")).isEqualTo(24);
    }

    @Test
    void 请求第一个函数时行为不变() {
        deploy("add2", ADD_FIRST, "en-US");

        assertThat(evaluate("add2")).isEqualTo(12);
    }

    @Test
    void 顺序反过来时请求第二个函数() {
        deploy("add2", TWICE_FIRST, "en-US");

        assertThat(evaluate("add2")).isEqualTo(12);
    }

    @Test
    void 中文源码请求第二个函数() {
        deploy("twice", ZH_ADD_FIRST, "zh-CN");

        assertThat(evaluate("twice")).isEqualTo(24);
    }

    @Test
    void 请求不存在的函数时报错而不是执行第一个函数() {
        deploy("nosuch", ADD_FIRST, "en-US");

        assertThatThrownBy(() -> evaluate("nosuch"))
            .isInstanceOf(EntryFunctionException.class)
            .hasMessageContaining("'nosuch'")
            .hasMessageContaining("add2")
            .hasMessageContaining("twice");
    }

    @Test
    void 模块含main时请求其它函数报错而不是执行main() {
        deploy("helper", WITH_MAIN, "en-US");

        assertThatThrownBy(() -> evaluate("helper"))
            .isInstanceOf(EntryFunctionException.class)
            .hasMessageContaining("'helper'")
            .hasMessageContaining("'main'");
    }

    @Test
    void REST成功路径返回所请求函数的结果() {
        deploy("twice", ADD_FIRST, "en-US");

        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(requestBody("twice"))
            .when().post("/api/v1/policies/evaluate")
            .then().statusCode(200)
            .body("result", equalTo(24))
            .body("error", nullValue());
    }

    @Test
    void REST错误信息透出main抢占的真实原因() {
        deploy("helper", WITH_MAIN, "en-US");

        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(requestBody("helper"))
            .when().post("/api/v1/policies/evaluate")
            .then()
            .body("result", nullValue())
            .body("error", allOf(containsString("'helper'"), containsString("'main'"),
                not(containsString("Policy evaluation failed"))));
    }

    @Test
    void REST错误信息透出函数不存在的真实原因() {
        deploy("nosuch", ADD_FIRST, "en-US");

        given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body(requestBody("nosuch"))
            .when().post("/api/v1/policies/evaluate")
            .then()
            .body("result", nullValue())
            .body("error", allOf(containsString("'nosuch'"), not(containsString("Policy evaluation failed"))));
    }
}
