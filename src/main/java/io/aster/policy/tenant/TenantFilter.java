package io.aster.policy.tenant;

import io.aster.policy.i18n.MessagesPathMatcher;
import io.aster.policy.security.TrialBypassPredicate;
import io.aster.policy.security.TrialEndpointGuard;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 租户过滤器，拦截所有请求并验证 X-Tenant-Id header。
 *
 * <p>验证规则：
 * <ul>
 *   <li>缺失 X-Tenant-Id → 400 Bad Request（除豁免路径外）</li>
 *   <li>空字符串或仅空白 → 400 Bad Request</li>
 *   <li>格式不符合规范 → 400 Bad Request</li>
 *   <li>租户不在白名单中（如启用） → 403 Forbidden</li>
 *   <li>有效 tenant ID → 填充 TenantContext</li>
 * </ul>
 *
 * <p>豁免路径（无需 X-Tenant-Id header）：
 * <ul>
 *   <li>/q/* - Quarkus 管理端点（health, metrics, openapi）</li>
 *   <li>/graphql/schema.graphql - GraphQL schema 端点</li>
 * </ul>
 *
 * <p>安全增强：
 * <ul>
 *   <li>租户 ID 格式验证（仅允许字母、数字、连字符、下划线）</li>
 *   <li>可选的租户白名单验证</li>
 *   <li>防止租户 ID 注入攻击</li>
 * </ul>
 */
@Provider
public class TenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * 租户 ID 格式正则表达式
     * 仅允许：字母、数字、连字符、下划线，长度 1-64
     */
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * R30 reserved-tenant denylist：这些租户名仅供本服务内部 sentinel 使用，
     * 不能通过 {@code X-Tenant-Id} header 由外部客户端设置。
     *
     * <p>当前唯一保留字是 {@code "trial"}，由 {@link TrialEndpointGuard} 在
     * 通过 Origin/IP/body 三重校验后通过 {@code TRIAL_GUARD_PASSED_PROP}
     * 凭证 + path 双重条件由本 filter 设置。外部直接传 {@code X-Tenant-Id: trial}
     * 会污染审计 identity、PlanGate 计费维度，并在历史上曾让 enforceApiQuota 误旁路。
     *
     * <p>用 {@code Set.of} 而非 ConfigProperty 是有意为之 —— sentinel 应当
     * 跟代码一起评审，而不是给 operator 用 ConfigMap 误关掉。
     */
    private static final java.util.Set<String> RESERVED_TENANTS = java.util.Set.of("trial");

    @Inject
    TenantContext tenantContext;

    /**
     * 是否启用租户白名单验证
     */
    @ConfigProperty(name = "aster.tenant.whitelist.enabled", defaultValue = "false")
    boolean whitelistEnabled;

    /**
     * 允许的租户 ID 白名单（逗号分隔）
     */
    @ConfigProperty(name = "aster.tenant.whitelist.tenants")
    Optional<String> allowedTenants;

    /**
     * 是否启用严格的租户 ID 格式验证
     */
    @ConfigProperty(name = "aster.tenant.strict-format", defaultValue = "true")
    boolean strictFormat;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // CORS preflight 请求不带自定义 headers，必须放行
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // R28→R30 trial 旁路：匿名 trial 访客没有 X-Tenant-Id；
        // TrialEndpointGuard 已经在更早的 filter 链中三重校验通过。
        // 用统一的 TrialBypassPredicate 判定，与 RBAC / RateLimit /
        // InternalCaller / enforceApiQuota 共享同一边界，避免漂移。
        if (TrialBypassPredicate.isGuardedTrialRequest(requestContext)) {
            tenantContext.setCurrentTenant("trial");
            LOG.debugf("Tenant set to 'trial' for guarded trial request (path=%s)", path);
            return;
        }

        // 豁免路径：管理端点、schema 端点、AI 端点（浏览器直连，无 X-Tenant-Id）
        // /api/internal/* 跨服务接口：tenantId 在路径里或不需要，自带 HMAC 验签
        // /api/v1/lexicons* 语言包查询：登录前/匿名亦需获取可用语言列表
        // /api/v1/messages/{locale} 界面文案：与 lexicons 同源，前端匿名 fetch（无 X-Tenant-Id），
        // 单段 locale 精确匹配（见 MessagesPathMatcher，拒多段/路径穿越）
        // 用 slash-boundary 匹配防止误匹配兄弟路径（如 /api/v1/lexicons-admin）
        // AI 端点：cloud BFF 经 HMAC 签名调用时，X-Tenant-Id 在签名 canonical 里（InternalCallerFilter
        // 已验签，且本 filter 排在它之后），是已验证的真实租户，必须按它走——否则所有用户的 AI 调用
        // 都归到 "default"：token 回填 userId 错、按租户的守门免扣计数全员共享、拦截事件归不到人
        // （aster-api#360）。未验签的 AI 调用（public / trial 旁路）header 不可信，仍用默认租户。
        boolean signedAiCall = isAiPath(path)
            && io.aster.security.apikey.InternalCallerFilter.isHmacVerified(requestContext)
            && requestContext.getHeaderString(TENANT_HEADER) != null
            && !requestContext.getHeaderString(TENANT_HEADER).isBlank();
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.equals("/graphql/schema.graphql") || path.equals("graphql/schema.graphql")
                || (isAiPath(path) && !signedAiCall)
                || matchesLexiconPath(path)
                || matchesMessagesPath(path)
                || path.startsWith("/api/v1/admin/lexicons/") || path.startsWith("api/v1/admin/lexicons/")
                || path.equals("/api/v1/admin/lexicons") || path.equals("api/v1/admin/lexicons")
                || path.startsWith("/api/v1/admin/messages/") || path.startsWith("api/v1/admin/messages/")
                || path.equals("/api/v1/admin/byok-allowlist") || path.equals("api/v1/admin/byok-allowlist")
                || path.equals("/api/v1/admin/policy-versions/source-hashes")
                || path.equals("api/v1/admin/policy-versions/source-hashes")
                || path.startsWith("/api/v1/admin/revalidation/") || path.startsWith("api/v1/admin/revalidation/")
                || path.equals("/api/v1/admin/resolver-migration/run") || path.equals("api/v1/admin/resolver-migration/run")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")) {
            LOG.debugf("Bypassing tenant validation for path: %s", path);
            // 豁免路径使用默认租户
            tenantContext.setCurrentTenant("default");
            return;
        }

        String tenantId = requestContext.getHeaderString(TENANT_HEADER);

        // 严格校验：缺失或空白 header 返回 400
        if (tenantId == null || tenantId.trim().isEmpty()) {
            LOG.warnf("Missing or empty %s header for path: %s", TENANT_HEADER, path);
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(String.format("Missing or empty %s header", TENANT_HEADER))
                    .build()
            );
            return;
        }

        // 去除首尾空白
        tenantId = tenantId.trim();

        // R30 reserved-tenant denylist：外部不允许使用 sentinel 租户名。
        // 注意：trial 路径在更早的分支（TRIAL_GUARD_PASSED_PROP + TRIAL_PATH）
        // 已经 return；走到这里的请求一定不是 guard 签发的 trial，所以
        // X-Tenant-Id: trial 必然来自外部伪造，直接拒绝。
        if (RESERVED_TENANTS.contains(tenantId.toLowerCase(java.util.Locale.ROOT))) {
            LOG.warnf("Rejected reserved tenant id '%s' from external client (path=%s). "
                + "Reserved tenants are internal sentinels installed by guarded paths only.",
                sanitizeForLog(tenantId), path);
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Reserved tenant identifier is not allowed in X-Tenant-Id header.")
                    .build()
            );
            return;
        }

        // 验证租户 ID 格式（防止注入攻击）
        if (strictFormat && !TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            LOG.warnf("Invalid tenant ID format: %s (must match pattern: %s)",
                sanitizeForLog(tenantId), TENANT_ID_PATTERN.pattern());
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 characters).")
                    .build()
            );
            return;
        }

        // 兼容旧版：宽松模式下仅验证长度
        if (!strictFormat && tenantId.length() > 255) {
            LOG.warnf("Tenant ID too long: %d characters", tenantId.length());
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Tenant ID must not exceed 255 characters")
                    .build()
            );
            return;
        }

        // 可选：验证租户是否在白名单中
        if (whitelistEnabled && !isTenantAllowed(tenantId)) {
            LOG.warnf("Tenant not in whitelist: %s", sanitizeForLog(tenantId));
            requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .entity("Tenant not authorized")
                    .build()
            );
            return;
        }

        // 设置租户上下文
        tenantContext.setCurrentTenant(tenantId);
        LOG.debugf("Tenant context initialized: %s for path: %s", tenantId, path);
    }

    /**
     * 检查租户是否在白名单中
     */
    private boolean isTenantAllowed(String tenantId) {
        if (!whitelistEnabled || allowedTenants.isEmpty()) {
            return true;
        }

        Set<String> allowed = Set.of(allowedTenants.get().split(","));
        return allowed.contains(tenantId);
    }

    /**
     * 清理日志输出（防止日志注入）
     */
    private String sanitizeForLog(String input) {
        if (input == null) {
            return "null";
        }
        // 截断过长的输入，移除控制字符
        String sanitized = input.length() > 100 ? input.substring(0, 100) + "..." : input;
        return sanitized.replaceAll("[\\r\\n\\t]", "_");
    }

    private static boolean isAiPath(String path) {
        return path.startsWith("/api/v1/ai/") || path.startsWith("api/v1/ai/");
    }

    /**
     * Slash-boundary 路径匹配，避免 startsWith("/api/v1/lexicons") 误匹配
     * 兄弟路径如 /api/v1/lexicons-admin。
     */
    private boolean matchesLexiconPath(String path) {
        if ("/api/v1/lexicons".equals(path) || "api/v1/lexicons".equals(path)) return true;
        return path.startsWith("/api/v1/lexicons/") || path.startsWith("api/v1/lexicons/");
    }

    /**
     * /api/v1/messages/{locale} 界面文案查询。与 {@link #matchesLexiconPath} 同源——
     * 公开只读（登录页本身也需文案），前端 messages-loader 匿名 fetch（无 X-Tenant-Id），
     * 受 locale 可用性开关约束（MessagesResource 对未启用 locale 返回 404）。
     *
     * <p>**精确单段匹配**（Codex 安全审查）：只豁免 {@code /api/v1/messages/<locale>}
     * 这一段，locale 段内不得再有斜杠、不得是 {@code .}/{@code ..}（防 {@code
     * /messages/../policies/evaluate} 之类穿越绕过 perimeter）。资源只有 {@code
     * /{locale}}，多段路径不是合法端点——豁免范围严格收敛到实际端点，不豁免整个子树。
     */
    private boolean matchesMessagesPath(String path) {
        return MessagesPathMatcher.isPublicMessagesReadPath(path);
    }
}
