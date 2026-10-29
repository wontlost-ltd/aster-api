package io.aster.policy.rest;

import io.aster.billing.ApiQuotaGuard;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API key 调用 {@code /evaluate} 的套餐门禁与计量：Free 计划的界面执行放行之后，API key 调用的限制一项不松。
 *
 * <p>测试配置下 API key 过滤器关闭：{@link RequestIdentityResolver} 被 mock 以提供「已验证」的 apiKeyId
 * （真实实现只信任过滤器写入的属性），{@link ApiQuotaGuard} 被 mock 以给出 Free / Pro 快照的裁决。
 */
@QuarkusTest
class PolicyEvaluationApiKeyQuotaTest {
    private static final String TENANT = "tenant-eval-key-quota-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String USER = "user-eval-key-quota";
    private static final String OWNER = "owner-eval-key-quota";
    private static final String API_KEY_ID = "key-eval-key-quota";
    private static final String MODULE = "eval.key.quota";
    private static final String FUNCTION = "decide";
    private static final String SOURCE = String.join("\n",
        "Module eval.key.quota.", "",
        "Rule decide given x as Int, produce Int:",
        "  Return x.");

    @InjectMock ApiQuotaGuard apiQuotaGuard;
    @InjectMock RequestIdentityResolver identityResolver;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
            PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
            PolicyVersion.delete("tenantId = ?1", TENANT);
            PolicyCatalog.delete("tenantId = ?1", TENANT);
        });
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
        DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, SOURCE);

        when(identityResolver.tenantId()).thenReturn(TENANT);
        when(identityResolver.performedBy()).thenReturn(USER);
        when(identityResolver.apiKeyId()).thenReturn(API_KEY_ID);
        when(identityResolver.quotaOwnerId()).thenReturn(OWNER);
        when(identityResolver.businessRoles()).thenReturn(List.of());
        when(apiQuotaGuard.checkRateAsync(any(), any(), any()))
            .thenReturn(Uni.createFrom().item(new ApiQuotaGuard.RateCheck(true, 1, 10)));
    }

    private static Response evaluate() {
        return given()
            .header("X-Tenant-Id", TENANT)
            .contentType(ContentType.JSON)
            .body(Map.of("policyModule", MODULE, "policyFunction", FUNCTION, "context", List.of(7)))
            .when().post("/api/v1/policies/evaluate");
    }

    @Test
    void Free计划API_key调用仍返回403且不计量() {
        when(apiQuotaGuard.check(TENANT, OWNER))
            .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.FORBIDDEN, 0L, 0L, 0, null));

        Response resp = evaluate();

        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(403);
        assertThat(resp.jsonPath().getString("error")).isEqualTo("api_access_denied");
        verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void Pro计划API_key调用放行并按key计量一次() {
        when(apiQuotaGuard.check(anyString(), anyString()))
            .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.ALLOW, 5000L, 10L, 0, null));

        Response resp = evaluate();

        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        verify(apiQuotaGuard, times(1)).check(TENANT, OWNER);
        verify(apiQuotaGuard, times(1)).checkRateAsync(TENANT, OWNER, API_KEY_ID);
        verify(apiQuotaGuard, times(1)).recordAsync(eq(OWNER), eq(USER), eq(TENANT), eq(API_KEY_ID),
            eq("/api/v1/policies/evaluate"), eq("success"), anyLong());
    }
}
