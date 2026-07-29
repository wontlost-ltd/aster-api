package io.aster.policy.rest;

import io.aster.billing.ApiQuotaGuard;
import io.aster.policy.security.TrialEndpointGuard;
import io.aster.policy.tenant.TenantContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R29 Codex 复审钉：trial 身份契约必须在 resource 层有直接回归覆盖，
 * 不只靠 podman E2E。本测试用反射访问 private 辅助方法，验证：
 *
 * <ul>
 *   <li>{@code tenantId()} 优先读 {@code TenantContext}，trial 流量返回 "trial"。</li>
 *   <li>{@code performedBy()} 对 trial 流量返回 "trial-anonymous"。</li>
 *   <li>{@code enforceApiQuota()} 对 tenant="trial" 不调用 {@code apiQuotaGuard.check()}，
 *       不写 {@code X-Quota-*} 响应头。</li>
 *   <li>普通租户走原路径：调用 quota guard 并写头。</li>
 * </ul>
 */
class PolicyEvaluationResourceTrialIdentityTest {

    private PolicyEvaluationResource newResource(ApiQuotaGuard quotaGuard,
                                                  TenantContext tenantContext,
                                                  RoutingContext routingContext) throws Exception {
        return newResource(quotaGuard, tenantContext, routingContext, null);
    }

    private PolicyEvaluationResource newResource(ApiQuotaGuard quotaGuard,
                                                  TenantContext tenantContext,
                                                  RoutingContext routingContext,
                                                  ContainerRequestContext jaxrsCtx) throws Exception {
        PolicyEvaluationResource r = new PolicyEvaluationResource();

        setField(r, "apiQuotaGuard", quotaGuard);
        setField(r, "tenantContext", tenantContext);
        setField(r, "routingContext", routingContext);
        if (jaxrsCtx != null) {
            setField(r, "jaxrsCtx", jaxrsCtx);
        }
        // issue #174：身份解析已收敛到 RequestIdentityResolver（4 个 resource 共用，
        // 消除已漂移的 4 份手写副本）。手工构造 resource 时必须一并装配它——
        // 生产由 CDI 注入。这里用同一组 mock 装配，保持与被测 resource 看到的
        // context 完全一致。
        RequestIdentityResolver resolver = new RequestIdentityResolver();
        setResolverField(resolver, "tenantContext", tenantContext);
        setResolverField(resolver, "routingContext", routingContext);
        setResolverField(resolver, "jaxrsCtx", jaxrsCtx);
        setField(r, "identityResolver", resolver);
        return r;
    }

    private ContainerRequestContext jaxrsCtxWithGuardProp(boolean propSet) {
        ContainerRequestContext c = mock(ContainerRequestContext.class);
        if (propSet) {
            when(c.getProperty(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP))
                .thenReturn(Boolean.TRUE);
        }
        return c;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = PolicyEvaluationResource.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setResolverField(Object target, String name, Object value) throws Exception {
        Field f = RequestIdentityResolver.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] sig, Object... args)
        throws Exception {
        Method m = PolicyEvaluationResource.class.getDeclaredMethod(methodName, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    // M2.1b: toTraceSteps 递归转换 truffle drain map shape —— 该私有方法已在
    // P0-A S2-1a-0 Task 4 移入 io.aster.replay.core.ReplayExecutionCore（trace/vocab/
    // alias glue 逐字迁移，见该次重构）。等价覆盖迁到
    // replay/src/test/java/io/aster/replay/core/ReplayExecutionCoreTest
    // ，经其公开入口 buildDecisionTrace(...) 测试同一份嵌套 children 转换逻辑
    // （比反射调用 private 方法更贴近真实公开契约）。本类不再持有该方法，反射调用
    // 会因 NoSuchMethodException 失败，故整体删除，不留死测试。

    // ── captureIdentity（issue #174）───────────────────────────────────
    // 4 个端点原先各自手抄 tenantId()/performedBy()/apiKeyIdFromContext() 三行 +
    // 一段"必须在进入 Uni 前预捕获"的注释。抄漏一行不会有任何编译或测试报错，
    // 却会让 quota/计费计数在异步回调里静默丢失。收敛成 captureIdentity() 后，
    // 这几条断言锁住它与逐个调用等价。

    @Test
    @DisplayName("#174: captureIdentity() 与逐个调用三个 helper 等价")
    void captureIdentityMatchesIndividualHelpers() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("acme");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        when(req.getHeader("X-Tenant-Id")).thenReturn("acme");
        when(req.getHeader("X-Api-Key-Id")).thenReturn("key-abc");

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        Object identity = invoke(r, "captureIdentity", new Class<?>[]{});
        assertEquals(invoke(r, "tenantId", new Class<?>[]{}),
            recordComponent(identity, "tenantId"), "tenantId 必须一致");
        assertEquals(invoke(r, "performedBy", new Class<?>[]{}),
            recordComponent(identity, "performedBy"), "performedBy 必须一致");
        assertEquals(invoke(r, "apiKeyIdFromContext", new Class<?>[]{}),
            recordComponent(identity, "apiKeyId"), "apiKeyId 必须一致");
    }

    @Test
    @DisplayName("#174: captureIdentity() 捕获 apiKeyId —— 漏抓会让配额计数静默丢失")
    void captureIdentityCapturesApiKeyId() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("acme");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);

        // 2026-07-29 审计后：apiKeyId 只认 ApiKeyAuthFilter 验证后写入的 ctx property，
        // 不再回退读客户端的 X-Api-Key-Id 头（那条回退让攻击者可把调用记到他人账上）。
        // 本用例关心的是「是否在请求线程上抓取」这一跨线程契约，与取值来源无关，
        // 故改用已验证的 property 作桩，断言意图不变。
        ContainerRequestContext jaxrsCtx = mock(ContainerRequestContext.class);
        when(jaxrsCtx.getProperty("aster.apikey.apiKeyId")).thenReturn("key-xyz");

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx, jaxrsCtx);

        Object identity = invoke(r, "captureIdentity", new Class<?>[]{});
        assertEquals("key-xyz", recordComponent(identity, "apiKeyId"),
            "apiKeyId 必须在请求线程上被抓取——回调里 RequestScoped 已失效");
    }

    @Test
    @DisplayName("#174: trial 流量的 captureIdentity() 保持 trial 身份契约")
    void captureIdentityPreservesTrialContract() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        when(req.getHeader("X-Tenant-Id")).thenReturn("default");

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        Object identity = invoke(r, "captureIdentity", new Class<?>[]{});
        assertEquals("trial", recordComponent(identity, "tenantId"));
        assertEquals("trial-anonymous", recordComponent(identity, "performedBy"),
            "trial 流量必须记为 trial-anonymous（R29 契约不得被本次重构改变）");
    }

    /** 读取 RequestIdentity record 的某个分量（该 record 是 private）。 */
    private static Object recordComponent(Object rec, String name) throws Exception {
        Method m = rec.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(rec);
    }

    @Test
    @DisplayName("R29: tenantId() 读 TenantContext.trial，跳过 header fallback")
    void tenantIdPrefersContextOverHeader() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        // Header 故意装一个错的值，证明 context 优先生效
        when(req.getHeader("X-Tenant-Id")).thenReturn("default");

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        String tenant = (String) invoke(r, "tenantId", new Class<?>[]{});
        assertEquals("trial", tenant,
            "TenantContext 设为 trial 时必须返回 trial，不读 header");
    }

    @Test
    @DisplayName("R29: performedBy() 对 trial 流量返回 trial-anonymous")
    void performedByReturnsTrialAnonymousForTrialTenant() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        when(req.getHeader("X-User-Id")).thenReturn(null);

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        String user = (String) invoke(r, "performedBy", new Class<?>[]{});
        assertEquals("trial-anonymous", user);
    }

    @Test
    @DisplayName("R29++: enforceApiQuota() 三重校验通过 → 跳过 PlanGate，零 X-Quota 头")
    void enforceApiQuotaSkipsForTripleGatedTrial() throws Exception {
        // R29++ 三重校验：path + guard property + tenant 全部命中才旁路
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        PolicyEvaluationResource r = newResource(quota, tc, rctx,
            jaxrsCtxWithGuardProp(true));

        ApiQuotaGuard.GuardResult result = (ApiQuotaGuard.GuardResult)
            invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
                "/api/v1/policies/evaluate-source");

        verify(quota, never()).check(anyString(), anyString());
        verify(quota, never()).checkRate(anyString(), anyString());

        verify(resp, never()).putHeader(eqMatch("X-Quota-Limit"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Remaining"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Reset"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Warning"), anyString());

        assertEquals(ApiQuotaGuard.Verdict.ALLOW, result.verdict());
        assertEquals(-1L, result.limit());
    }

    @Test
    @DisplayName("R29++: tenant=trial 但 endpoint 不是 evaluate-source → 不旁路（防 sentinel 滥用）")
    void enforceApiQuotaDoesNotSkipForTrialTenantOnWrongPath() throws Exception {
        // 关键安全断言：即便 TenantContext 被设成 "trial"，只要请求路径
        // 不是 TRIAL_PATH，也必须走原 PlanGate 流程。这把 R29+ 的弱耦合补上。
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);
        when(req.getHeader("X-User-Id")).thenReturn(null);

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        when(quota.check(anyString(), anyString())).thenReturn(
            new ApiQuotaGuard.GuardResult(
                ApiQuotaGuard.Verdict.ALLOW, 1000L, 10L, 1, null));

        PolicyEvaluationResource r = newResource(quota, tc, rctx,
            jaxrsCtxWithGuardProp(true));  // guard prop 设了，但路径错

        invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
            "/api/v1/policies/evaluate");  // 注意：不是 evaluate-source

        verify(quota, times(1)).check(anyString(), anyString());
        verify(resp).putHeader(eqMatch("X-Quota-Limit"), anyString());
    }

    @Test
    @DisplayName("R29++: TRIAL_PATH + tenant=trial 但缺 guard property → 不旁路")
    void enforceApiQuotaDoesNotSkipWithoutGuardProperty() throws Exception {
        // path 对、tenant 对，但 jaxrsCtx 没有 TRIAL_GUARD_PASSED_PROP →
        // 不放行。证明 bypass 不能仅靠 TenantContext sentinel。
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        when(quota.check(anyString(), anyString())).thenReturn(
            new ApiQuotaGuard.GuardResult(
                ApiQuotaGuard.Verdict.ALLOW, 1000L, 10L, 1, null));

        // jaxrsCtx 不设 TRIAL_GUARD_PASSED_PROP
        PolicyEvaluationResource r = newResource(quota, tc, rctx,
            jaxrsCtxWithGuardProp(false));

        invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
            "/api/v1/policies/evaluate-source");

        verify(quota, times(1)).check(anyString(), anyString());
    }

    @Test
    @DisplayName("R29: 普通租户走原路径 —— PlanGate 调用 + X-Quota 头写入")
    void normalTenantStillEnforcesQuota() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("acme-corp");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);
        when(req.getHeader("X-User-Id")).thenReturn("ops@acme");

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        when(quota.check(anyString(), anyString())).thenReturn(
            new ApiQuotaGuard.GuardResult(
                ApiQuotaGuard.Verdict.ALLOW, 10_000L, 100L, 1, null));

        PolicyEvaluationResource r = newResource(quota, tc, rctx);

        ApiQuotaGuard.GuardResult result = (ApiQuotaGuard.GuardResult)
            invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
                "/api/v1/policies/evaluate");

        verify(quota, times(1)).check("acme-corp", "ops@acme");
        verify(resp).putHeader(eqMatch("X-Quota-Limit"), anyString());
        verify(resp).putHeader(eqMatch("X-Quota-Remaining"), anyString());
        verify(resp).putHeader(eqMatch("X-Quota-Reset"), anyString());

        assertEquals(ApiQuotaGuard.Verdict.ALLOW, result.verdict());
    }

    // Mockito eq helper for String — keeps imports tight in this test
    private static String eqMatch(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
