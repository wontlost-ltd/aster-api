package io.aster.policy.security;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.Priorities;
import java.io.ByteArrayInputStream;

/**
 * 请求签名验证过滤器
 *
 * 拦截所有请求，验证 HMAC 签名和 Nonce，防止请求篡改和重放攻击
 *
 * 使用 Quarkus RESTEasy Reactive 原生 @ServerRequestFilter 注解，
 * 返回 Uni<Void> 以支持异步读取请求体。
 */
@ApplicationScoped
public class RequestSignatureFilter {

    @Inject
    HmacSignatureService hmacSignatureService;

    @Inject
    NonceService nonceService;

    @ConfigProperty(name = "aster.security.signature.enabled", defaultValue = "true")
    boolean signatureEnabled;

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
    public Uni<Void> filter(ContainerRequestContext ctx) {
        // 跳过健康检查和内部端点（支持带/不带前导斜杠的路径）
        // /api/internal/* 是跨服务接口，自带 HMAC 验签，不走全局签名过滤器
        String path = ctx.getUriInfo().getPath();
        // slash-boundary 路径匹配
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")
                || isLexiconReadPath(path)
                || isMessagesReadPath(path)
                || isLexiconAdminPath(path)
                || isMessagesAdminPath(path)
                || isByokAllowlistAdminPath(path)
                || isPolicyVersionSourceHashAdminPath(path)
                || isRevalidationAdminPath(path)
                || isResolverMigrationAdminPath(path)) {
            return Uni.createFrom().voidItem();
        }

        // Marketing-tier trial endpoint: TrialEndpointGuard 已经在 AUTHENTICATION-100
        // 优先级上完成 Origin / body-size / per-IP 限流校验，并签发 TRIAL_GUARD_PASSED_PROP
        // 凭证。该凭证只在 guard 全部闸门通过后才被设上，且只对当前请求生命周期有效；
        // 这里据此放过 HMAC 签名要求 —— 否则 trial 请求必然没有全局 HMAC，会被全部拒掉。
        //
        // 路径精确匹配 /api/v1/policies/evaluate-source（PathNormalizer 归一化后），
        // 避免任何子路径误用 property 名规避签名。
        String normalized = io.aster.security.PathNormalizer.normalize(path);
        if (TrialEndpointGuard.TRIAL_PATH.equals(normalized)
                && Boolean.TRUE.equals(ctx.getProperty(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP))) {
            return Uni.createFrom().voidItem();
        }

        // 如果签名验证未启用，跳过
        if (!signatureEnabled) {
            return Uni.createFrom().voidItem();
        }

        // 使用 Mutiny 异步读取请求体
        return Uni.createFrom().item(() -> {
            try {
                // 读取请求体（只能读取一次，需要缓存）
                byte[] body = ctx.getEntityStream().readAllBytes();
                ctx.setEntityStream(new ByteArrayInputStream(body));

                // 验证签名
                hmacSignatureService.verify(ctx, body);

                // 验证 Nonce（防重放）
                String tenantId = ctx.getHeaderString("X-Tenant-Id");
                String nonce = ctx.getHeaderString("X-Aster-Nonce");
                String query = ctx.getUriInfo().getRequestUri().getQuery();
                String requestHash = RequestCanonicalizer.computeRequestHash(
                    ctx.getMethod(), ctx.getUriInfo().getPath(), query, body);

                nonceService.ensureFresh(tenantId, nonce, requestHash);
                return null;
            } catch (WebApplicationException e) {
                // 透传 WebApplicationException，保留 HTTP 状态码（401/403 等）
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
          .replaceWithVoid();
    }

    private static boolean isLexiconReadPath(String path) {
        if ("/api/v1/lexicons".equals(path) || "api/v1/lexicons".equals(path)) return true;
        return path.startsWith("/api/v1/lexicons/") || path.startsWith("api/v1/lexicons/");
    }

    /**
     * /api/v1/messages/{locale} 界面文案查询：与 lexicons 同源，公开只读（登录页本身
     * 也需文案），不应被 HMAC 签名拦截。前端 messages-loader 匿名 fetch（无 X-Tenant-Id /
     * 无签名），受 locale 可用性开关约束（MessagesResource 内部 404 未启用 locale）。
     *
     * <p>精确匹配（Codex 安全审查）：复用 {@link MessagesPathMatcher}，与 TenantFilter
     * 同一份逻辑——豁免 {@code /api/v1/messages/<locale>} 单段（拒多段/穿越）与
     * {@code /api/v1/messages-manifest}（精确，ADR 0020 版本清单）。
     */
    private static boolean isMessagesReadPath(String path) {
        return io.aster.policy.i18n.MessagesPathMatcher.isPublicMessagesReadPath(path);
    }

    private static boolean isLexiconAdminPath(String path) {
        if ("/api/v1/admin/lexicons".equals(path) || "api/v1/admin/lexicons".equals(path)) return true;
        return path.startsWith("/api/v1/admin/lexicons/") || path.startsWith("api/v1/admin/lexicons/");
    }

    /**
     * /api/v1/admin/messages/{locale} 运行时文案编辑（ADR 0021）：与 lexicon admin 同——
     * 端点**自己**做内部 HMAC（{@code AdminHmacVerifier}），故跳过全局签名过滤器，避免双重
     * 验签方案冲突。鉴权由资源内 HMAC 保证（这里跳过 ≠ 不鉴权）。
     */
    private static boolean isMessagesAdminPath(String path) {
        return path.startsWith("/api/v1/admin/messages/") || path.startsWith("api/v1/admin/messages/");
    }

    /**
     * /api/v1/admin/byok-allowlist：资源内部 HMAC 控制的 SSRF allowlist 管理端点。
     * 精确匹配，避免误豁免兄弟路径。
     */
    private static boolean isByokAllowlistAdminPath(String path) {
        return "/api/v1/admin/byok-allowlist".equals(path) || "api/v1/admin/byok-allowlist".equals(path);
    }

    /** 重验 admin 端点（runs / runs/{id} / invalid）由资源内 AdminHmacVerifier 验签。 */
    private static boolean isRevalidationAdminPath(String path) {
        return path.startsWith("/api/v1/admin/revalidation/") || path.startsWith("api/v1/admin/revalidation/");
    }

    /** 存量重编译 admin 端点（ADR 0051 §9）由资源内 AdminHmacVerifier 验签。精确匹配。 */
    private static boolean isResolverMigrationAdminPath(String path) {
        return "/api/v1/admin/resolver-migration/run".equals(path)
            || "api/v1/admin/resolver-migration/run".equals(path);
    }

    /** 策略哈希 admin 读端点由资源内 AdminHmacVerifier 验签。 */
    private static boolean isPolicyVersionSourceHashAdminPath(String path) {
        return "/api/v1/admin/policy-versions/source-hashes".equals(path)
            || "api/v1/admin/policy-versions/source-hashes".equals(path);
    }
}
