package io.aster.policy.rest;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.rest.model.PolicyVersionInfo;
import io.aster.policy.rest.model.RollbackRequest;
import io.aster.policy.rest.model.RollbackResponse;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.policy.service.PolicyVersionService;
import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * REST API 资源：策略**版本管理**（回滚 + 版本历史）。
 *
 * <p>issue #174：从 {@code PolicyEvaluationResource}（曾 1500+ 行）抽出。选它作为
 * 第一刀是因为**耦合最低、职责最清**——两个端点只依赖 {@link PolicyVersionService}、
 * 审计事件与 NSM 埋点，与评估路径的 evaluator / quota / metrics / permit 闸门
 * 完全无关；且线程模型（{@code @Blocking}）与鉴权（方法级 ADMIN）都与评估端点不同，
 * 本就是另一类关注点。
 *
 * <p><b>类级注解必须与原类一致</b>：{@code @RequireRole(Role.MEMBER)} 是
 * {@code rollback} 方法级 ADMIN 提权的**基线**——RoleEnforcementFilter 优先方法注解，
 * 但类级默认决定了 {@code getVersionHistory} 的门槛。漏掉它会让版本历史对任何
 * 认证用户开放。
 *
 * <p>身份解析走共享的 {@link RequestIdentityResolver}（同 issue 的前置修复），
 * 不再手抄 {@code tenantId()}/{@code performedBy()}——那 4 份副本此前已漂移。
 */
@Path("/api/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class PolicyVersionResource {

    private static final Logger LOG = Logger.getLogger(PolicyVersionResource.class);

    @Inject
    PolicyVersionService versionService;

    @Inject
    Event<AuditEvent> auditEventPublisher;

    @Inject
    NsmTelemetry nsmTelemetry;

    /** 身份解析单一事实源（见 {@link RequestIdentityResolver}）。 */
    @Inject
    RequestIdentityResolver identityResolver;

    /**
     * 回滚策略到指定版本
     *
     * POST /api/policies/{policyId}/rollback
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "targetVersion": 1730890123456, "reason": "回滚原因" }
     */
    @POST
    @Path("/{policyId}/rollback")
    @io.smallrye.common.annotation.Blocking
    // 红队 P1-E：rollback 是生产突变操作（激活旧版本 = 改变线上决策行为），类级默认
    // 的 MEMBER 权限偏低。方法级提升到 ADMIN（RoleEnforcementFilter 优先方法注解）。
    // 结合 P0-A 的租户范围校验：只有本租户的 ADMIN/OWNER 才能回滚本租户策略。
    @io.aster.policy.security.rbac.RequireRole(io.aster.policy.security.rbac.Role.ADMIN)
    public Uni<RollbackResponse> rollback(
        @PathParam("policyId") String policyId,
        @Valid RollbackRequest request
    ) {
        String tenantId = identityResolver.tenantId();
        String performedBy = identityResolver.performedBy();

        LOG.infof("Rolling back policy %s to version %d for tenant %s",
            policyId, request.targetVersion(), tenantId);

        return Uni.createFrom().item(() -> {
            // 获取当前活跃版本（用于审计日志）——租户范围，防跨租户探测（P0-A）
            PolicyVersion currentVersion = versionService.getActiveVersion(policyId, tenantId);
            Long fromVersion = currentVersion != null ? currentVersion.version : null;

            // 执行回滚（收敛到正常激活路径：校验 APPROVED + 同步 catalog + 发激活通知）
            // 传 tenantId：目标版本必须归属当前租户，堵跨租户回滚（P0-A）
            PolicyVersion rolledBackVersion = versionService.rollbackToVersion(
                policyId,
                request.targetVersion(),
                performedBy,
                tenantId
            );

            auditEventPublisher.fireAsync(
                AuditEvent.rollback(
                    tenantId,
                    rolledBackVersion.moduleName,
                    policyId,
                    fromVersion,
                    rolledBackVersion.version,
                    performedBy,
                    request.reason()
                )
            );

            // NSM 埋点：rule_rolled_back（详见 03-telemetry-spec.md）
            // days_after_publish 暂留 -1，待 PolicyVersion 加入 publishedAt/activatedAt 后回填精确值
            long daysAfterPublish = -1L;
            if (currentVersion != null && currentVersion.activatedAt != null) {
                daysAfterPublish = java.time.Duration.between(
                    currentVersion.activatedAt, java.time.Instant.now()
                ).toDays();
            }
            nsmTelemetry.track(
                performedBy,
                NsmEvents.RULE_ROLLED_BACK,
                java.util.Map.of(
                    "rule_id", policyId,
                    "from_version", fromVersion != null ? fromVersion : -1,
                    "to_version", rolledBackVersion.version,
                    "days_after_publish", daysAfterPublish,
                    "reason", request.reason() != null ? request.reason() : "",
                    "tenant_id", tenantId
                )
            );

            LOG.infof("Policy %s successfully rolled back to version %d",
                policyId, rolledBackVersion.version);

            return RollbackResponse.success(rolledBackVersion.version);
        })
        .onFailure(IllegalArgumentException.class)
        .recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Rollback failed for policy %s", policyId);
            return RollbackResponse.failure("版本不存在: " + request.targetVersion());
        })
        .onFailure().recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Rollback failed for policy %s", policyId);
            return RollbackResponse.failure("回滚失败: " + throwable.getMessage());
        });
    }

    /**
     * 获取策略版本历史
     *
     * GET /api/policies/{policyId}/versions
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @Path("/{policyId}/versions")
    @io.smallrye.common.annotation.Blocking
    public Uni<List<PolicyVersionInfo>> getVersionHistory(@PathParam("policyId") String policyId) {
        String tenantId = identityResolver.tenantId();

        LOG.infof("Fetching version history for policy %s (tenant: %s)", policyId, tenantId);

        return Uni.createFrom().item(() -> {
            // 租户范围：只返回当前租户的版本，防跨租户读别租户版本历史（P0-A）
            List<PolicyVersion> versions = versionService.getAllVersions(policyId, tenantId);
            return versions.stream()
                .map(v -> new PolicyVersionInfo(
                    v.version,
                    v.active,
                    v.moduleName,
                    v.functionName,
                    v.createdAt,
                    v.createdBy,
                    v.notes,
                    v.sourceKind // G6：导出版本来源（manual/ai_draft/…），审计可见
                ))
                .toList();
        });
    }
}
