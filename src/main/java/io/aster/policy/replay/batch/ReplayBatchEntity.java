package io.aster.policy.replay.batch;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * What-If 批次账本（ADR 0034）。
 *
 * <p>只存**批次元数据与聚合结果**，<b>不存逐条 targetDecision</b>——
 * 后者会扩大 PII 面并带来复杂的失效语义，是 ADR 0033 §3.2 真正要避免的东西。
 *
 * <p>状态迁移一律走 {@link #transitionTo}，不要直接赋值 {@link #status}。
 */
@RegisterForReflection
@Entity
@Table(name = "replay_batch")
public class ReplayBatchEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    // ── 租户隔离：所有查询必须带 userId（ADR 0034 §4.3）──────────────────
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "policy_id", nullable = false)
    public String policyId;

    @Column(name = "base_version_id", nullable = false)
    public String baseVersionId;

    @Column(name = "target_version_id", nullable = false)
    public String targetVersionId;

    // ── 窗口口径（ADR 0034 §3.3）────────────────────────────────────────
    @Column(name = "window_kind", nullable = false, length = 32)
    public String windowKind;

    /** 呈现用文案，必须与数字同屏——用户要知道自己看的是哪个总体。 */
    @Column(name = "window_label", nullable = false, length = 128)
    public String windowLabel;

    /** 解析「当天」所用的租户时区；未配置为 UTC 并需在结果里标注。 */
    @Column(name = "window_timezone", nullable = false, length = 64)
    public String windowTimezone = "UTC";

    /** 窗口起点（含）。创建时固化的绝对时刻，不是相对表达。 */
    @Column(name = "window_from", nullable = false)
    public Instant windowFrom;

    /** 窗口终点（<b>不含</b>）。取当天 00:00——边界指向已封闭的过去。 */
    @Column(name = "window_to", nullable = false)
    public Instant windowTo;

    // ── 进度 ───────────────────────────────────────────────────────────
    @Column(name = "planned_count", nullable = false)
    public int plannedCount;

    @Column(name = "completed_count", nullable = false)
    public int completedCount = 0;

    @Column(name = "failed_count", nullable = false)
    public int failedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    public ReplayBatchStatus status = ReplayBatchStatus.PENDING;

    /** 失败原因分布（JSON）。拒答时回报——「失败了」不够，要说清是哪类失败。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_reasons", columnDefinition = "jsonb")
    public String failureReasons;

    /**
     * 聚合结果。<b>仅 COMPLETED 时非空</b>，DB 层亦有 CHECK 约束兜底。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_summary", columnDefinition = "jsonb")
    public String resultSummary;

    @Column(name = "toolchain_id", nullable = false, length = 512)
    public String toolchainId;

    /**
     * 租约到期时刻。<b>RUNNING 必须非空</b>（DB 层 CHECK 兜底）。
     *
     * <p>领取批次时写入，worker 定期续租。超期未续 = 持有者进程已死，
     * 允许其他副本回收重跑——否则批次永久停在 RUNNING，
     * 而且<b>持续占着租户的并发额度</b>（pro 档只有 1 个），该租户再也发不出批次。
     */
    @Column(name = "lease_expires_at")
    public java.time.Instant leaseExpiresAt;

    /**
     * 租约持有者标识。<b>RUNNING 必须非空</b>（DB 层 CHECK 兜底）。
     *
     * <p>★<b>这是防「双 worker 覆盖」的根本手段</b>（ADR 0034 §10.3）：
     * 终态写带 {@code AND leaseOwner = ?} 条件更新，被误回收的旧 worker
     * 即便还在跑，也<b>写不进去</b>。
     *
     * <p>为什么不能只靠把 lease 调长：调长只是让误判**更少发生**，
     * 不是让误判**无害**。owner token 让它无害。
     */
    @Column(name = "lease_owner", length = 64)
    public String leaseOwner;

    /**
     * 已尝试次数。回收不是无限的：反复崩溃说明是这个批次本身有问题
     * （例如某条输入必然让 worker 挂掉），无限重试只会循环占用额度。
     */
    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    /**
     * 并发槽位 {@code [0, quota)}。<b>活跃时非空、终态时为空</b>（DB 层 CHECK 兜底）。
     *
     * <p>{@code (tenant_id, concurrency_slot)} 上有部分唯一索引——这是<b>唯一</b>
     * 能真正堵住「先查数量再插入」那个 TOCTOU 的机制：并发请求抢同一槽时，
     * 输的那个直接被数据库拒绝，不靠应用层自觉。
     */
    @Column(name = "concurrency_slot")
    public Integer concurrencySlot;

    /**
     * 总体冻结完成时刻。<b>RUNNING 必须非空</b>（DB 层 CHECK 兜底）。
     *
     * <p>★没有这个标记，{@code plannedCount == 0} 是有歧义的：
     * 究竟是「这段时间没有执行」（正当业务结果），
     * 还是「冻结事务还没跑／崩了」（系统故障）？
     * 两者对用户的含义完全不同，不能都表现成 0。
     */
    @Column(name = "window_frozen_at")
    public java.time.Instant windowFrozenAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "finished_at")
    public Instant finishedAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    // ── 状态迁移 ───────────────────────────────────────────────────────

    /**
     * 迁移到 {@code next}，非法迁移抛 {@link IllegalStateException}。
     *
     * <p>顺带维护时间戳：进 RUNNING 记 {@code startedAt}，进终态记 {@code finishedAt}。
     */
    public void transitionTo(ReplayBatchStatus next) {
        status.requireTransitionTo(next);
        if (next == ReplayBatchStatus.RUNNING) {
            startedAt = Instant.now();
        } else if (next.isTerminal() && next != ReplayBatchStatus.EXPIRED) {
            finishedAt = Instant.now();
        }
        if (next.isTerminal()) {
            // ★终态**必须**释放并发槽与租约，否则历史批次会永久吃掉租户额度
            //   （pro 档只有 1 个，占住就等于该租户再也发不出批次）。
            //   放在 transitionTo 里而不是各个调用点：四条终态路径
            //   （完成/拒答/空窗口/防御性失败）漏掉任何一条都会造成额度泄漏，
            //   由**结构**保证比靠每处自觉可靠。DB 层另有 CHECK 兜底。
            concurrencySlot = null;
            leaseExpiresAt = null;
        }
        status = next;
    }

    /**
     * 标记全量成功并落聚合结果。
     *
     * <p>★这里是 ADR 0034 §1.1 在应用层的最后一道闸：
     * 只要有一条失败、或没跑满 planned，就<b>不允许</b>进 COMPLETED——
     * 抛异常而不是悄悄降级，因为「悄悄降级」正是上一版把部分成功
     * 当成全量的那条路径。DB 层的 CHECK 是兜底，不是唯一防线。
     */
    public void markCompleted(String summaryJson) {
        if (failedCount != 0) {
            throw new IllegalStateException(
                "批次有 " + failedCount + " 条失败，不得标记为 COMPLETED（ADR 0034 §1.1）");
        }
        if (completedCount != plannedCount) {
            throw new IllegalStateException(
                "批次仅完成 " + completedCount + "/" + plannedCount
                    + " 条，不得标记为 COMPLETED（ADR 0034 §1.1）");
        }
        if (summaryJson == null || summaryJson.isBlank()) {
            throw new IllegalStateException("COMPLETED 批次必须携带聚合结果");
        }
        transitionTo(ReplayBatchStatus.COMPLETED);
        resultSummary = summaryJson;
    }

    /**
     * 标记失败并记录原因分布。
     *
     * <p>★强制清空 {@link #resultSummary}：拒答的批次不得残留任何数字，
     * 否则读路径可能把它当结论呈现。
     */
    public void markFailed(String failureReasonsJson) {
        transitionTo(ReplayBatchStatus.FAILED);
        failureReasons = failureReasonsJson;
        resultSummary = null;
    }

    /**
     * 过期：保留元数据，<b>清空聚合结果</b>（ADR 0034 §7.3）。
     *
     * <p>数字有时效性——策略、词汇、工具链都会变。留着陈旧数字比删掉更危险。
     */
    public void markExpired() {
        transitionTo(ReplayBatchStatus.EXPIRED);
        resultSummary = null;
    }

    /** 是否已跑完全部计划条目（无论成败）。 */
    public boolean isAllProcessed() {
        return completedCount + failedCount >= plannedCount;
    }
}
