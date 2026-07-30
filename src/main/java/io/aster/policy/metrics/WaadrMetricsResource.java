package io.aster.policy.metrics;

import io.aster.policy.metrics.dto.WaadrPoint;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * WAADR 北极星指标查询端点
 *
 * <p>始终且只返回当前 X-Tenant-Id 的数据。★不提供跨租户聚合：{@link Role} 只有
 * OWNER/ADMIN/MEMBER/VIEWER 四级，全部是**租户内**角色（镜像 aster-cloud 的 TeamRole），
 * 代码库中不存在 PLATFORM_ADMIN 平台级角色。此前 {@code tenant=*} 参数配合
 * {@code @RequireRole(ADMIN)} 意在"仅平台管理员可跨租户"，但由于 ADMIN 实际是租户内
 * 角色，任何租户自己的 admin 用自己的合法凭据即可拉到全平台租户名单及各租户的
 * 周度规则采纳量（按作者角色维度）——跨租户越权读。
 *
 * <p>若将来确需平台级聚合，必须先引入独立的平台角色（而非复用租户内 ADMIN），
 * 再由该角色显式授权；在此之前本端点不接受任何跨租户参数。
 */
@Path("/api/v1/metrics/waadr")
@Produces(MediaType.APPLICATION_JSON)
@RequireRole(Role.ADMIN)
public class WaadrMetricsResource {

    @Inject
    WaadrMetricsService service;

    @Inject
    io.aster.policy.tenant.TenantContext tenantContext;

    @Context
    RoutingContext routingContext;

    @GET
    @Blocking
    public List<WaadrPoint> getWeeklyWaadr(
        @QueryParam("weeks") @DefaultValue("12") int weeks
    ) {
        int safeWeeks = Math.max(1, Math.min(weeks, 52));
        // 租户 ID 只取自服务端 TenantContext，不接受任何客户端传入的租户参数。
        return service.fetchWeeklyWaadr(currentTenantId(), safeWeeks);
    }

    private String currentTenantId() {
        // 优先读 TenantContext（权威，由 TenantFilter 从 ApiKeyAuthFilter 覆盖后的
        // X-Tenant-Id 填充）；Vert.x 原始 header 不可靠，见 AuditLogResource.tenantId。
        if (tenantContext != null) {
            String ctxTenant = tenantContext.getCurrentTenant();
            if (ctxTenant != null && !ctxTenant.isBlank()) {
                return ctxTenant;
            }
        }
        if (routingContext == null || routingContext.request() == null) {
            return "default";
        }
        String tenant = routingContext.request().getHeader("X-Tenant-Id");
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }
}
