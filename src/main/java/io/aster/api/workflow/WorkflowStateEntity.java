package io.aster.api.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Workflow 状态实体 - 使用 Panache Active Record 模式
 *
 * 持久化 workflow 当前执行状态，支持快速查询和调度。
 */
@RegisterForReflection
@Entity
@Table(name = "workflow_state")
public class WorkflowStateEntity extends PanacheEntityBase {

    private static final ObjectMapper SNAPSHOT_MAPPER = JacksonMappers.DEFAULT;

    @Id
    @Column(name = "workflow_id")
    public UUID workflowId;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "last_event_seq", nullable = false)
    public Long lastEventSeq = 0L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String snapshot;

    @Column(name = "snapshot_seq")
    public Long snapshotSeq;

    /**
     * 最后一次快照的时间戳（P0-2 criterion 4）
     * 用于基于时间间隔的快照触发
     */
    @Column(name = "last_snapshot_at")
    public Instant lastSnapshotAt;

    /**
     * 调度计数器（Phase 6.6）
     * 记录该 workflow 被调度器处理的次数，用于检测死循环调度
     */
    @Column(name = "schedule_count", nullable = false)
    public Integer scheduleCount = 0;

    @Column(name = "lock_owner", length = 64)
    public String lockOwner;

    @Column(name = "lock_expires_at")
    public Instant lockExpiresAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * 租户 ID（Phase 3.2）
     * 支持多租户场景的 workflow 过滤
     */
    @Column(name = "tenant_id", length = 64)
    public String tenantId;

    /**
     * 策略版本 ID（Phase 3.1）
     * NULL 表示版本化功能上线前的 workflow
     */
    @Column(name = "policy_version_id")
    public Long policyVersionId;

    /**
     * 策略版本激活时间（Phase 3.1）
     * 用于审计和版本使用时间线分析
     */
    @Column(name = "policy_activated_at")
    public Instant policyActivatedAt;

    /**
     * Workflow 开始时间（Phase 3.3）
     * 用于性能统计和执行时长计算
     */
    @Column(name = "started_at")
    public Instant startedAt;

    /**
     * Workflow 完成时间（Phase 3.3）
     * 用于性能统计和时间线分析
     */
    @Column(name = "completed_at")
    public Instant completedAt;

    /**
     * 执行时长（毫秒）（Phase 3.3）
     * 冗余字段，通过 completed_at - started_at 计算得出
     * 用于快速查询和统计，避免实时计算
     */
    @Column(name = "duration_ms")
    public Long durationMs;

    /**
     * 错误信息（Phase 3.3）
     * 仅在 status=FAILED 时有值，用于失败模式分析
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    /**
     * 确定性决策快照（Phase 0 Task 1.3）
     * JSONB 结构：{ "clockTimes": [...], "uuids": [...], "randoms": {"source": [...] } }
     * 兼容旧结构 recordedTimes/replayIndex/replayMode/version
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "clock_times", columnDefinition = "jsonb")
    public String clockTimes;

    /**
     * 读取 snapshot 中的 retry_context 字段，缺省返回空 Map 避免 NPE
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRetryContext() {
        Map<String, Object> snapshotMap = readSnapshotAsMap();
        Object retry = snapshotMap.get("retry_context");
        if (retry instanceof Map<?, ?> retryMap) {
            return Collections.unmodifiableMap(new HashMap<>((Map<String, Object>) retryMap));
        }
        return Collections.emptyMap();
    }

    /**
     * 更新 snapshot 中的 retry_context 字段，保持其他字段不变
     */
    public void setRetryContext(Map<String, Object> retryContext) {
        Map<String, Object> snapshotMap = readSnapshotAsMap();
        if (retryContext == null || retryContext.isEmpty()) {
            snapshotMap.remove("retry_context");
        } else {
            snapshotMap.put("retry_context", new HashMap<>(retryContext));
        }
        this.snapshot = writeSnapshot(snapshotMap);
    }

    /**
     * 根据 workflow ID 查询状态
     *
     * @param workflowId workflow 唯一标识符
     * @return 状态实例，如果不存在则返回 empty
     */
    public static Optional<WorkflowStateEntity> findByWorkflowId(UUID workflowId) {
        return findByIdOptional(workflowId);
    }

    /**
     * 查询指定状态的 workflow 列表
     *
     * @param status 状态类型（READY, RUNNING, etc.）
     * @return workflow 状态列表
     */
    public static List<WorkflowStateEntity> findByStatus(String status) {
        return find("status", status).list();
    }

    /**
     * 查询指定状态的 workflow 列表（**租户范围**）。
     *
     * <p>对外查询一律用本重载。无租户参数的 {@link #findByStatus(String)} 只允许
     * 调度器等系统内部路径使用——它跨租户返回，暴露给 REST 即为跨租户泄露。
     *
     * @param status   状态类型
     * @param tenantId 租户 ID，不可为 null
     * @return 该租户下的 workflow 状态列表
     */
    public static List<WorkflowStateEntity> findByStatus(String status, String tenantId) {
        return find("status = ?1 AND tenantId = ?2", status, tenantId).list();
    }

    /**
     * 查询就绪可调度的 workflow（支持 SELECT FOR UPDATE SKIP LOCKED）
     *
     * @param limit 最大返回数量
     * @return 就绪的 workflow 状态列表
     */
    public static List<WorkflowStateEntity> findReadyForScheduling(int limit) {
        return find("status IN ('READY', 'RUNNING') AND (lockExpiresAt IS NULL OR lockExpiresAt < ?1)",
                Instant.now())
                .page(0, limit)
                .list();
    }

    /**
     * 统计指定状态的 workflow 数量
     *
     * @param status 状态类型
     * @return workflow 数量
     */
    public static long countByStatus(String status) {
        return count("status", status);
    }

    /**
     * 统计指定状态的 workflow 数量（**租户范围**）。
     *
     * @param status   状态类型
     * @param tenantId 租户 ID，不可为 null
     * @return 该租户下的数量
     */
    public static long countByStatus(String status, String tenantId) {
        return count("status = ?1 AND tenantId = ?2", status, tenantId);
    }

    /**
     * 判断某 workflow 是否属于给定租户。
     *
     * <p>事件表（workflow_events）没有 tenant 列，事件查询只能借 workflow_state
     * 的归属来判定，故单列此方法供 REST 层做前置校验。
     *
     * @return 属于该租户返回 true；workflow 不存在或属于他人返回 false
     */
    public static boolean belongsToTenant(UUID workflowId, String tenantId) {
        return count("workflowId = ?1 AND tenantId = ?2", workflowId, tenantId) > 0;
    }

    /**
     * 获取或创建 workflow 状态
     *
     * Phase 4.3: 添加租户ID参数，确保多租户数据隔离
     *
     * @param workflowId workflow 唯一标识符
     * @param tenantId   租户ID（可为null，仅在创建新workflow时使用，更新时忽略）
     * @return workflow 状态实例
     */
    public static WorkflowStateEntity getOrCreate(UUID workflowId, String tenantId) {
        Optional<WorkflowStateEntity> existing = findByIdOptional(workflowId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var state = new WorkflowStateEntity();
        state.workflowId = workflowId;
        state.tenantId = tenantId;  // Phase 4.3: 设置租户ID（null表示未知租户，需要后续设置）
        state.status = "READY";
        state.lastEventSeq = 0L;
        state.createdAt = Instant.now();
        state.updatedAt = Instant.now();
        state.persist();
        return state;
    }

    /**
     * 更新时间戳
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * 计算并更新执行时长（Phase 3.3）
     *
     * 应在 workflow 完成时调用，确保 duration_ms 与时间戳一致
     */
    public void updateDuration() {
        if (this.startedAt != null && this.completedAt != null) {
            this.durationMs = Duration.between(this.startedAt, this.completedAt).toMillis();
        }
    }

    /**
     * 标记 workflow 开始（Phase 3.3）
     */
    public void markStarted() {
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    /**
     * 标记 workflow 完成（Phase 3.3）
     *
     * @param finalStatus 最终状态（COMPLETED, FAILED, etc.）
     */
    public void markCompleted(String finalStatus) {
        this.completedAt = Instant.now();
        this.status = finalStatus;
        updateDuration();
    }

    private Map<String, Object> readSnapshotAsMap() {
        if (this.snapshot == null || this.snapshot.isBlank()) {
            return new HashMap<>();
        }
        try {
            return SNAPSHOT_MAPPER.readValue(this.snapshot, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 解析失败时返回空 Map，避免影响调用方
            return new HashMap<>();
        }
    }

    private String writeSnapshot(Map<String, Object> snapshotMap) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(snapshotMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize snapshot with retry_context", e);
        }
    }
}
