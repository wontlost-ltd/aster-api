package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #533：aster-cloud Worker 渲染页面时未签名地请求公开 i18n 端点，客户端 IP 全是
 * Cloudflare 出口地址，所有用户共用一个 60/min 的 {@code rest:ip:<ip>} 桶。
 *
 * <p>公开、只读、内存直出的 i18n 端点（{@code GET /api/v1/messages-manifest}、
 * {@code GET /api/v1/messages/{locale}}）改用独立的、额度更高的每 IP 桶：
 * 不再挤占也不再被普通 REST 桶挤占；单个 IP 对它们的滥用仍有上限。
 * 其它路径、其它方法、多段 / 穿越子路径一律仍走普通桶。
 */
class RateLimitFilterPublicI18nBucketTest {

    private static final String CF_EGRESS_IP = "2a06:98c0:3600::103";
    private static final int REST_MAX = 60;
    private static final int PUBLIC_I18N_MAX = 1200;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = newFilter(PUBLIC_I18N_MAX);
    }

    @Test
    void 同一出口IP的100次manifest请求不被限流() {
        for (int i = 0; i < 100; i++) {
            assertThat(call("GET", "/api/v1/messages-manifest", CF_EGRESS_IP))
                .as("第 %d 次 manifest 请求", i + 1)
                .isNull();
        }
    }

    @Test
    void manifest回报独立桶的额度() {
        ContainerRequestContext ctx = request("GET", "/api/v1/messages-manifest", CF_EGRESS_IP);
        assertThat(call(ctx)).isNull();
        assertThat(ctx.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo(String.valueOf(PUBLIC_I18N_MAX));
    }

    @Test
    void 单locale文案与manifest共用公开i18n桶() {
        for (int i = 0; i < 50; i++) {
            assertThat(call("GET", "/api/v1/messages/en-US", CF_EGRESS_IP)).isNull();
            assertThat(call("GET", "api/v1/messages-manifest", CF_EGRESS_IP)).isNull();
        }
    }

    @Test
    void 其它端点在同一IP上仍按普通额度限流且不受i18n流量影响() {
        for (int i = 0; i < 100; i++) {
            call("GET", "/api/v1/messages-manifest", CF_EGRESS_IP);
        }
        // i18n 流量没有消耗普通桶：完整的 60 次仍可用
        for (int i = 0; i < REST_MAX; i++) {
            assertThat(call("GET", "/api/v1/policies", CF_EGRESS_IP)).as("第 %d 次", i + 1).isNull();
        }
        Response limited = call("GET", "/api/v1/policies", CF_EGRESS_IP);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeaderString("X-RateLimit-Limit")).isEqualTo(String.valueOf(REST_MAX));
    }

    @Test
    void 普通桶耗尽后公开i18n端点仍可用() {
        for (int i = 0; i < REST_MAX; i++) {
            call("GET", "/api/v1/policies", CF_EGRESS_IP);
        }
        assertThat(call("GET", "/api/v1/policies", CF_EGRESS_IP).getStatus()).isEqualTo(429);
        assertThat(call("GET", "/api/v1/messages-manifest", CF_EGRESS_IP)).isNull();
    }

    @Test
    void 公开i18n桶对单个IP仍有上限且按IP隔离() throws Exception {
        filter = newFilter(3);
        for (int i = 0; i < 3; i++) {
            assertThat(call("GET", "/api/v1/messages-manifest", "203.0.113.7")).isNull();
        }
        Response limited = call("GET", "/api/v1/messages/en-US", "203.0.113.7");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeaderString("X-RateLimit-Limit")).isEqualTo("3");
        // 另一个 IP 不受影响
        assertThat(call("GET", "/api/v1/messages-manifest", "198.51.100.9")).isNull();
    }

    @Test
    void 非GET方法或非精确路径仍走普通桶() {
        for (String[] req : new String[][] {
                {"POST", "/api/v1/messages-manifest"},
                {"GET", "/api/v1/messages/en-US/extra"},
                {"GET", "/api/v1/messages/.."},
                {"GET", "/api/v1/messages-manifest/x"},
                {"PUT", "/api/v1/admin/messages/en-US"}}) {
            ContainerRequestContext ctx = request(req[0], req[1], CF_EGRESS_IP);
            call(ctx);
            assertThat(ctx.getHeaders().getFirst("X-RateLimit-Limit"))
                .as("%s %s", req[0], req[1])
                .isEqualTo(String.valueOf(REST_MAX));
        }
    }

    @Test
    void HEAD请求同样走公开i18n桶() {
        ContainerRequestContext ctx = request("HEAD", "/api/v1/messages-manifest", CF_EGRESS_IP);
        assertThat(call(ctx)).isNull();
        assertThat(ctx.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo(String.valueOf(PUBLIC_I18N_MAX));
    }

    private Response call(String method, String path, String ip) {
        return call(request(method, path, ip));
    }

    private Response call(ContainerRequestContext ctx) {
        return filter.filter(ctx).await().indefinitely();
    }

    private static RateLimitFilter newFilter(int publicI18nMax) throws Exception {
        RateLimiter limiter = new RateLimiter();
        set(limiter, "enabled", true);
        RateLimitFilter f = new RateLimitFilter();
        set(f, "enabled", true);
        set(f, "maxRequests", REST_MAX);
        set(f, "internalMaxRequests", 600);
        set(f, "publicI18nMaxRequests", publicI18nMax);
        set(f, "windowSeconds", 60);
        // 生产经 Cloudflare Tunnel 暴露并信任 CF-Connecting-IP；Worker 发起的子请求里它就是出口 IP
        set(f, "trustForwardedHeaders", true);
        set(f, "tenantContext", new TenantContext());
        set(f, "rateLimiter", limiter);
        set(f, "hmacKey", Optional.of("rate-limit-test-key"));
        return f;
    }

    private static ContainerRequestContext request(String method, String path, String ip) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn(method);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/" + path.replaceFirst("^/", "")));
        when(ctx.getUriInfo()).thenReturn(uri);
        when(ctx.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        Map<String, String> headers = Map.of("CF-Connecting-IP", ip);
        when(ctx.getHeaderString(any())).thenAnswer(inv -> headers.get(inv.<String>getArgument(0)));
        return ctx;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
