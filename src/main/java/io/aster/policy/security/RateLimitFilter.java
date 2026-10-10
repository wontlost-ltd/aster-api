package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import io.aster.security.PathNormalizer;
import io.aster.security.apikey.InternalCallerFilter;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * REST API 限流过滤器
 *
 * 在 AUTHENTICATION 之后、AUTHORIZATION 之前执行，滑动窗口限流。
 * 分桶：经 HMAC 验签的内部调用按调用方 × 租户（独立额度），其余按租户或客户端 IP。
 *
 * 豁免路径：/q/*（健康检查等管理端点）、/internal/*
 */
@ApplicationScoped
public class RateLimitFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);

    @Inject
    RateLimiter rateLimiter;

    @Inject
    TenantContext tenantContext;

    /** 读取 socket 远端地址（trust-forwarded-headers=false 时的 IP 来源）。 */
    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * 是否信任转发头作为客户端真实 IP（解析优先级见 {@link TrialEndpointGuard#clientIp}：
     * CF-Connecting-IP &gt; XFF 首段 &gt; X-Real-IP &gt; socket）。
     *
     * <p>默认 false：用 socket 源地址，防止客户端轮换 XFF/CF 头绕过匿名限流、制造
     * 高基数 key 撑爆限流 map。置 true 仅适用于：(a) Cloudflare Tunnel-only 拓扑
     * （CF-Connecting-IP 边缘写入、不可伪造）；或 (b) 会覆盖 XFF 且清洗客户端 CF 头
     * 的可信反代/ingress。语义与 {@code aster.security.trial.trust-forwarded-for}
     * 一致（部署需同时打开），复用 TrialEndpointGuard.clientIp 解析。
     */
    @ConfigProperty(name = "aster.ratelimit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    @ConfigProperty(name = "aster.ratelimit.rest.max-requests", defaultValue = "60")
    int maxRequests;

    @ConfigProperty(name = "aster.ratelimit.rest.window-seconds", defaultValue = "60")
    int windowSeconds;

    /**
     * 经 HMAC 验签的内部调用（cloud→api）每个调用方 × 租户的窗口额度。cloud 的服务端调用
     * 全部来自同一出口 IP，按 IP 计数会让所有租户共用一个 60/min 桶；按租户分桶并单独
     * 配额，一个租户的流量不会挤占另一个租户。
     */
    @ConfigProperty(name = "aster.ratelimit.internal.max-requests", defaultValue = "600")
    int internalMaxRequests;

    /** 与 InternalCallerFilter 同一把内部调用密钥（ASTER_PLAN_GATE_HMAC_KEY）。 */
    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    /** 限流桶：标识 + 窗口额度。 */
    private record Bucket(String identifier, int maxRequests) {}

    /**
     * RESTEasy Reactive 的 @ServerRequestFilter 须返回 Response（或 Uni&lt;Response&gt;）来短路，
     * 在其中调用 ctx.abortWith 会抛 IllegalStateException 并以 500 冒出（k6 曾因此看到
     * "100% 5xx"）。返回 null 表示继续过滤链。
     *
     * <p>本 filter 早于 InternalCallerFilter 执行，此刻 HMAC 尚未验证；带内部调用头的请求
     * 在这里复用 {@link InternalCallerFilter} 的同一套预检与 canonical v3 签名比对（不消费
     * nonce，nonce 仍由 InternalCallerFilter 在受保护路径上消费），读 body 须在 worker 线程。
     * 未带内部头、或验签不通过的请求一律按租户 / IP 限流，绝不信任未验证的头来分桶。
     */
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 1)
    public Uni<Response> filter(ContainerRequestContext ctx) {
        if (!enabled || isExempt(ctx)) {
            return Uni.createFrom().nullItem();
        }
        if (!TrialBypassPredicate.hasInternalCallerCredentials(ctx) || hmacKey.isEmpty()) {
            return Uni.createFrom().item(limit(ctx, defaultBucket(ctx)));
        }
        return Uni.createFrom().item(() -> limit(ctx, internalOrDefaultBucket(ctx)))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /** 豁免：管理端点、内部端点、已由 TrialEndpointGuard 做过成本控制的 trial 请求。 */
    private static boolean isExempt(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")) {
            return true;
        }
        // R29→R30 trial 旁路：TrialEndpointGuard 已经提供 per-IP minute/hour/
        // concurrent 三层成本控制，叠加本 REST limiter 既冗余又会因为 filter
        // 优先级早于 TenantFilter 导致 tenantContext 未 set，落到
        // rest:ip:unknown 全局桶。统一用 TrialBypassPredicate 判定。
        if (TrialBypassPredicate.isGuardedTrialRequest(ctx)) {
            LOG.debugf("rate limit bypassed for guarded trial path %s", path);
            return true;
        }
        return false;
    }

    /**
     * 限流 key：优先租户，否则回退客户端 IP。
     * 注意：本 filter 在 AUTHENTICATION+1 执行，**早于** TenantFilter，所以
     * 绝大多数请求此刻 TenantContext 尚未初始化，会走 IP 分支——这正是
     * trust-forwarded-headers 在生产必须正确配置的原因（见 application.
     * properties 注释：反代后须置 true 且覆盖 XFF，否则全站折叠到入口 IP）。
     * 已认证流量的精确按量计费由下游 ApiQuotaGuard（在 apikey 验证之后）承担；
     * 本 filter 是粗粒度的入口防滥用闸。
     */
    private Bucket defaultBucket(ContainerRequestContext ctx) {
        if (tenantContext.isInitialized()) {
            return new Bucket("rest:" + tenantContext.getCurrentTenant(), maxRequests);
        }
        return new Bucket("rest:ip:" + getClientIp(ctx), maxRequests);
    }

    /** 签名有效 → 按调用方与已签名的 X-Tenant-Id 分桶；否则回退默认桶。 */
    private Bucket internalOrDefaultBucket(ContainerRequestContext ctx) {
        if (!verifiedInternalCall(ctx)) {
            return defaultBucket(ctx);
        }
        String tenant = ctx.getHeaderString("X-Tenant-Id");
        String scope = tenant == null || tenant.isBlank() ? "-" : tenant;
        return new Bucket("rest:internal:cloud-bff:" + scope, internalMaxRequests);
    }

    private boolean verifiedInternalCall(ContainerRequestContext ctx) {
        if (InternalCallerFilter.precheckHeaders(ctx, Instant.now().getEpochSecond()) != null) {
            return false;
        }
        try {
            byte[] body = InternalCallerFilter.bufferBody(ctx);
            String path = PathNormalizer.normalize(ctx.getUriInfo().getPath());
            return body != null && InternalCallerFilter.signatureMatches(ctx, path, body, hmacKey.get());
        } catch (IOException e) {
            LOG.debugf("内部调用 body 读取失败，按默认桶限流: %s", e.getMessage());
            return false;
        }
    }

    /** 计数并始终写限流响应头；超限返回 429，否则返回 null 继续过滤链。 */
    private Response limit(ContainerRequestContext ctx, Bucket bucket) {
        Duration window = Duration.ofSeconds(windowSeconds);
        String identifier = bucket.identifier();
        int max = bucket.maxRequests();
        boolean allowed = rateLimiter.tryAcquire(identifier, max, window);
        int remaining = rateLimiter.remaining(identifier, max, window);
        long resetAt = rateLimiter.resetAtEpochSecond(identifier, window);

        ctx.getHeaders().putSingle("X-RateLimit-Limit", String.valueOf(max));
        ctx.getHeaders().putSingle("X-RateLimit-Remaining", String.valueOf(remaining));
        ctx.getHeaders().putSingle("X-RateLimit-Reset", String.valueOf(resetAt));
        if (allowed) {
            return null;
        }
        long retryAfter = Math.max(1, resetAt - Instant.now().getEpochSecond());
        LOG.warnf("限流拒绝: identifier=%s, path=%s", identifier, ctx.getUriInfo().getPath());
        return Response.status(429)
            .header("Retry-After", String.valueOf(retryAfter))
            .header("X-RateLimit-Limit", String.valueOf(max))
            .header("X-RateLimit-Remaining", "0")
            .header("X-RateLimit-Reset", String.valueOf(resetAt))
            .entity("{\"error\":\"Too Many Requests\",\"retryAfter\":" + retryAfter + "}")
            .build();
    }

    /**
     * 提取客户端 IP。复用 {@link TrialEndpointGuard#clientIp}：
     * trust-forwarded-headers=false（默认）时返回 socket 源地址，忽略可伪造的
     * XFF/CF 头；=true 时优先不可伪造的 CF-Connecting-IP，再 XFF 首段 / X-Real-IP。
     * 两处限流共享同一信任边界。
     */
    private String getClientIp(ContainerRequestContext ctx) {
        return TrialEndpointGuard.clientIp(ctx, routingContext, trustForwardedHeaders);
    }
}
