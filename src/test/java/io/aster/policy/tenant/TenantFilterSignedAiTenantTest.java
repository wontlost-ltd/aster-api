package io.aster.policy.tenant;

import io.aster.security.apikey.InternalCallerFilter;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 端点的租户解析（aster-api#360）。
 *
 * <p>此前 /api/v1/ai/* 一律豁免并写死租户 "default"：cloud BFF 明明经 HMAC 签名送来了真实
 * X-Tenant-Id，所有用户的 AI 调用仍都归到 "default"——token 回填的 userId 错、守门免扣计数
 * 全员共享、拦截事件归不到人。修复：已验签的 AI 调用按 header 走；未验签的仍用默认租户。
 */
class TenantFilterSignedAiTenantTest {

    private TenantContext tenantContext;

    private TenantFilter newFilter() throws Exception {
        TenantFilter filter = new TenantFilter();
        set(filter, "whitelistEnabled", false);
        set(filter, "allowedTenants", Optional.<String>empty());
        set(filter, "strictFormat", true);
        tenantContext = new TenantContext();
        set(filter, "tenantContext", tenantContext);
        return filter;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = TenantFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ContainerRequestContext ctx(String path, boolean hmacVerified, String tenantHeader) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(ctx.getUriInfo()).thenReturn(uri);
        Map<String, String> headers = new HashMap<>();
        if (tenantHeader != null) headers.put("X-Tenant-Id", tenantHeader);
        MultivaluedHashMap<String, String> hdr = new MultivaluedHashMap<>();
        headers.forEach(hdr::add);
        when(ctx.getHeaders()).thenReturn(hdr);
        when(ctx.getHeaderString(any())).thenAnswer(inv -> headers.get(inv.<String>getArgument(0)));
        when(ctx.getProperty(InternalCallerFilter.HMAC_VERIFIED_PROP)).thenReturn(hmacVerified ? Boolean.TRUE : null);
        return ctx;
    }

    @Test
    @DisplayName("★已验签的 AI 调用：租户取 X-Tenant-Id（不再是 default）")
    void signedAiCallUsesHeaderTenant() throws Exception {
        TenantFilter filter = newFilter();
        for (String path : new String[]{"/api/v1/ai/suggest", "/api/v1/ai/generate", "api/v1/ai/complete"}) {
            ContainerRequestContext c = ctx(path, true, "cmuser123");
            filter.filter(c);
            verify(c, never()).abortWith(any());
            assertThat(tenantContext.getCurrentTenant()).as(path).isEqualTo("cmuser123");
        }
    }

    @Test
    @DisplayName("★未验签的 AI 调用（public / trial 旁路）：header 不可信，仍用 default")
    void unsignedAiCallIgnoresHeader() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext c = ctx("/api/v1/ai/suggest", false, "victim");
        filter.filter(c);
        verify(c, never()).abortWith(any());
        assertThat(tenantContext.getCurrentTenant()).isEqualTo("default");
    }

    @Test
    @DisplayName("已验签但没带 X-Tenant-Id：不拒绝，沿用 default")
    void signedAiCallWithoutHeaderFallsBackToDefault() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext c = ctx("/api/v1/ai/suggest", true, null);
        filter.filter(c);
        verify(c, never()).abortWith(any());
        assertThat(tenantContext.getCurrentTenant()).isEqualTo("default");
    }

    @Test
    @DisplayName("已验签的 AI 调用也走常规校验：保留字 trial / 非法格式被拒")
    void signedAiCallStillValidatesHeader() throws Exception {
        TenantFilter filter = newFilter();
        for (String bad : new String[]{"trial", "bad tenant!"}) {
            ContainerRequestContext c = ctx("/api/v1/ai/suggest", true, bad);
            filter.filter(c);
            verify(c).abortWith(any());
        }
    }

    @Test
    @DisplayName("其它豁免路径不受影响：/api/internal/* 即便验签也仍用 default")
    void internalPathUnchanged() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext c = ctx("/api/internal/x", true, "cmuser123");
        filter.filter(c);
        assertThat(tenantContext.getCurrentTenant()).isEqualTo("default");
    }
}
