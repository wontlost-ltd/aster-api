package io.aster.policy.security.rbac;

import io.aster.policy.security.TrialBypassPredicate;
import io.aster.policy.security.TrialEndpointGuard;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * JAX-RS 请求过滤器：检查 X-User-Role header 是否满足 {@code @RequireRole} 注解要求。
 *
 * <p>角色来源：aster-cloud 在调用 aster-api 时，把用户的团队角色通过 X-User-Role
 * header 传递。
 *
 * <p><b>★信任模型的准确表述（issue #297）</b>——此前这里写的是
 * 「aster-api 信任此 header（因为已通过 HMAC 签名验证请求来源）」，
 * 那句话把保护范围说大了：
 *
 * <ul>
 *   <li>{@code RequestSignatureFilter} 的 HMAC 覆盖的是<b>请求体与 nonce</b>，
 *       <b>不覆盖 X-User-Role 这个 header</b>（实测：签名计算里没有它）。</li>
 *   <li>因此签名能证明的只是「调用方持有共享密钥」（即 BFF），
 *       <b>不能</b>证明「这个角色值是 BFF 按真实用户身份填的」。
 *       任何持有该密钥的一方都可以自行声明任意角色。</li>
 * </ul>
 *
 * <p>即 {@code @RequireRole} 是一道<b>面向可信 BFF 的一致性约束</b>
 * （防止 BFF 侧漏传/错传），<b>不是</b>面向不可信调用方的授权边界。
 * 真正的边界在 perimeter：签名 + TenantFilter。
 *
 * <p>要让它成为真边界，需要把 X-User-Role 纳入签名的规范化串（两侧同改），
 * 或改为由 aster-api 自己从会话/令牌解析角色——属独立的安全设计变更，
 * 不在本注释订正的范围内。
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class RoleEnforcementFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RoleEnforcementFilter.class);

    @ConfigProperty(name = "aster.security.rbac.enabled", defaultValue = "true")
    boolean rbacEnabled;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // CORS preflight 请求不带自定义 headers，必须放行
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        if (!rbacEnabled) {
            return;
        }

        RequireRole annotation = getRequireRoleAnnotation();
        if (annotation == null) {
            return;
        }

        // R28→R30 trial 旁路：匿名访客没有 X-User-Role。统一通过
        // TrialBypassPredicate 判定，与 TenantFilter / RateLimitFilter /
        // InternalCallerFilter / enforceApiQuota 同一安全边界。
        if (TrialBypassPredicate.isGuardedTrialRequest(requestContext)) {
            LOG.debugf("RBAC bypassed for trial path %s (TRIAL_GUARD_PASSED_PROP set)",
                TrialEndpointGuard.TRIAL_PATH);
            return;
        }

        String roleHeader = requestContext.getHeaderString("X-User-Role");
        if (roleHeader == null || roleHeader.isBlank()) {
            LOG.warnf("Missing X-User-Role header for protected endpoint: %s",
                    requestContext.getUriInfo().getPath());
            requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity("{\"error\":\"Missing role information\"}")
                            .build());
            return;
        }

        try {
            Role userRole = Role.valueOf(roleHeader.toUpperCase());
            Role requiredRole = annotation.value();

            if (!userRole.hasAtLeast(requiredRole)) {
                LOG.infof("Insufficient role: user=%s required=%s path=%s",
                        userRole, requiredRole, requestContext.getUriInfo().getPath());
                requestContext.abortWith(
                        Response.status(Response.Status.FORBIDDEN)
                                .entity("{\"error\":\"Insufficient permissions\",\"required\":\"" + requiredRole + "\"}")
                                .build());
            }
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid role value: %s", roleHeader);
            requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity("{\"error\":\"Invalid role\"}")
                            .build());
        }
    }

    private RequireRole getRequireRoleAnnotation() {
        Method method = resourceInfo.getResourceMethod();
        if (method != null) {
            // 方法级匿名豁免优先于一切：标了 @AnonymousAllowed 即跳过 RBAC，
            // 即使所在类带类级 @RequireRole（这正是 /schema、/validate 等只读
            // 元数据端点在受保护的 PolicyEvaluationResource 类里仍需匿名的原因）。
            if (method.isAnnotationPresent(AnonymousAllowed.class)) {
                return null;
            }
            RequireRole methodAnnotation = method.getAnnotation(RequireRole.class);
            if (methodAnnotation != null) {
                return methodAnnotation;
            }
        }

        Class<?> clazz = resourceInfo.getResourceClass();
        if (clazz != null) {
            return clazz.getAnnotation(RequireRole.class);
        }

        return null;
    }
}
