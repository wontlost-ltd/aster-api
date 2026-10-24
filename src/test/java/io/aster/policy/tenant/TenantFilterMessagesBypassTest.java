package io.aster.policy.tenant;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR 0018 P2 回归：{@code /api/v1/messages/{locale}} 界面文案查询与
 * {@code /api/v1/lexicons} 同源——公开只读、前端 messages-loader 匿名 fetch
 * （无 X-Tenant-Id）。此前 TenantFilter 漏了 messages 豁免，导致生产环境
 * messages 加载被 400 拒绝、前端静默 fail-open 到内嵌文案 → 统一语言包的
 * 运行时消息加载实际失效（lexicons 被豁免，messages 没有）。
 *
 * <p>本测试钉住 messages 读路径的豁免，与 lexicons 行为对齐。授权仍由
 * MessagesResource 内部的 locale 可用性开关守（未启用 locale → 404），
 * 此层只负责不在 perimeter 上误拦匿名读请求。
 */
class TenantFilterMessagesBypassTest {

    private TenantFilter newFilter() throws Exception {
        TenantFilter filter = new TenantFilter();
        Field f1 = TenantFilter.class.getDeclaredField("whitelistEnabled");
        f1.setAccessible(true);
        f1.set(filter, false);
        Field f2 = TenantFilter.class.getDeclaredField("allowedTenants");
        f2.setAccessible(true);
        f2.set(filter, Optional.<String>empty());
        Field f3 = TenantFilter.class.getDeclaredField("strictFormat");
        f3.setAccessible(true);
        f3.set(filter, true);
        Field f4 = TenantFilter.class.getDeclaredField("tenantContext");
        f4.setAccessible(true);
        f4.set(filter, new TenantContext());
        return filter;
    }

    private ContainerRequestContext newCtx(String method, String path, Map<String, String> headers) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn(method);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(ctx.getUriInfo()).thenReturn(uri);
        MultivaluedMap<String, String> hdr = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) hdr.add(e.getKey(), e.getValue());
        when(ctx.getHeaders()).thenReturn(hdr);
        for (var e : headers.entrySet()) {
            when(ctx.getHeaderString(e.getKey())).thenReturn(e.getValue());
        }
        return ctx;
    }

    @Test
    @DisplayName("messages 读路径（带 locale 段）匿名访问 → 不要求 X-Tenant-Id")
    void messagesReadPathBypassesTenant() throws Exception {
        TenantFilter filter = newFilter();
        for (String path : new String[]{
                "/api/v1/messages/en-US",
                "/api/v1/messages/zh-CN",
                "/api/v1/messages/hi-IN",
                "api/v1/messages/de-DE" // 无前导斜杠形态
        }) {
            ContainerRequestContext ctx = newCtx("GET", path, Map.of()); // 无 X-Tenant-Id
            filter.filter(ctx);
            verify(ctx, never()).abortWith(any());
        }
    }

    @Test
    @DisplayName("messages 豁免与 lexicons 对齐：lexicons 读路径同样不被拦")
    void lexiconReadPathStillBypasses() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("GET", "/api/v1/lexicons", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("messages-manifest 版本清单端点匿名访问 → 不要求 X-Tenant-Id（ADR 0020）")
    void manifestPathBypassesTenant() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("GET", "/api/v1/messages-manifest", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("admin/messages 写端点跳过 tenant（自做内部 HMAC，ADR 0021）→ 不要求 X-Tenant-Id")
    void messagesAdminPathBypassesTenant() throws Exception {
        // admin 端点鉴权靠资源内 AdminHmacVerifier，全局 tenant 校验跳过（同 admin/lexicons）。
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("PUT", "/api/v1/admin/messages/en-US", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("admin/byok-allowlist 跳过 tenant（资源内 HMAC）→ 不要求 X-Tenant-Id")
    void byokAllowlistAdminPathBypassesTenant() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("POST", "/api/v1/admin/byok-allowlist", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("admin source-hashes 跳过 tenant（资源内 HMAC）→ 不要求 X-Tenant-Id")
    void sourceHashAdminPathBypassesTenant() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("GET", "/api/v1/admin/policy-versions/source-hashes", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("兄弟路径不被误豁免：裸 /api/v1/messages（无 locale 段）仍要求 X-Tenant-Id")
    void bareMessagesPathNotExempt() throws Exception {
        // matchesMessagesPath 只匹配 `/api/v1/messages/` 前缀（必带 locale 段），
        // 防止未来出现 /api/v1/messages-admin 类兄弟路径被误豁免。裸路径不是有效
        // 端点（MessagesResource 只有 /{locale}），这里走普通校验返回 400 即可。
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("GET", "/api/v1/messages", Map.of());
        filter.filter(ctx);
        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(400, resp.getValue().getStatus());
    }

    @Test
    @DisplayName("非 messages 普通端点不受影响：仍要求 X-Tenant-Id")
    void normalPathStillRequiresTenant() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx("POST", "/api/v1/policies/evaluate", Map.of());
        filter.filter(ctx);
        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(400, resp.getValue().getStatus());
    }

    @Test
    @DisplayName("多段子路径/路径穿越不被误豁免（Codex 安全审查收紧）→ 仍要求 X-Tenant-Id")
    void multiSegmentAndTraversalNotExempt() throws Exception {
        TenantFilter filter = newFilter();
        for (String path : new String[]{
                "/api/v1/messages/en-US/extra",       // 多段子路径
                "/api/v1/messages/../policies/evaluate", // 路径穿越尝试绕过 perimeter
                "/api/v1/messages-admin/foo"          // 兄弟路径
        }) {
            ContainerRequestContext ctx = newCtx("GET", path, Map.of());
            filter.filter(ctx);
            ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
            verify(ctx).abortWith(resp.capture());
            assertEquals(400, resp.getValue().getStatus(),
                "非单段 messages 路径不应被豁免: " + path);
        }
    }
}
