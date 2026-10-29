package io.aster.policy.rest;

import io.aster.policy.tenant.TenantContext;
import io.aster.security.apikey.InternalCallerFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.Arrays;
import java.util.List;

/**
 * 调用方身份解析的**单一事实源**：tenant / user / apiKeyId / quotaOwnerId。
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
 * <h2>解析顺序（tenantId / performedBy / apiKeyId 一致）</h2>
 * <ol>
 *   <li>{@link TenantContext} —— TenantFilter 已用权威值填充；trial 流量在此被识别</li>
 *   <li>JAX-RS {@code ctx.property} —— {@code ApiKeyAuthFilter} 写入的验证结果</li>
 *   <li>Vert.x header —— 最后回退</li>
 * </ol>
 *
 * <h2>quotaOwnerId 的解析顺序（ADR 0015 §3）</h2>
 * <ol>
 *   <li>JAX-RS {@code ctx.property} {@code aster.apikey.quotaOwnerId} —— {@code ApiKeyAuthFilter}
 *       验证 key 后写入（团队 key 为 team owner，个人 key 等于 userId）</li>
 *   <li>回退 {@link #performedBy()} —— 无 key、旧 cloud 未下发时，配额归属就是操作者本人</li>
 * </ol>
 *
 * <p>它先读属性、后（经 {@code performedBy()}）判 trial，与 {@code performedBy()} 的顺序相反。
 * 两者只在「请求既带已验证 key、租户又是 trial」时分叉，生产不可达：{@code TenantFilter} 只在
 * {@code TrialEndpointGuard} 放行的 {@code /evaluate-source} 上设置 trial 租户，外部传
 * {@code X-Tenant-Id: trial} 一律拒绝；而 {@code ApiKeyAuthFilter} 不保护 {@code /evaluate-source}
 * （由 {@code InternalCallerFilter} 守护），该路径上属性从不写入，trial 流量必然回退到
 * {@code trial-anonymous}。这一结论依赖过滤器不保护 {@code /evaluate-source}；若将来让
 * {@code ApiKeyAuthFilter} 覆盖该路径，需重新审视此顺序。
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
    static final String PROP_QUOTA_OWNER_ID = "aster.apikey.quotaOwnerId";
    /** 已验证业务角色集合（ADR 0042 §2.2）：ApiKeyAuthFilter / InternalCallerFilter 写入的 {@code List<String>}。 */
    public static final String PROP_BUSINESS_ROLES = "aster.identity.businessRoles";
    /**
     * HMAC 内部调用者签过的用户 ID（ADR 0042 §4.1）：InternalCallerFilter 验签与 nonce 校验都通过后写入，可为空串。
     * 属性存在（含空串）时 {@link #performedBy()} 不再读未签名的 {@code X-User-Id} 头。
     */
    public static final String PROP_IDENTITY_USER_ID = "aster.identity.userId";

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
        // HMAC 通道签过的用户 ID（ADR 0042 §4.1）：属性存在即身份终态。签的是空串时返回 anonymous，
        // 绝不回退读未签名的 X-User-Id 头——否则安全性要依赖 JAX-RS 与 Vert.x 对多值头的看法一致。
        if (jaxrsCtx != null && jaxrsCtx.getProperty(PROP_IDENTITY_USER_ID) instanceof String signed) {
            return signed.isBlank() ? ANONYMOUS_USER : signed.trim();
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
     * 是否为 cloud 代登录会话发起的内部调用：HMAC 已验签（{@code InternalCallerFilter} 盖章）且没有已验证的 API key。
     *
     * <p>这类调用不按「API 调用」处理：Free 计划「不含 Policy Execution API」的月度门禁与 API 调用计量
     * 都只针对 API key 调用，本服务对内部会话调用<b>不做任何套餐或用量限制</b>。
     *
     * <p><b>豁免的前提（契约）</b>：每一个经 plan-gate HMAC 调用本服务执行类端点（如 {@code /evaluate-source}）
     * 的 cloud 路由，都必须在 cloud 侧自行做套餐与用量限制——界面执行、secure-execute、会话 evaluate-source
     * 按执行用量（UsageRecord type=execution，Free 每月 1000 次）拦截并计数，cloud REST API（Bearer key 打
     * cloud）校验 apiAccess 并按 owner 池（apiCallRecords）计 API 调用。cloud 新增 HMAC 调用方时必须同时补上
     * cloud 侧配额，否则它在这里不受任何计量。
     *
     * <p>判定只认验签章（验签与 nonce 防重放都通过后才盖），不看 {@code X-Internal-Caller} 等头是否存在：
     * 未签名、签名不对或重放的请求拿不到章，仍按 API 调用走配额门禁。
     */
    public boolean internalSessionCall() {
        return InternalCallerFilter.isHmacVerified(jaxrsCtx) && apiKeyId() == null;
    }

    /** 配额归属：团队 key 为 team owner，个人 key 等于 performedBy；属性缺失（旧 cloud / 无 key）回退 performedBy。 */
    public String quotaOwnerId() {
        String prop = jaxrsProperty(PROP_QUOTA_OWNER_ID);
        return prop != null ? prop : performedBy();
    }

    /**
     * 解析调用方的业务角色集合（ADR 0042 §2.2），供审批匹配使用。
     *
     * <p>先读过滤器写入的已验证属性（ApiKeyAuthFilter 或 InternalCallerFilter 验签后写入，
     * 两者都在时 ApiKeyAuthFilter 晚执行、覆盖之）；无属性（两条通道都没经过）时回退读
     * {@code X-User-Business-Roles} 头（逗号切分、去首尾空白、去空项）；都没有则空列表。
     * 经过任一认证通道的请求，调用方自报的未签值不会到达这里。
     */
    public List<String> businessRoles() {
        if (jaxrsCtx != null && jaxrsCtx.getProperty(PROP_BUSINESS_ROLES) instanceof List<?> prop) {
            return prop.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        String header = header("X-User-Business-Roles");
        if (header == null) {
            return List.of();
        }
        return Arrays.stream(header.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
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
