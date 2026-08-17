package io.aster.policy.rest;

import io.aster.audit.chain.AuditChainVerifier;
import io.aster.audit.chain.ChainVerificationResult;
import io.aster.policy.entity.AuditLog;
import io.smallrye.mutiny.Uni;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 审计日志查询 API
 *
 * 提供审计日志的查询功能，用于合规审计和事后调查：
 * - 按租户查询
 * - 按事件类型查询
 * - 按策略查询
 * - 按时间范围查询
 *
 * 注意：所有API都通过 X-Tenant-Id 实现多租户隔离
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@RequireRole(Role.ADMIN)
public class AuditLogResource {

    private static final Logger LOG = Logger.getLogger(AuditLogResource.class);

    /**
     * issue #174：身份解析收敛到共享 resolver。此前本类自写 tenantId()，
     * **缺 R32 hotfix**（不读 ApiKeyAuthFilter 写入的 ctx property），带有效
     * API key 但不带 X-Tenant-Id 的请求会误落到 "default" 租户。
     */
    @jakarta.inject.Inject
    io.aster.policy.rest.RequestIdentityResolver identityResolver;

    @Inject
    AuditChainVerifier chainVerifier;

    /**
     * 查询指定租户的所有审计日志
     *
     * GET /api/audit
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @io.smallrye.common.annotation.Blocking
    public Uni<List<AuditLog>> getAllLogs(
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("50") int size
    ) {
        String tenantId = tenantId();
        LOG.infof("Fetching audit logs for tenant: %s (page=%d, size=%d)", tenantId, page, size);

        return Uni.createFrom().item(() -> AuditLog.findByTenant(tenantId, page, size));
    }

    /**
     * 查询指定事件类型的审计日志
     *
     * GET /api/audit/type/{eventType}
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @Path("/type/{eventType}")
    @io.smallrye.common.annotation.Blocking
    public Uni<List<AuditLog>> getLogsByEventType(
        @PathParam("eventType") String eventType,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("50") int size
    ) {
        String tenantId = tenantId();
        LOG.infof("Fetching audit logs by event type: %s for tenant: %s (page=%d, size=%d)", eventType, tenantId, page, size);

        return Uni.createFrom().item(() -> AuditLog.findByEventType(eventType, tenantId, page, size));
    }

    /**
     * 查询指定策略的审计日志
     *
     * GET /api/audit/policy/{policyModule}/{policyFunction}
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @Path("/policy/{policyModule}/{policyFunction}")
    @io.smallrye.common.annotation.Blocking
    public Uni<List<AuditLog>> getLogsByPolicy(
        @PathParam("policyModule") String policyModule,
        @PathParam("policyFunction") String policyFunction,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("50") int size
    ) {
        String tenantId = tenantId();
        LOG.infof("Fetching audit logs for policy: %s.%s (tenant: %s, page=%d, size=%d)",
            policyModule, policyFunction, tenantId, page, size);

        return Uni.createFrom().item(() ->
            AuditLog.findByPolicy(policyModule, policyFunction, tenantId, page, size));
    }

    /**
     * 查询指定时间范围的审计日志
     *
     * GET /api/audit/range?startTime={ISO8601}&endTime={ISO8601}
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @Path("/range")
    @io.smallrye.common.annotation.Blocking
    public Uni<List<AuditLog>> getLogsByTimeRange(
        @QueryParam("startTime") String startTimeStr,
        @QueryParam("endTime") String endTimeStr,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("50") int size
    ) {
        String tenantId = tenantId();
        LOG.infof("Fetching audit logs for time range: %s to %s (tenant: %s, page=%d, size=%d)",
            startTimeStr, endTimeStr, tenantId, page, size);

        try {
            Instant startTime = Instant.parse(startTimeStr);
            Instant endTime = Instant.parse(endTimeStr);

            return Uni.createFrom().item(() ->
                AuditLog.findByTimeRange(startTime, endTime, tenantId, page, size));
        } catch (Exception e) {
            LOG.errorf(e, "Invalid time format: startTime=%s, endTime=%s", startTimeStr, endTimeStr);
            throw new BadRequestException("Invalid time format. Use ISO8601 format (e.g., 2024-01-01T00:00:00Z)");
        }
    }

    /**
     * 验证审计哈希链完整性（Phase 0 Task 3.4）
     *
     * GET /api/audit/verify-chain?start={ISO8601}&end={ISO8601}
     * Headers: X-Tenant-Id (optional, defaults to "default")
     *
     * @param startTimeStr 开始时间（ISO8601 格式）
     * @param endTimeStr   结束时间（ISO8601 格式）
     * @return 验证结果 JSON
     */
    @GET
    @Path("/verify-chain")
    @io.smallrye.common.annotation.Blocking
    @Operation(summary = "验证审计哈希链完整性",
        description = "通过校验哈希链的连续性与逐条摘要，检测审计记录被修改或被中间删除。"
            + "范围说明：（1）hash_version=2 的记录覆盖全部业务字段；"
            + "hash_version=1 的历史记录仅覆盖 6 个字段（event_type/timestamp/tenant_id/"
            + "policy_module/policy_function/success），其余字段的改动在这些历史行上不可检测。"
            + "（2）删除链**尾部**记录不会破坏剩余链，故无法由本接口检出——"
            + "该场景需依赖数据库层的 append-only 约束或外部锚定。")
    @APIResponse(responseCode = "200", description = "验证结果（包含 valid、brokenAt、reason、recordsVerified 字段）")
    @APIResponse(responseCode = "400", description = "缺少必需参数或参数格式错误")
    @APIResponse(responseCode = "500", description = "验证失败（服务器内部错误）")
    public Uni<Response> verifyChain(
        @QueryParam("start") String startTimeStr,
        @QueryParam("end") String endTimeStr
    ) {
        String tenantId = tenantId();

        // 参数验证
        if (startTimeStr == null || endTimeStr == null) {
            LOG.warnf("Missing required parameters: start=%s, end=%s", startTimeStr, endTimeStr);
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing required parameters: start and end are required\"}")
                    .build()
            );
        }

        try {
            Instant startTime = Instant.parse(startTimeStr);
            Instant endTime = Instant.parse(endTimeStr);

            // 防止大范围查询（限制 30 天）
            Duration duration = Duration.between(startTime, endTime);
            if (duration.toDays() > 30) {
                LOG.warnf("Time range too large: %d days (limit: 30 days)", duration.toDays());
                return Uni.createFrom().item(
                    Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Time range too large. Maximum: 30 days\"}")
                        .build()
                );
            }

            LOG.infof("Verifying audit chain: tenant=%s, start=%s, end=%s", tenantId, startTimeStr, endTimeStr);

            // 执行验证（阻塞式调用）
            return Uni.createFrom().item(() -> {
                ChainVerificationResult result = chainVerifier.verifyChain(tenantId, startTime, endTime);

                // 构建 JSON 响应
                String json = String.format(
                    "{\"valid\":%b,\"brokenAt\":%s,\"reason\":%s,\"recordsVerified\":%d}",
                    result.isValid(),
                    result.getBrokenAt() != null ? "\"" + result.getBrokenAt() + "\"" : "null",
                    result.getReason() != null ? "\"" + result.getReason().replace("\"", "\\\"") + "\"" : "null",
                    result.getRecordsVerified()
                );

                if (result.isValid()) {
                    LOG.infof("Chain verification succeeded: tenant=%s, recordsVerified=%d",
                        tenantId, result.getRecordsVerified());
                } else {
                    LOG.warnf("Chain verification failed: tenant=%s, reason=%s",
                        tenantId, result.getReason());
                }

                return Response.ok(json).build();
            }).onFailure().recoverWithItem(t -> {
                LOG.errorf(t, "Chain verification error: tenant=%s", tenantId);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Verification failed: " + t.getMessage().replace("\"", "\\\"") + "\"}")
                    .build();
            });

        } catch (Exception e) {
            LOG.errorf(e, "Invalid time format: start=%s, end=%s", startTimeStr, endTimeStr);
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid time format. Use ISO8601 format (e.g., 2025-01-15T10:00:00Z)\"}")
                    .build()
            );
        }
    }

    /**
     * 提取租户ID
     *
     * 从 X-Tenant-Id 请求头提取租户ID，如果不存在则返回 "default"
     */
    private String tenantId() {
        return identityResolver.tenantId();
    }
}
