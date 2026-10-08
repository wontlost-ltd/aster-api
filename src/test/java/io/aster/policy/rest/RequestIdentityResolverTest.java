package io.aster.policy.rest;

import io.aster.policy.tenant.TenantContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

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
    @DisplayName("ADR 0042 §4.1：apikey userId 优先于 HMAC 签过的 userId")
    void performedByApiKeyUserWinsOverSignedIdentity() throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(RequestIdentityResolver.PROP_USER_ID)).thenReturn("key-user");
        when(ctx.getProperty(RequestIdentityResolver.PROP_IDENTITY_USER_ID)).thenReturn("signed-user");
        var r = resolver(new TenantContext(), ctx, rctxWith("X-User-Id", "header-user"));

        assertEquals("key-user", r.performedBy());
    }

    @Test
    @DisplayName("ADR 0042 §4.1：无 apikey 时取 HMAC 签过的 userId，而非未签头")
    void performedBySignedIdentityWinsOverHeader() throws Exception {
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_IDENTITY_USER_ID, "signed-user"),
            rctxWith("X-User-Id", "header-user"));

        assertEquals("signed-user", r.performedBy());
    }

    @Test
    @DisplayName("ADR 0042 §4.1：签过的 userId 为空白 → anonymous，绝不回退读未签头")
    void performedByBlankSignedIdentityIsAnonymousNotHeader() throws Exception {
        for (String blank : new String[] {"", "   "}) {
            var r = resolver(new TenantContext(),
                ctxWith(RequestIdentityResolver.PROP_IDENTITY_USER_ID, blank),
                rctxWith("X-User-Id", "header-user"));

            assertEquals("anonymous", r.performedBy());
        }
    }

    @Test
    @DisplayName("ADR 0042 §4.1：签过的 userId 去首尾空白")
    void performedBySignedIdentityTrimmed() throws Exception {
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_IDENTITY_USER_ID, "  signed-user "),
            rctxWith("X-User-Id", "header-user"));

        assertEquals("signed-user", r.performedBy());
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

    @Test
    @DisplayName("★客户端伪造 X-Api-Key-Id 不被采信——无已验证 property 时必须为 null")
    void apiKeyIdIgnoresClientHeader() throws Exception {
        // ApiKeyAuthFilter.shouldProtect() 显式排除 evaluate-source（改由
        // InternalCallerFilter 守护），那条路径上 property 与 header 都不会被
        // filter 写入 → 若回退读 header，就是直接采信客户端自称的 key id。
        //
        // 这不是信息泄露而是**记账串号**：apiKeyId 是 apiQuotaGuard.checkRate 的
        // 限流桶键、也是 recordAsync 的计费归属，填别人的 id 即可消耗他人速率
        // 预算并把调用记到他人账上。
        var r = resolver(new TenantContext(),
            mock(ContainerRequestContext.class),          // 无已验证 property
            rctxWith("X-Api-Key-Id", "victim-key-id"));   // 攻击者自带的头

        assertNull(r.apiKeyId(),
            "未经 ApiKeyAuthFilter 验证的 X-Api-Key-Id 头不得被采信");
    }

    // ── quotaOwnerId ───────────────────────────────────────────────────

    @Test
    @DisplayName("ADR 0015：quotaOwnerId 读 ApiKeyAuthFilter 写入的 ctx property")
    void quotaOwnerIdFromProperty() throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(RequestIdentityResolver.PROP_USER_ID)).thenReturn("member-1");
        when(ctx.getProperty(RequestIdentityResolver.PROP_QUOTA_OWNER_ID)).thenReturn("owner-1");
        var r = resolver(new TenantContext(), ctx, null);

        assertEquals("owner-1", r.quotaOwnerId(), "团队 key 的配额归属是 team owner");
        assertEquals("member-1", r.performedBy(), "操作者仍是调用成员本人");
    }

    @Test
    @DisplayName("ADR 0015：quotaOwnerId 属性缺失（旧 cloud / 无 key）→ 等于 performedBy()")
    void quotaOwnerIdFallsBackToPerformedBy() throws Exception {
        var r = resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_USER_ID, "u-1"), null);

        assertEquals("u-1", r.quotaOwnerId());
        assertEquals(r.performedBy(), r.quotaOwnerId());
    }

    @Test
    @DisplayName("ADR 0015：trial 流量 quotaOwnerId 与 performedBy 一致（trial-anonymous）")
    void quotaOwnerIdTrialFollowsPerformedBy() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");
        var r = resolver(tc, mock(ContainerRequestContext.class), null);

        assertEquals("trial-anonymous", r.quotaOwnerId());
    }

    @Test
    @DisplayName("ADR 0015：quotaOwnerId 属性空白 → 回退 performedBy()；带空白的值去首尾空白")
    void quotaOwnerIdBlankPropertyFallsBack_paddedPropertyTrimmed() throws Exception {
        ContainerRequestContext blank = mock(ContainerRequestContext.class);
        when(blank.getProperty(RequestIdentityResolver.PROP_USER_ID)).thenReturn("u-1");
        when(blank.getProperty(RequestIdentityResolver.PROP_QUOTA_OWNER_ID)).thenReturn("   ");
        assertEquals("u-1", resolver(new TenantContext(), blank, null).quotaOwnerId());

        assertEquals("owner-1", resolver(new TenantContext(),
            ctxWith(RequestIdentityResolver.PROP_QUOTA_OWNER_ID, " owner-1 "), null).quotaOwnerId());
    }

    // ── businessRoles（ADR 0042 §2.2）──────────────────────────────────

    @Test
    @DisplayName("ADR 0042：businessRoles 属性优先于头")
    void businessRolesPropertyWinsOverHeader() throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(RequestIdentityResolver.PROP_BUSINESS_ROLES)).thenReturn(List.of("Data Protection Officer"));
        var r = resolver(new TenantContext(), ctx, rctxWith("X-User-Business-Roles", "CEO"));

        assertEquals(List.of("Data Protection Officer"), r.businessRoles());
    }

    @Test
    @DisplayName("ADR 0042：无属性时读 X-User-Business-Roles，逗号切分、trim、去空")
    void businessRolesFallBackToHeaderSplitTrimmed() throws Exception {
        var r = resolver(new TenantContext(), mock(ContainerRequestContext.class),
            rctxWith("X-User-Business-Roles", " DPO , ,CISO"));

        assertEquals(List.of("DPO", "CISO"), r.businessRoles());
    }

    @Test
    @DisplayName("ADR 0042：无属性无头 → 空列表")
    void businessRolesEmptyWhenAbsent() throws Exception {
        assertEquals(List.of(), resolver(new TenantContext(), mock(ContainerRequestContext.class),
            rctxWith("X-User-Business-Roles", null)).businessRoles());
        assertEquals(List.of(), resolver(new TenantContext(), null, null).businessRoles());
    }
}
