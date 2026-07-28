package io.aster.policy.rest;

import io.aster.policy.tenant.TenantContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RequestIdentityResolver} 的解析顺序契约（issue #174 探勘发现的租户隔离漂移）。
 *
 * <p>此前 4 个 resource 各自手写 {@code tenantId()}，只有 PolicyEvaluationResource 带
 * R32 hotfix（读 JAX-RS ctx property）。**带有效 API key 但不带 X-Tenant-Id** 的请求
 * 因此在另两个 resource 上回退成 "default"——真实的跨租户记账错误。
 *
 * <p>这些用例把三级回退顺序钉死，防止补丁再次只打到一个副本上。
 */
@DisplayName("RequestIdentityResolver 身份解析顺序")
class RequestIdentityResolverTest {

    private RequestIdentityResolver resolver(TenantContext tc, ContainerRequestContext ctx,
                                             RoutingContext rctx) throws Exception {
        RequestIdentityResolver r = new RequestIdentityResolver();
        set(r, "tenantContext", tc);
        set(r, "jaxrsCtx", ctx);
        set(r, "routingContext", rctx);
        return r;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = RequestIdentityResolver.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static RoutingContext rctxWith(String header, String value) {
        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        when(req.getHeader(header)).thenReturn(value);
        return rctx;
    }

    private static ContainerRequestContext ctxWith(String key, String value) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(key)).thenReturn(value);
        return ctx;
    }

    // ── tenantId ───────────────────────────────────────────────────────

    @Test
    @DisplayName("★R32：有效 API key 但无 X-Tenant-Id → 读 ctx property，不回退 default")
    void tenantFromJaxrsPropertyWhenHeaderMissing() throws Exception {
        // 这正是漂移造成的真实缺陷场景：AuditLog/AiAssistant 此前会返回 "default"
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_TENANT_ID, "acme"),
            rctxWith("X-Tenant-Id", null));

        assertEquals("acme", r.tenantId(),
            "ApiKeyAuthFilter 写入的权威租户必须被读到，不能回退 default");
    }

    @Test
    @DisplayName("R29：TenantContext 优先于 header（trial 流量记到 trial）")
    void tenantContextWinsOverHeader() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");
        var r = resolver(tc, ctxWith(RequestIdentityResolver.PROP_TENANT_ID, "ignored"),
            rctxWith("X-Tenant-Id", "default"));

        assertEquals("trial", r.tenantId());
    }

    @Test
    @DisplayName("三级回退：无 context / 无 property → 读 header")
    void tenantFallsBackToHeader() throws Exception {
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class),
            rctxWith("X-Tenant-Id", " acme "));

        assertEquals("acme", r.tenantId(), "应 trim");
    }

    @Test
    @DisplayName("全都拿不到 → default")
    void tenantDefaultsWhenNothingAvailable() throws Exception {
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class), null);
        assertEquals("default", r.tenantId());
    }

    @Test
    @DisplayName("★未初始化的 TenantContext 应回退而非抛异常（原实现的潜伏 bug）")
    void uninitializedTenantContextFallsBackInsteadOfThrowing() throws Exception {
        // TenantContext.getCurrentTenant() 在未初始化时抛 IllegalStateException
        // （TenantContext:22）。各 resource 原实现只判 != null、没判 isInitialized()，
        // 于是 TenantFilter 未跑到的路径会直接抛异常，而不是回退 header。
        // 实测确认原 PolicyEvaluationResource.tenantId() 会抛：
        //   LATENT original tenantId() THREW java.lang.IllegalStateException
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class),
            rctxWith("X-Tenant-Id", "acme"));

        assertEquals("acme", r.tenantId(),
            "未初始化的 TenantContext 必须被跳过并回退 header，而非抛异常");
    }

    // ── performedBy ────────────────────────────────────────────────────

    @Test
    @DisplayName("R29：trial 租户统一记为 trial-anonymous")
    void performedByTrialAnonymous() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");
        var r = resolver(tc, ctxWith(RequestIdentityResolver.PROP_USER_ID, "someone"), null);

        assertEquals("trial-anonymous", r.performedBy(),
            "trial 流量必须与普通 anonymous 区分，便于审计分离");
    }

    @Test
    @DisplayName("R32：优先读 ctx property 的已验证 userId")
    void performedByFromJaxrsProperty() throws Exception {
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_USER_ID, "u-1"),
            rctxWith("X-User-Id", "header-user"));

        assertEquals("u-1", r.performedBy());
    }

    @Test
    @DisplayName("无任何来源 → anonymous")
    void performedByDefaultsAnonymous() throws Exception {
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class), null);
        assertEquals("anonymous", r.performedBy());
    }

    // ── apiKeyId ───────────────────────────────────────────────────────

    @Test
    @DisplayName("apiKeyId 优先读 ctx property")
    void apiKeyIdFromProperty() throws Exception {
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_API_KEY_ID, "key-1"),
            rctxWith("X-Api-Key-Id", "header-key"));

        assertEquals("key-1", r.apiKeyId());
    }

    @Test
    @DisplayName("无 key 的请求 → null（而非空串）")
    void apiKeyIdNullWhenAbsent() throws Exception {
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class), null);
        assertNull(r.apiKeyId());
    }
}
