package io.aster.guard.api;

import io.aster.billing.ApiQuotaGuard;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.rest.RequestIdentityResolver;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
 * guard 决策端点的配额、每 key 限流与计量（与 {@code PolicyEvaluationResource.enforceApiQuota} 同口径）。
 *
 * <p>测试配置下 plan gate 与 API key 过滤器关闭：{@link ApiQuotaGuard} 被 mock 以驱动各裁决分支，
 * {@link RequestIdentityResolver} 被 mock 以提供「已验证」的 apiKeyId（真实实现只信任过滤器写入的属性）。
 */
@QuarkusTest
class GuardDecisionResourceQuotaTest {
  private static final String TENANT = "tenant-guard-quota-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String USER = "user-guard-quota";
  private static final String API_KEY_ID = "key-guard-quota";
  private static final String MODULE = "guard.quota.allow";
  private static final String FUNCTION = "decide";
  private static final String ALLOW_SOURCE = String.join("\n",
      "Module guard.quota.allow.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.", "",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  Return Verdict.allow().");

  @InjectMock ApiQuotaGuard apiQuotaGuard;
  @InjectMock RequestIdentityResolver identityResolver;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void setUp() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
      PolicyVersion.delete("tenantId = ?1", TENANT);
      PolicyCatalog.delete("tenantId = ?1", TENANT);
    });
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, ALLOW_SOURCE);

    when(identityResolver.tenantId()).thenReturn(TENANT);
    when(identityResolver.performedBy()).thenReturn(USER);
    when(identityResolver.apiKeyId()).thenReturn(API_KEY_ID);
    when(apiQuotaGuard.check(anyString(), anyString()))
        .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.ALLOW, -1L, 0L, 0, null));
    when(apiQuotaGuard.checkRate(anyString(), anyString())).thenReturn(new ApiQuotaGuard.RateCheck(true, 1, 10));
  }

  private static Response post(String module) {
    Map<String, Object> action = Map.of(
        "principal", Map.of("id", "agent-42", "type", "agent", "roles", List.of("ops-bot")),
        "action", Map.of("name", "read_customer_record"),
        "resource", Map.of("type", "customer", "id", "C-1001"),
        "context", Map.of("amount", 1));
    return given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", USER)
        .contentType(ContentType.JSON)
        .body(Map.of("policyModule", module, "policyFunction", FUNCTION, "action", action))
        .when().post("/api/v1/guard/decisions");
  }

  @Test
  void 套餐不开放API返回403且不计量() {
    when(apiQuotaGuard.check(TENANT, USER))
        .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.FORBIDDEN, 0L, 0L, 0, null));

    Response resp = post(MODULE);

    assertThat(resp.statusCode()).isEqualTo(403);
    assertThat(resp.jsonPath().getMap("$")).isEqualTo(Map.of(
        "error", "api_access_denied",
        "message", "Free 计划不包含 Policy Execution API。请升级到 Pro/Team 或试用 Trial。"));
    verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void 月度硬上限返回429含limit与used() {
    when(apiQuotaGuard.check(TENANT, USER))
        .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.RATE_LIMITED, 1000L, 2001L, 200, null));

    Response resp = post(MODULE);

    assertThat(resp.statusCode()).isEqualTo(429);
    assertThat(resp.jsonPath().getString("error")).isEqualTo("api_quota_hard_exceeded");
    assertThat(resp.jsonPath().getString("message")).isEqualTo("本月 API 调用已超 200% 上限，已拒绝。请升级套餐或联系客服。");
    assertThat(resp.jsonPath().getLong("limit")).isEqualTo(1000L);
    assertThat(resp.jsonPath().getLong("used")).isEqualTo(2001L);
    verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void 每key每秒限流返回429_rate_limit_exceeded与Retry_After() {
    when(apiQuotaGuard.checkRate(TENANT, API_KEY_ID)).thenReturn(new ApiQuotaGuard.RateCheck(false, 11, 10));

    Response resp = post(MODULE);

    assertThat(resp.statusCode()).isEqualTo(429);
    assertThat(resp.header("Retry-After")).isEqualTo("1");
    assertThat(resp.jsonPath().getMap("$")).isEqualTo(Map.of(
        "error", "rate_limit_exceeded",
        "message", "Per-API-key per-second 限流：超过 10 RPS",
        "rps_limit", 10,
        "rps_used", 11));
    verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void 成功决策计量一次() {
    Response resp = post(MODULE);

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    verify(apiQuotaGuard, times(1)).checkRate(TENANT, API_KEY_ID);
    verify(apiQuotaGuard, times(1)).recordAsync(eq(USER), eq(TENANT), eq(API_KEY_ID),
        eq("/api/v1/guard/decisions"), eq("success"), anyLong());
  }

  @Test
  void 策略不存在不计量() {
    Response resp = post("guard.quota.missing");

    assertThat(resp.statusCode()).isEqualTo(404);
    verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), anyLong());
  }
}
