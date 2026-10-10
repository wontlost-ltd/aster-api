package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import io.aster.security.apikey.InternalCallerSigning;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * cloud→api 的服务端调用全部来自同一出口 IP：经 HMAC 验签的内部调用按调用方与租户分桶，
 * 使用独立额度；未验签（含伪造内部头）的请求仍按 IP 限流。
 */
class RateLimitFilterInternalBucketTest {

    private static final String KEY = "rate-limit-test-key";
    private static final String PATH = "/api/v1/policies/compile";
    private static final String BODY = "{\"source\":\"Module M.\",\"locale\":\"en-US\"}";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        RateLimiter limiter = new RateLimiter();
        set(limiter, "enabled", true);
        filter = new RateLimitFilter();
        set(filter, "enabled", true);
        set(filter, "maxRequests", 1);
        set(filter, "internalMaxRequests", 2);
        set(filter, "windowSeconds", 60);
        set(filter, "tenantContext", new TenantContext());
        set(filter, "rateLimiter", limiter);
        set(filter, "hmacKey", Optional.of(KEY));
    }

    @Test
    void 两个租户经内部通道各用各的桶() {
        assertThat(call(signed("tenant-a"))).isNull();
        assertThat(call(signed("tenant-a"))).isNull();
        assertThat(call(signed("tenant-a")).getStatus()).isEqualTo(429);
        // 同一出口 IP 上 tenant-a 已耗尽额度，tenant-b 不受影响
        assertThat(call(signed("tenant-b"))).isNull();
    }

    @Test
    void 内部桶使用独立额度并回报该额度() {
        ContainerRequestContext ctx = signed("tenant-a");
        call(ctx);
        assertThat(ctx.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("2");
    }

    @Test
    void 伪造签名的内部头落入IP桶() {
        Map<String, String> h = InternalCallerSigning.headers(KEY, "POST", PATH, "", BODY,
            "tenant-a", null, null, List.of());
        h.put("X-Internal-Signature", "0".repeat(64));
        assertThat(call(request(h))).isNull();
        // IP 桶额度为 1：第二个伪造请求即被拒，且与其声称的租户无关
        h.put("X-Tenant-Id", "tenant-b");
        Response second = call(request(h));
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeaderString("X-RateLimit-Limit")).isEqualTo("1");
    }

    @Test
    void 签名有效但body被篡改时落入IP桶() {
        Map<String, String> h = InternalCallerSigning.headers(KEY, "POST", PATH, "", BODY,
            "tenant-a", null, null, List.of());
        assertThat(call(request(h, "{\"source\":\"tampered\"}"))).isNull();
        assertThat(call(request(h, "{\"source\":\"tampered\"}")).getStatus()).isEqualTo(429);
    }

    @Test
    void 验签后body仍可被下游读取() throws Exception {
        ContainerRequestContext ctx = signed("tenant-a");
        call(ctx);
        assertThat(new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY);
    }

    private Response call(ContainerRequestContext ctx) {
        return filter.filter(ctx).await().indefinitely();
    }

    private ContainerRequestContext signed(String tenant) {
        return request(InternalCallerSigning.headers(KEY, "POST", PATH, "", BODY,
            tenant, null, null, List.of()));
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        return request(headers, BODY);
    }

    /** 模拟请求：实体流可被 setEntityStream 替换，与真实容器一致。 */
    private static ContainerRequestContext request(Map<String, String> headers, String body) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(PATH);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + PATH));
        when(ctx.getUriInfo()).thenReturn(uri);
        when(ctx.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        Map<String, String> copy = new HashMap<>(headers);
        when(ctx.getHeaderString(any())).thenAnswer(inv -> copy.get(inv.<String>getArgument(0)));
        when(ctx.getLength()).thenReturn(body.length());
        InputStream[] stream = { new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) };
        when(ctx.getEntityStream()).thenAnswer(inv -> stream[0]);
        doAnswer(inv -> stream[0] = inv.getArgument(0)).when(ctx).setEntityStream(any());
        return ctx;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
