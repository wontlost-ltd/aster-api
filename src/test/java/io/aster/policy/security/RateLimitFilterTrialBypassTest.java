package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R29 Codex audit 发现：RateLimitFilter 优先级 {@code AUTHENTICATION+1}
 * 会在 JAX-RS TenantFilter 之前执行，对 trial 路径而言 TenantContext 还没
 * 初始化，落到 {@code rest:ip:unknown} 全局 60/min 桶，叠加
 * TrialEndpointGuard 已有的 per-IP 限流形成双重限流且互相挤桶。
 *
 * <p>修复：识别 {@link TrialEndpointGuard#TRIAL_GUARD_PASSED_PROP} +
 * {@link TrialEndpointGuard#TRIAL_PATH} 后直接 return null 放行。
 * 本测试钉住该 bypass。
 */
class RateLimitFilterTrialBypassTest {

    private RateLimitFilter newFilter(boolean enabled, RateLimiter rateLimiter) throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        Field f = RateLimitFilter.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(filter, enabled);

        Field maxReq = RateLimitFilter.class.getDeclaredField("maxRequests");
        maxReq.setAccessible(true);
        maxReq.setInt(filter, 60);
        Field win = RateLimitFilter.class.getDeclaredField("windowSeconds");
        win.setAccessible(true);
        win.setInt(filter, 60);

        Field tc = RateLimitFilter.class.getDeclaredField("tenantContext");
        tc.setAccessible(true);
        tc.set(filter, new TenantContext());

        Field rl = RateLimitFilter.class.getDeclaredField("rateLimiter");
        rl.setAccessible(true);
        rl.set(filter, rateLimiter);
        return filter;
    }

    private RateLimitFilter newFilter(boolean enabled) throws Exception {
        // Default convenience: produce a permissive mock limiter so tests
        // that don't care about interactions still work.
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        when(limiter.remaining(anyString(), anyInt(), any())).thenReturn(59);
        when(limiter.resetAtEpochSecond(anyString(), any())).thenReturn(0L);
        return newFilter(enabled, limiter);
    }

    private ContainerRequestContext newCtx(String path,
                                           Map<String, Object> properties) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");

        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(ctx.getUriInfo()).thenReturn(uri);

        MultivaluedMap<String, String> hdr = new MultivaluedHashMap<>();
        when(ctx.getHeaders()).thenReturn(hdr);

        for (var e : properties.entrySet()) {
            when(ctx.getProperty(e.getKey())).thenReturn(e.getValue());
        }
        return ctx;
    }

    @Test
    @DisplayName("R29: trial guard 凭证 + TRIAL_PATH → RateLimiter 零交互")
    void trialPathWithGuardPropertyBypassesRateLimiter() throws Exception {
        // R29+ 改进版：用 mock 验证 RateLimiter 完全未被调用，比 NPE 证明硬
        RateLimiter limiter = mock(RateLimiter.class);
        RateLimitFilter filter = newFilter(true, limiter);
        ContainerRequestContext ctx = newCtx(
            "/api/v1/policies/evaluate-source",
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        Response result = filter.filter(ctx).await().indefinitely();

        assertNull(result, "trial 路径放行：filter 返回 null 不阻断 chain");
        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("R29: trial 凭证但非 trial 路径 → 仍然调用 RateLimiter (反证 short-circuit 未触发)")
    void trialPropertyOnNonTrialPathDoesNotBypass() throws Exception {
        // 用 mock RateLimiter 显式验证非 trial 路径走完了限流链路。
        // 这比依赖 NullPointerException 更可靠：如果未来把短路改回只看
        // property，本断言会立刻失败（mock 不会被调用）。
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        when(limiter.remaining(anyString(), anyInt(), any())).thenReturn(59);
        when(limiter.resetAtEpochSecond(anyString(), any())).thenReturn(0L);

        RateLimitFilter filter = newFilter(true, limiter);
        ContainerRequestContext ctx = newCtx(
            "/api/v1/policies/evaluate",  // 不是 evaluate-source
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        filter.filter(ctx).await().indefinitely();

        verify(limiter, atLeastOnce()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("R29: /q/* 仍然豁免（管理端点）")
    void healthPathStillExempt() throws Exception {
        RateLimitFilter filter = newFilter(true);
        ContainerRequestContext ctx = newCtx("/q/health", Map.of());

        Response result = filter.filter(ctx).await().indefinitely();

        assertNull(result, "/q/* 必须放行（健康检查）");
    }

    @Test
    @DisplayName("R29: enabled=false 时整个 filter 不做事")
    void disabledFilterSkipsEverything() throws Exception {
        RateLimitFilter filter = newFilter(false);
        ContainerRequestContext ctx = newCtx("/api/v1/policies/evaluate", Map.of());

        Response result = filter.filter(ctx).await().indefinitely();

        assertNull(result);
    }
}
