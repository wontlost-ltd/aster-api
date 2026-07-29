package io.aster.policy.rest;

import io.aster.policy.tenant.TenantContext;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * 调用方身份解析的**单一事实源**：tenant / user / apiKeyId。
 *
 * <h2>为什么需要它（issue #174 探勘时发现的真实缺陷）</h2>
 *
 * <p>此前 4 个 resource 类各自手写 {@code tenantId()}，且**已经漂移**：只有
 * {@code PolicyEvaluationResource} 带 R32 hotfix（读 JAX-RS ctx 的
 * {@code aster.apikey.tenantId} property），{@code AuditLogResource} 与
 * {@code AiAssistantResource} 没有。
 *
 * <p>后果不是风格问题而是**租户隔离不一致**：{@code ApiKeyAuthFilter} 验证通过后把
 * 权威租户写进 {@code ctx.setProperty(...)}，并覆盖 {@code X-Tenant-Id} header；但
 * header 的 mutation 是 JAX-RS 层动作，部分 RESTEasy 配置下 Vert.x 层看不到。于是
 * 一个**带有效 API key 但不带 X-Tenant-Id** 的请求，在 PolicyEvaluationResource 上
 * 正确落到自己的租户，在另两个 resource 上却回退成 {@code "default"}。
 *
 * <p>收敛到本类后，解析顺序只有一处定义，补丁（如 R32）不会再只打到一个副本上。
 *
 * <h2>解析顺序（三个方法一致）</h2>
 * <ol>
 *   <li>{@link TenantContext} —— TenantFilter 已用权威值填充；trial 流量在此被识别</li>
 *   <li>JAX-RS {@code ctx.property} —— {@code ApiKeyAuthFilter} 写入的验证结果</li>
 *   <li>Vert.x header —— 最后回退</li>
 * </ol>
 *
 * <h2>不在本类范围内</h2>
 *
 * <p>{@code PolicyGraphQLResource} **有意不收编**：GraphQL 不走 JAX-RS filter 链，
 * 它的租户由 {@code GraphQLApiKeyAuthHandler} 写进 <b>RoutingContext</b>
 * （{@code rc.put(PROP_TENANT_ID, …)}）而非 JAX-RS ctx，且语义是 <b>fail-closed</b>
 * ——认证开启却拿不到已验证租户时抛异常，而非回退 header。那是更严格的策略，
 * 强行统一会**削弱**它。
 *
 * <p>{@code @ApplicationScoped}：注入的 {@code RoutingContext} /
 * {@code ContainerRequestContext} 由容器按请求解析，本类无可变状态、线程安全。
 */
@ApplicationScoped
public class RequestIdentityResolver {

    static final String PROP_TENANT_ID = "aster.apikey.tenantId";
    static final String PROP_USER_ID = "aster.apikey.userId";
    static final String PROP_API_KEY_ID = "aster.apikey.apiKeyId";

    private static final String DEFAULT_TENANT = "default";
    private static final String ANONYMOUS_USER = "anonymous";
    /** trial 流量统一记账的用户名，与租户 "trial" 配对（R29）。 */
    private static final String TRIAL_TENANT = "trial";
    private static final String TRIAL_USER = "trial-anonymous";

    @Inject
    TenantContext tenantContext;

    @Inject
    RoutingContext routingContext;

    @Inject
    ContainerRequestContext jaxrsCtx;

    /**
     * 解析租户 ID。
     *
     * <p>R29：trial 路径不带 {@code X-Tenant-Id}，TenantFilter 已把 TenantContext 设为
     * "trial"，故先查它，确保 quota/audit/metrics 把 trial 流量记到 "trial" 而非 "default"。
     *
     * <p>R32：再查 JAX-RS ctx property——{@code ApiKeyAuthFilter} 对 header 的覆盖是
     * JAX-RS 层 mutation，Vert.x 层未必可见，直接读 header 会漏掉已验证租户。
     */
    public String tenantId() {
        String ctxTenant = currentTenantOrNull();
        if (ctxTenant != null) {
            return ctxTenant;
        }
        String prop = jaxrsProperty(PROP_TENANT_ID);
        if (prop != null) {
            return prop;
        }
        String header = header("X-Tenant-Id");
        return header != null ? header : DEFAULT_TENANT;
    }

    /**
     * 解析操作者标识，用于审计与配额记账。
     *
     * <p>R29：trial 租户统一返回 {@code trial-anonymous}，便于把 marketing playground
     * 流量从普通 anonymous 中分离。
     */
    public String performedBy() {
        if (TRIAL_TENANT.equals(currentTenantOrNull())) {
            return TRIAL_USER;
        }
        String prop = jaxrsProperty(PROP_USER_ID);
        if (prop != null) {
            return prop;
        }
        // ★这里**有意**回退读 header，与 apiKeyId() 的严格化是两种不同的语义，不要
        // 「顺手统一」：
        //
        //   apiKeyId 是**记账键**——限流桶键与计费归属，必须是已验证身份，
        //             否则攻击者可把用量记到他人账上。
        //   performedBy 是**操作者标签**——策略可被分享，允许未登录用户执行，
        //             此时本就没有已验证身份可用，anonymous 是正确的终态。
        //
        // 因此调用方应把 performedBy 当作「尽力而为的操作者标注」：它对已认证流量
        // 是准确的（ApiKeyAuthFilter:143 会覆盖客户端传值），对匿名/分享流量则是
        // 自述值或 anonymous。授权判断一律不得依赖它——授权走 tenantId + RBAC。
        String header = header("X-User-Id");
        return header != null ? header : ANONYMOUS_USER;
    }

    /**
     * 解析 API key ID；无 key 的请求返回 {@code null}。
     *
     * <p>★调用方若要在异步回调里用它，必须在**请求线程**上先取好：评估切到 worker pool
     * 后 RequestScoped 已失效，届时再读会得到 null 或抛
     * {@code ContextNotActiveException} 并被 Mutiny 静默 drop，表现为配额/计费计数
     * 无声丢失。
     */
    public String apiKeyId() {
        // ★只信任 ApiKeyAuthFilter 验证后写入的 ctx property，**不回退读客户端头**
        //（2026-07-29 审计修复）。
        //
        // X-Api-Key-Id 这个头只有一个合法写入者：ApiKeyAuthFilter:146，它在验签通过后
        // 覆盖客户端传值。但该 filter 的 shouldProtect() **显式排除 evaluate-source**
        // （改由 InternalCallerFilter 守护），那条路径上 property 与 header 都不会被设置
        // → 回退读 header 等于直接采信客户端自称的 key id。
        //
        // 后果不是信息泄露而是**记账串号**：PolicyEvaluationResource 拿它做
        // apiQuotaGuard.checkRate(tenantId, apiKeyId) 的限流桶键与 recordAsync 的
        // 计费归属，攻击者填别人的 apiKeyId 即可消耗他人速率预算、把调用记到他人账上。
        //
        // 拿不到已验证身份时返回 null（无 key 的请求本就返回 null），调用方据此按
        // 「无 API key」处理，而不是按一个伪造的 key 处理。
        return jaxrsProperty(PROP_API_KEY_ID);
    }

    /**
     * 读取 TenantContext，未初始化时返回 null 而非抛异常。
     *
     * <p>★{@code getCurrentTenant()} 在未初始化时**抛 IllegalStateException**
     * （见 TenantContext:22）。各 resource 原先的实现只判了 {@code tenantContext != null}，
     * 没判 {@code isInitialized()}——TenantFilter 未跑到的路径（如 filter 链更早失败、
     * 或非 filter 覆盖的入口）会让本该回退 header 的解析直接抛异常。
     * 这里用 {@code isInitialized()} 守卫，保持"三级回退"的原意。
     */
    private String currentTenantOrNull() {
        if (tenantContext == null || !tenantContext.isInitialized()) {
            return null;
        }
        String t = tenantContext.getCurrentTenant();
        return t == null || t.isBlank() ? null : t;
    }

    private String jaxrsProperty(String key) {
        if (jaxrsCtx == null) {
            return null;
        }
        Object v = jaxrsCtx.getProperty(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private String header(String name) {
        if (routingContext == null || routingContext.request() == null) {
            return null;
        }
        String v = routingContext.request().getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
