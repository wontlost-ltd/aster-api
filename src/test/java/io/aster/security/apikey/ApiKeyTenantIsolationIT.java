package io.aster.security.apikey;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * 真实链路集成测试：通过 RESTEasy Reactive 完整 filter 链（不是反射调用私有方法）
 * 验证 API key 认证 + 跨租户隔离 + CNL source 长度 DoS 上限。
 *
 * <p>补足 {@link ApiKeyAuthFilterTenantOverwriteTest}（反射单测，绕过了 filter
 * 顺序 / TenantFilter / async cache-miss 路径）的盲区。这里用真实 HTTP 请求，
 * 经过 ApiKeyAuthFilter → TenantFilter → 资源方法的实际链路。
 *
 * <p>测试 profile 打开 apikey 鉴权（默认 test profile 关闭），并预置一个有效 key
 * 到验证缓存（{@link ApiKeyVerifierService#seedCacheForTest}），从而无需真实
 * cloud verify 即可模拟"持有有效 key 的调用方"。signature 仍关闭以贴近生产
 * （生产 k3s 也关 signature，正是 ApiKeyAuthFilter 存在的原因）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(ApiKeyTenantIsolationIT.ApiKeyEnabledProfile.class)
class ApiKeyTenantIsolationIT {

    /** 打开 apikey 鉴权的测试 profile（其余安全层维持基类 test 配置）。 */
    public static class ApiKeyEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.security.apikey.enabled", "true",
                "aster.security.signature.enabled", "false",
                // 打开 RBAC，使 @RequireRole(ADMIN) 真正生效——否则伪造-ADMIN
                // 提权测试无法验证角色覆盖逻辑（base test profile 默认关 RBAC）。
                "aster.security.rbac.enabled", "true"
            );
        }
    }

    private static final String VALID_KEY = "ak_test_valid_tenant_a";
    private static final String TENANT_A = "tenant-a";

    @Inject
    ApiKeyVerifierService verifier;

    private static final String MEMBER_KEY = "ak_test_member_tenant_a";

    @BeforeEach
    void seedKey() {
        // 预置绑定 tenant-a 的有效 key（默认 MEMBER 角色），模拟 cloud verify。
        verifier.seedCacheForTest(
            VALID_KEY,
            ApiKeyVerifyResult.valid("ak-id-1", "user-1", TENANT_A, "pro", "active"));
        // 显式 MEMBER 角色的 key，用于验证"自带 ADMIN 头无法提权"。
        verifier.seedCacheForTest(
            MEMBER_KEY,
            ApiKeyVerifyResult.valid("ak-id-2", "user-2", TENANT_A, "pro", "active", "MEMBER"));
    }

    private static String evalBody() {
        return "{\"policyModule\":\"aster.x\",\"policyFunction\":\"y\",\"context\":[]}";
    }

    @Test
    void missingApiKey_returns401() {
        given()
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(401)
            .body(containsString("missing_authorization"));
    }

    @Test
    void bogusApiKey_returns401() {
        given()
            .header("Authorization", "Bearer ak_does_not_exist")
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(401);
    }

    @Test
    void workflowAuditEndpoints_withoutApiKey_return401() {
        // ★与 ApiKeyAuthFilterShouldProtectTest 不同轴：那边反射调 shouldProtect，
        //   只证明「路径谓词命中」；这里发**真实请求**，证明请求确实被拒。
        //   谓词对了但 filter 没接上，是这类修复最容易留下的假绿。
        //
        //   issue aster-api#296：这四个只读端点以 RequestIdentityResolver.tenantId()
        //   为租户谓词，而该解析器末级回退是裸 X-Tenant-Id header；生产签名关闭后
        //   无任何验证方，等于「带上受害者租户 ID 即可读其 workflow 事件与指标」。
        for (String path : new String[] {
            "/api/v1/workflows/3f2504e0-4f89-11d3-9a0c-0305e82c3301/events",
            "/api/v1/workflows/3f2504e0-4f89-11d3-9a0c-0305e82c3301/state",
            "/api/v1/workflows/metrics",
            "/api/v1/workflows/by-status/RUNNING",
        }) {
            given()
                .header("X-Tenant-Id", "victim-tenant")   // 伪造的受害者租户
                .header("X-User-Role", "ADMIN")           // 伪造的角色
            .when()
                .get(path)
            .then()
                .statusCode(401);
        }
    }

    @Test
    void auditEndpoint_withoutApiKey_returns401() {
        // 审计端点文档约定 Bearer 必填；无 key 必须 401（此前签名关闭时它
        // 不在鉴权覆盖内、bearer 形同虚设——现已纳入 ApiKeyAuthFilter）。
        given()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "ADMIN")
            .queryParam("from", "2024-01-01T00:00:00Z")
            .queryParam("to", "2024-01-31T23:59:59Z")
        .when()
            .get("/api/v1/audit/range")
        .then()
            .statusCode(401)
            .body(containsString("missing_authorization"));
    }

    @Test
    void auditEndpoint_mismatchedTenant_returns403() {
        // 跨租户隔离同样覆盖审计端点：持 tenant-a 的有效 key 带 victim 租户
        // 读审计 → 403 tenant_mismatch。
        given()
            .header("Authorization", "Bearer " + VALID_KEY)
            .header("X-Tenant-Id", "victim-tenant")
            .header("X-User-Role", "ADMIN")
            .queryParam("from", "2024-01-01T00:00:00Z")
            .queryParam("to", "2024-01-31T23:59:59Z")
        .when()
            .get("/api/v1/audit/range")
        .then()
            .statusCode(403)
            .body(containsString("tenant_mismatch"));
    }

    @Test
    void memberKey_forgedAdminHeader_cannotAccessAdminAudit() {
        // 提权防护核心断言：持普通 MEMBER key 的调用方自带 X-User-Role: ADMIN，
        // filter 会用已验证角色（MEMBER）无条件覆盖该头 → 下游 @RequireRole(ADMIN)
        // 拒绝（403 Insufficient permissions / Missing role），而不是被伪造头蒙混。
        int code = given()
            .header("Authorization", "Bearer " + MEMBER_KEY)
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "ADMIN") // 伪造的提权尝试
            .queryParam("from", "2024-01-01T00:00:00Z")
            .queryParam("to", "2024-01-31T23:59:59Z")
        .when()
            .get("/api/v1/audit/range")
        .then()
            .extract().statusCode();
        // 必须被 RBAC 拒绝（403）；绝不能因伪造头通过（200/业务码）。
        org.junit.jupiter.api.Assertions.assertEquals(403, code,
            "MEMBER key 自带 ADMIN 头不得通过 @RequireRole(ADMIN) 审计端点");
    }

    @Test
    void validKey_matchingTenantHeader_passesAuthLayer() {
        // header 租户与 key 租户一致 → 过认证层（之后可能因 module 不存在等
        // 返回业务错误，但绝不是 401/403 鉴权错误）。
        int code = given()
            .header("Authorization", "Bearer " + VALID_KEY)
            .header("X-Tenant-Id", TENANT_A)
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .extract().statusCode();
        org.junit.jupiter.api.Assertions.assertNotEquals(401, code, "一致租户不应 401");
        org.junit.jupiter.api.Assertions.assertNotEquals(403, code, "一致租户不应 403");
    }

    @Test
    void validKey_mismatchedTenantHeader_returns403() {
        // 核心跨租户隔离断言：持有 tenant-a 的有效 key，却带 X-Tenant-Id: victim
        // → 必须 403 tenant_mismatch（真实 filter 链路，不是反射）。
        given()
            .header("Authorization", "Bearer " + VALID_KEY)
            .header("X-Tenant-Id", "victim-tenant")
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(403)
            .body(containsString("tenant_mismatch"));
    }

    @Test
    void oversizedSource_rejectedByBeanValidationBeforeExpensiveWork() {
        // CNL source 长度上限（算法复杂度 DoS 防护）走真实 bean validation。
        // 注意：/schema 是匿名元数据端点（不在 ApiKeyAuthFilter.shouldProtect
        // 内），故此处不带 Authorization——本用例验证的是 @Size 上限在真实
        // 请求链路上把 >64KB 的 source 快速 400，而非进入超线性解析；API key
        // 鉴权由上面的 evaluate 用例覆盖。
        StringBuilder big = new StringBuilder(70_000);
        big.append("Module m.\\n\\nRule r given x as Number, produce:\\n  Return x. ");
        while (big.length() < 70_000) big.append('x');
        String body = "{\"source\":\"" + big + "\"}";

        given()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "MEMBER")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/api/v1/policies/schema")
        .then()
            .statusCode(400);
    }

    @Test
    void validKey_typicalSource_schemaSucceeds() {
        // 合法小策略仍正常工作（上限不误伤真实用例）。
        String body = "{\"source\":\"Module m.\\n\\nRule r given amount as Number, produce:\\n  Return amount < 100.\"}";
        given()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "MEMBER")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/api/v1/policies/schema")
        .then()
            .statusCode(200)
            .body(containsString("amount"));
    }

    @Test
    void schemaConcurrencySaturation_returns503WithRetryAfter() throws Exception {
        // 反射抽干 /schema 并发许可，模拟饱和：必须返回 503 且带 Retry-After，
        // 而不是放进解析把 worker 池拖垮。结束后释放，避免影响其它用例。
        java.lang.reflect.Field f = io.aster.policy.rest.PolicyEvaluationResource.class
            .getDeclaredField("ANON_PARSE_PERMITS");
        f.setAccessible(true);
        java.util.concurrent.Semaphore permits = (java.util.concurrent.Semaphore) f.get(null);
        int drained = permits.drainPermits();
        try {
            String body = "{\"source\":\"Module m.\\n\\nRule r given x as Number, produce:\\n  Return x.\"}";
            given()
                .header("X-Tenant-Id", TENANT_A)
                .header("X-User-Role", "MEMBER")
                .contentType("application/json")
                .body(body)
            .when()
                .post("/api/v1/policies/schema")
            .then()
                .statusCode(503)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue());
        } finally {
            permits.release(drained); // 还原许可
        }
    }

    @Test
    void validateConcurrencySaturation_returns503WithRetryAfter() throws Exception {
        // /validate 与 /schema 共用 ANON_PARSE_PERMITS，但 acquire/release 形态不同
        // （在方法入口 acquire、.eventually 释放）。单独钉住其饱和路径：抽干许可后
        // 必须 503 + Retry-After（在查库/编译前就被并发闸拦下）。
        java.lang.reflect.Field f = io.aster.policy.rest.PolicyEvaluationResource.class
            .getDeclaredField("ANON_PARSE_PERMITS");
        f.setAccessible(true);
        java.util.concurrent.Semaphore permits = (java.util.concurrent.Semaphore) f.get(null);
        int drained = permits.drainPermits();
        try {
            given()
                .header("X-Tenant-Id", TENANT_A)
                .header("X-User-Role", "MEMBER")
                .contentType("application/json")
                .body("{\"policyModule\":\"any.module\",\"policyFunction\":\"anyFn\"}")
            .when()
                .post("/api/v1/policies/validate")
            .then()
                .statusCode(503)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue());
        } finally {
            permits.release(drained);
        }
    }

    @Test
    void schemaAnonymous_noUserRole_notForbiddenUnderRbac() {
        // 端到端钉住 @AnonymousAllowed：rbac.enabled=true 且**不带 X-User-Role**，
        // /schema 也不应被 RBAC 判 403 Missing role。证明类级 @RequireRole(MEMBER)
        // 被方法级豁免覆盖、真匿名生效（与 ApiKeyAuthFilter.shouldProtect 设计一致）。
        String body = "{\"source\":\"Module m.\\n\\nRule r given x as Number, produce:\\n  Return x.\"}";
        given()
            .contentType("application/json")
            .body(body)
        .when()
            .post("/api/v1/policies/schema")
        .then()
            // 关键：不是 403。匿名可达；具体 200/解析结果由业务决定，这里只钉 RBAC 不拦。
            .statusCode(org.hamcrest.Matchers.not(403));
    }
}
