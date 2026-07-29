package io.aster.audit.service;

import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.inbox.InboxGuard;
import io.aster.audit.outbox.OutboxStatus;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.api.workflow.WorkflowStateEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * 异常工作流服务（Phase 3.7）
 *
 * 采用 orchestration 模式负责异常状态机编排：
 * - 仅负责状态迁移和事件发布
 * - 不实现具体业务逻辑（Replay/Rollback 由 AnomalyActionExecutor 调用现有服务）
 * - 通过 Event<AuditEvent> 集成到现有审计体系
 *
 * <p><b>#57 持久化模型约束</b>：本类底层是 <em>blocking</em> Hibernate ORM（AnomalyReportEntity
 * 为 orm.panache）。{@code submitVerificationAction}/{@code updateStatus}/{@code recordVerificationResult}
 * 标注 {@code @Transactional} 且返回 {@code Uni}——之所以正确，是因为其全部 {@code persist()}
 * 写入都<b>同步</b>执行在方法体内，返回的是已解析的 {@code Uni.createFrom().item(...)}，故 JTA 事务
 * 确实覆盖这些写入。⚠️ 铁律：这些方法内的 DB 写入必须保持同步，<b>不得</b>移入 {@code .onItem()}/
 * {@code .transformToUni} 延迟链——否则工作会逃出 {@code @Transactional} 作用域（JTA 事务在 Uni 对象
 * 生成即提交），造成无事务写入。若需异步化，须移除 {@code @Transactional} 并让各段自带独立事务
 * （参见 AnomalyActionExecutor.executeReplayVerification 与 WorkflowSchedulerService.replayWorkflow）。
 */
@ApplicationScoped
public class AnomalyWorkflowService {

    @Inject
    Event<AuditEvent> auditEvent;

    @Inject
    InboxGuard inboxGuard;

    /**
     * 提交验证动作到队列
     *
     * 状态迁移：PENDING → VERIFYING
     * 创建 anomaly_actions 记录（actionType=VERIFY_REPLAY, status=PENDING）
     *
     * @param anomalyId 异常报告 ID
     * @return 创建的动作 ID
     */
    @Transactional
    public Uni<Long> submitVerificationAction(Long anomalyId) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        // Phase 3.8: 检查 sampleWorkflowId 是否存在
        if (anomaly.sampleWorkflowId == null) {
            Log.warnf("异常报告 %d 缺少 sampleWorkflowId，跳过验证动作创建", anomalyId);
            return Uni.createFrom().nullItem();  // 跳过创建，返回 null
        }

        // Phase 3.8: 检查 workflow 的 clockTimes 是否存在
        WorkflowStateEntity workflow = WorkflowStateEntity.findByWorkflowId(anomaly.sampleWorkflowId).orElse(null);
        if (workflow == null || workflow.clockTimes == null || workflow.clockTimes.isBlank()) {
            Log.warnf("Workflow %s 缺少 clockTimes，跳过验证动作创建", anomaly.sampleWorkflowId);
            return Uni.createFrom().nullItem();  // 跳过创建，返回 null
        }

        // 更新异常状态为 VERIFYING
        anomaly.status = "VERIFYING";
        anomaly.persist();

        // Phase 3.8: 构建 payload（从 sampleWorkflowId 提取）
        String payload = Json.createObjectBuilder()
            .add("workflowId", anomaly.sampleWorkflowId.toString())
            .build()
            .toString();
        Log.infof("提交验证动作: anomalyId=%d, workflowId=%s", anomalyId, anomaly.sampleWorkflowId);

        // 创建验证动作
        AnomalyActionEntity action = new AnomalyActionEntity();
        action.anomalyId = anomalyId;
        action.actionType = "VERIFY_REPLAY";
        action.status = OutboxStatus.PENDING;
        action.payload = payload;  // Phase 3.8: 填充 payload
        action.tenantId = resolveOutboxTenant(anomalyId);
        action.createdAt = Instant.now();
        action.persist();

        // 发布审计事件
        auditEvent.fireAsync(AuditEvent.anomalyVerification(
            anomaly.policyId,
            anomalyId,
            "VERIFY_REPLAY"
        ));

        return Uni.createFrom().item(action.id);
    }

    private String resolveOutboxTenant(Long anomalyId) {
        if (anomalyId == null) {
            return null;
        }
        return "ANOMALY-" + anomalyId;
    }

    /**
     * 更新异常状态
     *
     * 支持的状态转换：
     * - VERIFYING → VERIFIED
     * - VERIFIED → RESOLVED
     * - VERIFIED → DISMISSED
     * - PENDING → DISMISSED
     *
     * @param anomalyId 异常报告 ID
     * @param newStatus 新状态
     * @param notes     处置备注
     * @return 成功标志
     */
    @Transactional
    public Uni<Boolean> updateStatus(Long anomalyId, String newStatus, String notes) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        String oldStatus = anomaly.status;

        // 更新状态
        anomaly.status = newStatus;
        if (notes != null && !notes.isBlank()) {
            anomaly.resolutionNotes = notes;
        }

        // 如果状态为 RESOLVED 或 DISMISSED，记录解决时间
        if ("RESOLVED".equals(newStatus) || "DISMISSED".equals(newStatus)) {
            anomaly.resolvedAt = Instant.now();
        }

        anomaly.persist();

        // 发布审计事件
        auditEvent.fireAsync(AuditEvent.anomalyStatusChange(
            anomaly.policyId,
            anomalyId,
            oldStatus,
            newStatus,
            notes
        ));

        return Uni.createFrom().item(true);
    }

    /**
     * 幂等记录验证结果（issue #119：方案 C）。
     *
     * <p>outbox 长事务拆分后，VERIFY_REPLAY handler 在无外层事务下运行；原「执行前抢 inbox 坑」的
     * 幂等模型不再安全（marker 早于结果独立提交 → 崩溃后 reclaim 重投递被误跳过 = 丢事件）。本方法把
     * inbox marker 的获取与 {@link #recordVerificationResult}（状态更新 + 可能创建的 AUTO_ROLLBACK
     * action）放在<b>同一事务</b>内原子提交：
     * <ul>
     *   <li>marker 未获取到（重投递命中已有 marker）→ 不再重复 record、不重复创建 AUTO_ROLLBACK；</li>
     *   <li>崩溃在本事务提交前 → marker 也未提交 → reclaim 重投递可正常重试，不丢事件。</li>
     * </ul>
     * 投递级并发去重由 outbox claim（PESSIMISTIC_WRITE + status 双检 + lease token）提供，故不需要
     * 「执行前」占坑。幂等键 {@code REPLAY_{anomalyId}_{actionId}} 与租户派生与旧路径保持一致。
     *
     * @param action             触发本次验证的 outbox 动作（提供 anomalyId + actionId + tenant）
     * @param verificationResult 验证结果对象
     * @return 成功标志；若本次为重复投递（marker 已存在）则跳过 record 后仍返回 true（幂等成功）
     */
    @Transactional
    public Uni<Boolean> recordVerificationResultOnce(AnomalyActionEntity action, VerificationResult verificationResult) {
        String idempotencyKey = String.format("REPLAY_%s_%s", action.anomalyId, action.id);
        // tryAcquireBlocking 自带 @Transactional(REQUIRED)：在本方法事务内自我调用会加入同一事务，
        // 与后续 recordVerificationResult 的写入原子提交。
        boolean acquired = inboxGuard.tryAcquireBlocking(idempotencyKey, "VERIFY_REPLAY", resolveReplayTenant(action));
        if (!acquired) {
            Log.infof("检测到重复 Replay 验证结果记录，跳过（幂等）：%s", idempotencyKey);
            return Uni.createFrom().item(true);
        }
        return recordVerificationResult(action.anomalyId, verificationResult);
    }

    /**
     * VERIFY_REPLAY 幂等键的租户派生，与 AnomalyActionExecutor 旧 resolveInboxTenant 一致：
     * 优先 action.tenantId，缺失则用异常 ID 派生 {@code ANOMALY-<id>} 命名空间。
     */
    private String resolveReplayTenant(AnomalyActionEntity action) {
        if (action == null) {
            return null;
        }
        if (action.tenantId != null && !action.tenantId.isBlank()) {
            return action.tenantId;
        }
        if (action.anomalyId == null) {
            return null;
        }
        return "ANOMALY-" + action.anomalyId;
    }

    /**
     * 记录验证结果
     *
     * 将 VerificationResult 序列化为 JSONB 并写入 verification_result 字段
     * Phase 3.8 Task 2: 如果异常可复现（anomalyReproduced=true），触发自动回滚
     *
     * @param anomalyId          异常报告 ID
     * @param verificationResult 验证结果对象
     * @return 成功标志
     */
    @Transactional
    public Uni<Boolean> recordVerificationResult(Long anomalyId, VerificationResult verificationResult) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        // 序列化验证结果为 JSON 字符串
        String jsonResult = verificationResult.toJson().toString();
        anomaly.verificationResult = jsonResult;

        // 状态迁移：VERIFYING → VERIFIED
        anomaly.status = "VERIFIED";
        anomaly.persist();

        // Phase 3.8 Task 2: 如果异常可复现，触发自动回滚
        if (verificationResult.anomalyReproduced()) {
            return submitAutoRollbackAction(anomaly)
                .onItem().transform(actionId -> true);
        }

        return Uni.createFrom().item(true);
    }

    /**
     * Phase 3.8 Task 2: 提交自动回滚动作到队列
     *
     * 查找目标版本 → 构建 payload → 创建 AUTO_ROLLBACK 动作 → 发布审计事件
     *
     * @param anomaly 异常报告实体
     * @return 创建的动作 ID，如果无历史版本则返回 null
     */
    private Uni<Long> submitAutoRollbackAction(AnomalyReportEntity anomaly) {
        try {
            // Step 1: 获取当前版本号
            PolicyVersion currentVersion = PolicyVersion.findById(anomaly.versionId);
            if (currentVersion == null) {
                Log.warnf("异常 %d 的版本不存在: versionId=%d", anomaly.id, anomaly.versionId);
                return Uni.createFrom().nullItem();
            }

            // Step 2: 查找目标版本（必须限定租户，见 findPreviousVersion 注释）
            Long targetVersion =
                findPreviousVersion(anomaly.policyId, currentVersion.version, anomaly.tenantId);
            if (targetVersion == null) {
                Log.warnf("异常 %d 无历史版本可回滚，跳过 AUTO_ROLLBACK", anomaly.id);
                return Uni.createFrom().nullItem();
            }

            // Step 3: 构建 payload
            String payload = Json.createObjectBuilder()
                .add("targetVersion", targetVersion)
                .build()
                .toString();

            // Step 4: 创建动作
            AnomalyActionEntity action = new AnomalyActionEntity();
            action.anomalyId = anomaly.id;
            action.actionType = "AUTO_ROLLBACK";
            action.status = OutboxStatus.PENDING;
            action.payload = payload;
            action.tenantId = resolveOutboxTenant(anomaly.id);
            action.createdAt = Instant.now();
            action.persist();

            // Step 5: 发布审计事件
            auditEvent.fireAsync(AuditEvent.anomalyAutoRollback(
                anomaly.policyId,
                anomaly.id,
                currentVersion.version,
                targetVersion
            ));

            Log.infof("异常 %d 创建 AUTO_ROLLBACK 动作：从版本 %d 回滚到 %d",
                anomaly.id, currentVersion.version, targetVersion);

            return Uni.createFrom().item(action.id);
        } catch (Exception e) {
            Log.errorf(e, "创建 AUTO_ROLLBACK 动作失败: anomalyId=%d", anomaly.id);
            return Uni.createFrom().nullItem();
        }
    }

    /**
     * Phase 3.8 Task 2: 查找上一个版本
     *
     * 查询指定策略的上一个版本（version < currentVersion）
     *
     * <p>★<b>必须限定租户</b>：{@code policyId} 是 {@code module.function} 形式，
     * <b>不含租户命名空间</b>——两个租户可以有同名模块与函数。用无租户范围的
     * {@code findAllVersions(policyId)} 会把别的租户的版本历史一并扫进来，于是
     * A 租户的 CRITICAL 异常可能选中 B 租户时间线上的版本号写进 AUTO_ROLLBACK
     * payload。后续 {@code performAutoRollback} 是租户范围解析的（它带 C1 IDOR
     * 修复注释），结果要么把 A 回滚到一个由 B 的历史决定的版本号，要么
     * {@code findByVersion} 返回 null 使应急回滚静默失败（表现为「版本漂移」）。
     *
     * <p>即：下游已加固，**上游的选择步**漏了——这是同一次修复没做完。
     *
     * @param policyId       策略 ID
     * @param currentVersion 当前版本号
     * @param tenantId       租户 ID；为 null 时直接放弃回滚（拒绝跨租户兜底）
     * @return 上一个版本号，如果不存在则返回 null
     */
    private Long findPreviousVersion(String policyId, Long currentVersion, String tenantId) {
        if (policyId == null || currentVersion == null) {
            return null;
        }
        if (tenantId == null || tenantId.isBlank()) {
            // 宁可不回滚，也不做跨租户扫描：拿不到租户就无法保证目标版本归属。
            Log.warnf("policyId=%s 缺少 tenantId，跳过自动回滚目标选择", policyId);
            return null;
        }

        // 查询该租户下的所有版本，按 version 降序（findAllVersions 已排序）
        List<PolicyVersion> versions = PolicyVersion.findAllVersions(policyId, tenantId);

        // 找到第一个小于 currentVersion 且【已审批】的版本（last known good）。
        // 自动回滚的目标必须是曾经审批通过的版本——回滚到一个未审批的历史草稿
        // （DRAFT/REJECTED）作为应急目标是危险的，且会被 activateVersion 的
        // status==APPROVED 闸门拒绝（见 PolicyVersionService.rollbackToVersion）。
        // 过滤 APPROVED 让自动回滚天然选中合法目标，而非选中后再被闸门拒掉。
        return versions.stream()
            .filter(v -> v.version < currentVersion)
            .filter(v -> v.status == io.aster.policy.entity.VersionStatus.APPROVED)
            .map(v -> v.version)
            .findFirst()
            .orElse(null);
    }
}
