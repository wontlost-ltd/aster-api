package io.aster.security.apikey;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 跨租户隔离回归（行为级）：钉住 {@code applyResultSync} 的两条关键副作用——
 * <ul>
 *   <li>请求自带的 {@code X-Tenant-Id} 与 key 所属租户不一致 → 403 拒绝；</li>
 *   <li>验证通过后无条件用"已验证租户"覆盖 {@code X-Tenant-Id} header，
 *       使下游 TenantFilter/TenantContext 只会看到权威值。</li>
 * </ul>
 *
 * <p>与 {@link ApiKeyAuthFilterShouldProtectTest} 的纯函数测试互补：那里验证
 * "是否冲突"的判定，这里验证"冲突如何处置 + header 如何被改写"。
 */
class ApiKeyAuthFilterTenantOverwriteTest {

    /** 反射调用 private 实例方法 applyResultSync(ctx, result, path)。 */
    private static Response invokeApply(ContainerRequestContext ctx,
                                        ApiKeyVerifyResult result) throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter();
        Method m = ApiKeyAuthFilter.class.getDeclaredMethod(
            "applyResultSync", ContainerRequestContext.class, ApiKeyVerifyResult.class, String.class);
        m.setAccessible(true);
        return (Response) m.invoke(filter, ctx, result, "/api/v1/policies/evaluate");
    }

    @Test
    void mismatchedTenantHeaderIsRejectedWith403() throws Exception {
        FakeCtx ctx = new FakeCtx(Map.of("X-Tenant-Id", "attacker-target"));
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid("ak1", "u1", "tenant-a", "pro", "active");

        Response resp = invokeApply(ctx, result);

        assertEquals(403, resp.getStatus(), "header 租户与 key 租户不一致必须 403");
    }

    @Test
    void absentTenantHeaderIsFilledWithVerifiedTenant() throws Exception {
        FakeCtx ctx = new FakeCtx(Map.of()); // 无 X-Tenant-Id
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid("ak1", "u1", "tenant-a", "pro", "active");

        Response resp = invokeApply(ctx, result);

        assertNull(resp, "通过验证应返回 null（继续链路）");
        assertEquals("tenant-a", ctx.getHeaderString("X-Tenant-Id"),
            "缺失时应以已验证租户填充 header");
    }

    @Test
    void matchingTenantHeaderIsAllowedAndPropertySet() throws Exception {
        FakeCtx ctx = new FakeCtx(Map.of("X-Tenant-Id", "tenant-a"));
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid("ak1", "u1", "tenant-a", "pro", "active");

        Response resp = invokeApply(ctx, result);

        assertNull(resp, "header 租户与 key 一致应放行");
        assertEquals("tenant-a", ctx.getHeaderString("X-Tenant-Id"));
        assertEquals("tenant-a", ctx.getProperty("aster.apikey.tenantId"),
            "验证结果应写入 ctx property 供下游读取");
    }

    @Test
    void validResultWithMissingTenantBindingIsRejected() throws Exception {
        // 自洽 invariant：valid 但租户缺失 → fail-closed（403 invalid_tenant_binding），
        // 不能放行后依赖下游兜底（可能落到 "default" 租户）。
        FakeCtx ctx = new FakeCtx(Map.of());
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid("ak1", "u1", null, "pro", "active");

        Response resp = invokeApply(ctx, result);

        assertEquals(403, resp.getStatus(), "valid 但 tenant 缺失必须 403 拒绝");
    }

    @Test
    void quotaOwnerIdIsWrittenToProperty() throws Exception {
        // ADR 0015 §3：团队 key 的配额归属（team owner）经 ctx property 交给 RequestIdentityResolver。
        FakeCtx ctx = new FakeCtx(Map.of());
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid(
            "ak1", "member-1", "team-1", "team", "active", "member", "owner-1");

        Response resp = invokeApply(ctx, result);

        assertNull(resp);
        assertEquals(result.quotaOwnerId(), ctx.getProperty("aster.apikey.quotaOwnerId"));
        assertEquals("owner-1", ctx.getProperty("aster.apikey.quotaOwnerId"));
        assertEquals("member-1", ctx.getProperty("aster.apikey.userId"));
    }

    @Test
    void quotaOwnerIdPropertyFallsBackToUserIdForLegacyResult() throws Exception {
        // 旧 cloud 不返回 quotaOwnerId：property 仍写入，值为 userId（个人 key 行为不变）。
        FakeCtx ctx = new FakeCtx(Map.of());
        ApiKeyVerifyResult result = ApiKeyVerifyResult.valid("ak1", "u1", "tenant-a", "pro", "active");

        invokeApply(ctx, result);

        assertEquals("u1", ctx.getProperty("aster.apikey.quotaOwnerId"));
    }

    /**
     * 最小手写 ContainerRequestContext 假实现：只实现 filter 用到的
     * getHeaderString / getHeaders().putSingle / get/setProperty，其余抛
     * UnsupportedOperationException 以暴露意外依赖。比 Mockito 桩更直观，
     * 且 putSingle 与 getHeaderString 共享同一份 store，能真实反映覆盖效果。
     */
    private static final class FakeCtx implements ContainerRequestContext {
        // 单一 MultivaluedMap 同时驱动 getHeaders().putSingle() 与 getHeaderString()，
        // 真实反映 filter 对 header 的覆盖效果。
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        private final Map<String, Object> properties = new HashMap<>();

        FakeCtx(Map<String, String> initial) { initial.forEach(headers::putSingle); }

        @Override public String getHeaderString(String name) { return headers.getFirst(name); }
        @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
        @Override public void setProperty(String name, Object object) { properties.put(name, object); }
        @Override public Object getProperty(String name) { return properties.get(name); }
        @Override public java.util.Collection<String> getPropertyNames() { return properties.keySet(); }
        @Override public void removeProperty(String name) { properties.remove(name); }

        private static UnsupportedOperationException nope() {
            return new UnsupportedOperationException("not needed for this test");
        }
        @Override public jakarta.ws.rs.core.Request getRequest() { throw nope(); }
        @Override public String getMethod() { return "POST"; }
        @Override public void setMethod(String method) { throw nope(); }
        @Override public jakarta.ws.rs.core.UriInfo getUriInfo() { throw nope(); }
        @Override public void setRequestUri(java.net.URI requestUri) { throw nope(); }
        @Override public void setRequestUri(java.net.URI baseUri, java.net.URI requestUri) { throw nope(); }
        @Override public jakarta.ws.rs.core.SecurityContext getSecurityContext() { throw nope(); }
        @Override public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) { throw nope(); }
        @Override public boolean hasEntity() { return false; }
        @Override public java.io.InputStream getEntityStream() { throw nope(); }
        @Override public void setEntityStream(java.io.InputStream input) { throw nope(); }
        @Override public jakarta.ws.rs.core.MediaType getMediaType() { throw nope(); }
        @Override public java.util.List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() { throw nope(); }
        @Override public java.util.List<java.util.Locale> getAcceptableLanguages() { throw nope(); }
        @Override public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() { throw nope(); }
        @Override public java.util.Date getDate() { throw nope(); }
        @Override public java.util.Locale getLanguage() { throw nope(); }
        @Override public int getLength() { return -1; }
        @Override public void abortWith(Response response) { throw nope(); }
    }
}
