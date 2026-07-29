package io.aster.api.workflow;

import aster.runtime.workflow.WorkflowEvent;
import aster.runtime.workflow.WorkflowState;
import io.aster.api.workflow.dto.WorkflowEventDTO;
import io.aster.api.workflow.dto.WorkflowMetricsDTO;
import io.aster.api.workflow.dto.WorkflowStateDTO;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workflow 审计和查询 REST API
 *
 * 提供 workflow 事件查询、状态查询和指标接口，满足审计和可观测性要求。
 *
 * <h2>租户隔离（2026-07-29 审计修复）</h2>
 *
 * <p>本类此前**完全没有租户谓词**：四个端点都只按 workflowId（或干脆不带条件）查询，
 * 任何持有 MEMBER 角色的调用方都能读到其他租户的事件历史（含 payload）、状态、
 * 全局计数以及 workflow ID 列表。
 *
 * <p>现在统一经 {@link RequestIdentityResolver#tenantId()} 取权威租户，并：
 * <ul>
 *   <li>单个 workflow（events/state）——先用 {@code belongsToTenant} 前置校验，
 *       不属于本租户一律 404（而非 403，避免泄露该 workflowId 是否存在）；</li>
 *   <li>聚合查询（metrics/by-status）——改用带 tenantId 的重载。</li>
 * </ul>
 *
 * <p>事件表本身无 tenant 列，故事件查询借 workflow_state 的归属判定。
 */
@Path("/api/v1/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class WorkflowAuditResource {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    RequestIdentityResolver identity;

    /**
     * 校验 workflowId 属于当前租户，否则抛 404。
     *
     * <p>用 404 而非 403：403 会向调用方确认「该 workflowId 确实存在，只是不归你」，
     * 从而允许枚举探测；404 让「不存在」与「不属于你」不可区分。
     */
    private UUID requireOwnedWorkflow(String workflowId) {
        UUID id;
        try {
            id = UUID.fromString(workflowId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Workflow not found: " + workflowId);
        }
        if (!WorkflowStateEntity.belongsToTenant(id, identity.tenantId())) {
            throw new NotFoundException("Workflow not found: " + workflowId);
        }
        return id;
    }

    /**
     * 获取 workflow 的事件历史
     *
     * @param workflowId workflow 唯一标识符
     * @param fromSeq 起始序列号（可选，默认 0）
     * @return 事件列表
     */
    @GET
    @Path("/{workflowId}/events")
    public List<WorkflowEventDTO> getEvents(
            @PathParam("workflowId") String workflowId,
            @QueryParam("fromSeq") @DefaultValue("0") long fromSeq) {
        requireOwnedWorkflow(workflowId);
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, fromSeq);
        return events.stream()
                .map(this::toEventDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取 workflow 当前状态
     *
     * @param workflowId workflow 唯一标识符
     * @return workflow 状态
     */
    @GET
    @Path("/{workflowId}/state")
    public WorkflowStateDTO getState(@PathParam("workflowId") String workflowId) {
        requireOwnedWorkflow(workflowId);
        Optional<WorkflowState> stateOpt = eventStore.getState(workflowId);
        if (stateOpt.isEmpty()) {
            throw new NotFoundException("Workflow not found: " + workflowId);
        }

        WorkflowState state = stateOpt.get();
        return toStateDTO(state);
    }

    /**
     * 获取所有 workflow 的聚合指标
     *
     * @return 指标统计
     */
    @GET
    @Path("/metrics")
    public WorkflowMetricsDTO getMetrics() {
        String tenantId = identity.tenantId();
        return new WorkflowMetricsDTO(
                WorkflowStateEntity.countByStatus("READY", tenantId),
                WorkflowStateEntity.countByStatus("RUNNING", tenantId),
                WorkflowStateEntity.countByStatus("COMPLETED", tenantId),
                WorkflowStateEntity.countByStatus("FAILED", tenantId),
                WorkflowStateEntity.countByStatus("COMPENSATING", tenantId),
                WorkflowStateEntity.countByStatus("COMPENSATED", tenantId),
                WorkflowStateEntity.countByStatus("COMPENSATION_FAILED", tenantId)
        );
    }

    /**
     * 获取指定状态的 workflow 列表
     *
     * @param status 状态类型
     * @param limit 最大返回数量
     * @return workflow ID 列表
     */
    @GET
    @Path("/by-status/{status}")
    public List<String> getWorkflowsByStatus(
            @PathParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return WorkflowStateEntity.findByStatus(status, identity.tenantId()).stream()
                .limit(limit)
                .map(state -> state.workflowId.toString())
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法 ====================

    private WorkflowEventDTO toEventDTO(WorkflowEvent event) {
        return new WorkflowEventDTO(
                event.getSequence(),
                event.getWorkflowId(),
                event.getEventType(),
                event.getPayload(),
                event.getOccurredAt()
        );
    }

    private WorkflowStateDTO toStateDTO(WorkflowState state) {
        return new WorkflowStateDTO(
                state.getWorkflowId(),
                state.getStatus().name(),
                state.getLastEventSeq(),
                state.getResult(),
                state.getSnapshot(),
                state.getSnapshotSeq(),
                state.getCreatedAt(),
                state.getUpdatedAt()
        );
    }
}
