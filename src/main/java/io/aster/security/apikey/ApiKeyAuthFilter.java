package io.aster.security.apikey;

import io.aster.security.apikey.ApiKeyVerifyResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Map;

/**
 * API key 认证过滤器
 *
 * 仅作用于公开生产路径：/api/v1/policies/evaluate{,-json,/batch}
 * 不作用于：/api/internal/*（内部 HMAC）、/api/v1/policies/evaluate-source（仅 BFF）、
 *          /api/v1/policies（其它 CRUD 由 cloud 处理）、/q/*（健康检查）
 *
 * 行为：
 *   - 缺少 Authorization Bearer → 401
 *   - 格式不对（不以 ak_ 开头）→ 401
 *   - 验证失败（not_found / revoked / expired）→ 401（响应体含 reason）
 *   - 验证通过 → 把 userId/tenantId/apiKeyId 放进 RoutingContext
 *     供下游 ApiQuotaGuard.check() / recordApiCall() 使用
 *
 * Priority 比 RequestSignatureFilter (AUTHENTICATION) 略低，确保 HMAC 先验。
 */
@ApplicationScoped
public class ApiKeyAuthFilter {

    private static final Logger LOG = Logger.getLogger(ApiKeyAuthFilter.class);

    @Inject
    ApiKeyVerifierService verifier;

    @ConfigProperty(name = "aster.security.apikey.enabled", defaultValue = "true")
    boolean enabled;

    // R32：filter 在 event loop 上运行。底层 Redis getApiKey 抛
    // "cannot be blocked" 是因为 LocalQuotaSnapshotService 用了同步
    // ValueCommands<String>。修复在 snapshot service 层用 try/catch
    // 包住 IllegalStateException，让 redis 路径 fall through 到 cloud
    // verify 而不是抛到 filter（fail-closed → 401）。
    /**
     * R32 hotfix v3: 返回 {@code Uni<Response>}（null = continue chain）。
     * RESTEasy Reactive 看到 Uni 就不会阻塞 event-loop 等结果，而是 subscribe
     * 后让出线程；verify 在 {@link ApiKeyVerifierService#verifyPool} 上跑完
     * 再触发下游 emission。Cache 命中走 fast path 同步返回，不付 Uni 调度开销。
     *
     * @return null/Uni.item(null) = 继续；Uni.item(Response) = 401 短路
     */
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 100)
    public io.smallrye.mutiny.Uni<Response> filter(ContainerRequestContext ctx) {
        if (!enabled) return null;

        String path = ctx.getUriInfo().getPath();
        if (!shouldProtect(path)) {
            return null;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return io.smallrye.mutiny.Uni.createFrom().item(unauthorizedResponse("missing_authorization"));
        }
        String key = auth.substring(7).trim();
        if (key.isEmpty()) {
            return io.smallrye.mutiny.Uni.createFrom().item(unauthorizedResponse("empty_token"));
        }

        // Cache fast path：Caffeine 命中即同步返回，不付 Uni 调度开销。
        ApiKeyVerifyResult cached = verifier.tryCacheLookup(key);
        if (cached != null) {
            return applyResult(ctx, cached, path);
        }

        // Cache miss：把 verify 丢到 verifyPool，event-loop 立刻让出。
        return io.smallrye.mutiny.Uni
            .createFrom().item(() -> verifier.verify(key))
            .runSubscriptionOn(verifier.verifyExecutor())
            .onItem().transform(result -> applyResultSync(ctx, result, path));
    }

    /**
     * Cache 命中或 fast path 同步路径：apply 结果并返回 Uni（null = continue）。
     */
    private io.smallrye.mutiny.Uni<Response> applyResult(ContainerRequestContext ctx,
                                                         ApiKeyVerifyResult result,
                                                         String path) {
        Response r = applyResultSync(ctx, result, path);
        return r == null ? io.smallrye.mutiny.Uni.createFrom().nullItem()
                         : io.smallrye.mutiny.Uni.createFrom().item(r);
    }

    /** 返回 null = 验证通过继续；非 null = 401/403 短路。 */
    private Response applyResultSync(ContainerRequestContext ctx,
                                     ApiKeyVerifyResult result, String path) {
        if (!result.valid()) {
            LOG.infof("apikey verify rejected: path=%s reason=%s", path, result.reason());
            return unauthorizedResponse(result.reason() != null ? result.reason() : "invalid_key");
        }

        // 租户隔离铁律：API key 绑定唯一租户。若调用方自带 X-Tenant-Id，
        // 必须与 key 所属租户精确匹配，否则拒绝。
        //
        // 历史漏洞：此前缺失该校验时，TenantFilter 会把客户端 header 写进
        // TenantContext，而 PolicyEvaluationResource.tenantId() 优先读
        // TenantContext —— 于是持有任意有效 key 的调用方带 X-Tenant-Id: victim
        // 即可对 victim 租户执行策略 / 清缓存 / 回滚 / 查版本（跨租户越权）。
        // 在认证点 fail-closed 是唯一可靠的拦截位置：这里同时握有"已验证租户"
        // 和"请求 header"，且早于 TenantFilter 填充 TenantContext。
        String verifiedTenant = result.tenantId();
        // 自洽 invariant：valid 的验证结果必须携带租户。缺失说明 verify 上游
        // （cloud / snapshot）数据不完整——此时 fail-closed，而不是放行后依赖
        // 下游 TenantFilter 兜底（语义不稳，且可能落到 "default" 租户）。
        if (verifiedTenant == null || verifiedTenant.isBlank()) {
            LOG.warnf("apikey valid but tenant binding missing: path=%s — denying", path);
            return forbiddenResponse("invalid_tenant_binding");
        }
        String headerTenant = ctx.getHeaderString("X-Tenant-Id");
        if (isTenantMismatch(verifiedTenant, headerTenant)) {
            LOG.warnf("apikey tenant mismatch: path=%s key_tenant=%s header_tenant=%s — denying",
                path, sanitize(verifiedTenant), sanitize(headerTenant));
            return forbiddenResponse("tenant_mismatch");
        }

        ctx.setProperty("aster.apikey.userId", result.userId());
        ctx.setProperty("aster.apikey.tenantId", result.tenantId());
        ctx.setProperty("aster.apikey.apiKeyId", result.apiKeyId());
        // 无条件以已验证租户覆盖 header：上面已确保 header 要么缺失、要么与
        // 验证结果一致，覆盖后下游（TenantFilter / TenantContext）只会看到
        // 权威值，杜绝任何 header 旁路。
        if (verifiedTenant != null && !verifiedTenant.isBlank()) {
            ctx.getHeaders().putSingle("X-Tenant-Id", verifiedTenant);
        }
        // 与 X-Tenant-Id / X-User-Role 一致：身份头一律以已验证结果**无条件覆盖**，
        // 不沿用客户端自带值。即使下游目前优先读 aster.apikey.* property，无条件
        // 覆盖也杜绝未来某资源直接读 header 时被伪造身份污染审计归因 / keyId。
        if (result.userId() != null) {
            ctx.getHeaders().putSingle("X-User-Id", result.userId());
        }
        if (result.apiKeyId() != null) {
            ctx.getHeaders().putSingle("X-Api-Key-Id", result.apiKeyId());
        }
        // 角色不可伪造：用已验证结果里的权威角色**无条件覆盖** X-User-Role，
        // 决不沿用客户端自带的值。否则持普通 MEMBER key 的调用方只要带
        // X-User-Role: ADMIN 就能通过下游 @RequireRole(ADMIN)（提权）。
        // role 由 cloud verify 按 key 所属用户在其租户内的真实角色返回；
        // 缺失时 ApiKeyVerifyResult.valid 已回退到最小权限 MEMBER。
        String verifiedRole = result.role() != null && !result.role().isBlank()
            ? result.role() : ApiKeyVerifyResult.DEFAULT_ROLE;
        ctx.getHeaders().putSingle("X-User-Role", verifiedRole);
        return null;
    }

    /**
     * 仅保护公开生产路径。
     *
     * <p>R21-Critical-1 背景：生产 k3s 部署把全局 {@code aster.security.signature.enabled}
     * 关掉了（理由：浏览器 Monaco/AI/lexicon 直连无法签名），但这意味着
     * "策略版本管理" 类端点（rollback / cache-clear / version 列表）会失去全局签名
     * 兜底而完全敞开。这些端点是租户内部突变操作，必须 API key 守护。
     *
     * <p>分层（含此 filter 覆盖范围）：
     * <ul>
     *   <li>{@code /evaluate*} —— public 计算端点：API key 必填</li>
     *   <li>{@code /evaluate-source} —— internal SSE：InternalCallerFilter 守护</li>
     *   <li>{@code /{policyId}/rollback} —— 突变：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /{policyId}/versions} —— 历史查询：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /cache} —— 缓存清理：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /schema}、{@code /validate} —— 元数据查询：保持匿名（无副作用）</li>
     *   <li>{@code /api/v1/workflows/*} —— workflow 审计只读：API key 必填（见下）</li>
     * </ul>
     */
    private static boolean shouldProtect(String path) {
        // R23-Critical-1 + R25-Critical-1：matrix-param 归一化必须 **per-segment**。
        // R23 的 "在第一个 ; 截断" 把 /foo;x/rollback → /foo（丢失 /rollback）→ 绕过。
        // R25 改用 PathNormalizer 按 / 切段、每段独立 strip ;... 后拼回，与 RESTEasy
        // 路由层一致。
        String p = io.aster.security.PathNormalizer.normalize(path);

        // 管理类只读端点：审计日志 / 分析 / 版本使用 / WAADR 指标。文档明确要求
        // Authorization: Bearer + ADMIN 角色，但历史上它们既不在本 filter 覆盖、
        // 也不在 InternalCallerFilter 的 HMAC 范围（HMAC 只守 /ai/* 与
        // /evaluate-source）。生产签名关闭时，bearer token 形同虚设，仅剩可伪造的
        // X-User-Role + 一个无法在代码中验证的 ingress 剥离假设。这里把它们纳入
        // API key 鉴权，让 token 被真实验证、并强制已验证租户（跨租户隔离），与
        // 文档契约一致；@RequireRole(ADMIN) 在可信层之上继续生效。
        // （这些端点没有 BFF/内部调用方，纳入鉴权不破坏任何现有调用契约。）
        if (p.startsWith("/api/v1/audit/") || p.equals("/api/v1/audit")) return true;
        // 用 equals + "/" 前缀，避免 startsWith 误伤兄弟路径（/api/v1/metrics/waadrXYZ）。
        if (p.equals("/api/v1/metrics/waadr") || p.startsWith("/api/v1/metrics/waadr/")) return true;

        // WorkflowAuditResource 的四个只读端点（events / state / metrics / by-status）。
        // 它们全部以 RequestIdentityResolver.tenantId() 作为租户谓词，而该解析器的
        // 末级回退就是裸 X-Tenant-Id header；类级 @RequireRole(MEMBER) 又只校验同样
        // 可伪造的 X-User-Role。生产签名关闭后，这两个头没有任何验证方，等于
        // 「带上受害者租户 ID 即可读其 workflow 事件历史（含 payload）、状态与聚合指标」。
        //
        // 2026-07-29 那次修复补的是「谓词缺失」（此前根本不按租户过滤），但谓词的
        // **输入**仍是客户端自述值——纳入本 filter 后，X-Tenant-Id / X-User-Role 会被
        // 已验证结果无条件覆盖（见 applyResultSync），谓词才真正有可信输入。
        // 用 equals + "/" 前缀，避免误伤 /api/v1/workflowsXYZ 之类兄弟路径。
        if (p.equals("/api/v1/workflows") || p.startsWith("/api/v1/workflows/")) return true;

        if (!p.startsWith("/api/v1/policies/")) return false;
        // 公开 evaluate 端点（按调用量计费）
        // 显式排除 evaluate-source（由 InternalCallerFilter 守护）
        if (p.equals("/api/v1/policies/evaluate")) return true;
        if (p.equals("/api/v1/policies/evaluate-json")) return true;
        if (p.equals("/api/v1/policies/evaluate/batch")) return true;
        // R21-Critical-1：cache 清理 (DELETE /cache) —— 突变操作必须鉴权
        if (p.equals("/api/v1/policies/cache")) return true;
        // R21-Critical-1：版本管理 —— rollback / versions 列表必须鉴权
        // path 形如 /api/v1/policies/{policyId}/rollback 或 /{policyId}/versions
        if (p.endsWith("/rollback")) return true;
        if (p.endsWith("/versions")) return true;
        return false;
    }

    /**
     * 租户匹配判定（纯函数，便于单测）。
     *
     * <p>规则：调用方未带 {@code X-Tenant-Id}（null/空白）时视为"无主张"，
     * 不算 mismatch（下游会以已验证租户填充）。带了就必须 trim 后与已验证
     * 租户精确相等，否则 mismatch → 拒绝。
     *
     * @return true 表示存在冲突，必须拒绝
     */
    static boolean isTenantMismatch(String verifiedTenant, String headerTenant) {
        // #55: 单一事实来源——委托给 ApiKeyPerimeter，避免 REST filter 与 GraphQL
        // route handler 的租户冲突判定语义漂移。
        return ApiKeyPerimeter.isTenantMismatch(verifiedTenant, headerTenant);
    }

    private static Response unauthorizedResponse(String reason) {
        return Response.status(401)
            .entity(Map.of(
                "error", "unauthorized",
                "reason", reason,
                "message", "Invalid or revoked API key. See https://aster-lang.cloud/billing/api-keys"
            ))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private static Response forbiddenResponse(String reason) {
        return Response.status(403)
            .entity(Map.of(
                "error", "forbidden",
                "reason", reason,
                "message", "The X-Tenant-Id header does not match the tenant bound to this API key."
            ))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    /** 日志注入防护：截断 + 去除控制字符（header 值来自不可信客户端）。 */
    private static String sanitize(String input) {
        if (input == null) return "null";
        String s = input.length() > 64 ? input.substring(0, 64) + "..." : input;
        return s.replaceAll("[\\r\\n\\t]", "_");
    }
}
