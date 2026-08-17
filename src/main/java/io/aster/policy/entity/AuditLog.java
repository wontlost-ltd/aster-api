package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 审计日志实体 - 持久化审计记录到数据库
 *
 * 用于合规审计和事后调查，记录所有关键策略操作：
 * - 策略评估
 * - 策略创建
 * - 策略回滚
 *
 * 所有 PII 在写入前已被脱敏。
 */
@RegisterForReflection
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_tenant", columnList = "tenant_id"),
    @Index(name = "idx_audit_event_type", columnList = "event_type"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_policy", columnList = "policy_module, policy_function")
})
public class AuditLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 事件类型：POLICY_EVALUATION, POLICY_CREATED, POLICY_ROLLBACK
     */
    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;

    /**
     * 事件发生时间
     */
    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;

    /**
     * 租户 ID（多租户隔离）
     */
    @Column(name = "tenant_id", nullable = false, length = 100)
    public String tenantId;

    /**
     * 执行者 ID（从 X-User-Id 头部提取）
     */
    @Column(name = "performed_by", length = 100)
    public String performedBy;

    /**
     * 策略模块名称
     */
    @Column(name = "policy_module", length = 200)
    public String policyModule;

    /**
     * 策略函数名称
     */
    @Column(name = "policy_function", length = 200)
    public String policyFunction;

    /**
     * 策略 ID（仅用于 POLICY_CREATED 和 POLICY_ROLLBACK）
     */
    @Column(name = "policy_id", length = 100)
    public String policyId;

    /**
     * 原版本号（仅用于 POLICY_ROLLBACK）
     */
    @Column(name = "from_version")
    public Long fromVersion;

    /**
     * 目标版本号（仅用于 POLICY_CREATED 和 POLICY_ROLLBACK）
     */
    @Column(name = "to_version")
    public Long toVersion;

    /**
     * 执行时间（毫秒，仅用于 POLICY_EVALUATION）
     */
    @Column(name = "execution_time_ms")
    public Long executionTimeMs;

    /**
     * 是否成功（仅用于 POLICY_EVALUATION）
     */
    @Column(name = "success")
    public Boolean success;

    /**
     * 回滚原因（仅用于 POLICY_ROLLBACK）
     */
    @Column(name = "reason", length = 500)
    public String reason;

    /**
     * 错误信息（策略评估失败时记录）
     */
    @Column(name = "error_message", length = 1000)
    public String errorMessage;

    /**
     * 额外注释（可选）
     */
    @Column(name = "notes", length = 1000)
    public String notes;

    /**
     * 扩展元数据（JSON 字符串）
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;

    /**
     * 客户端 IP 地址（已脱敏）
     */
    @Column(name = "client_ip", length = 50)
    public String clientIp;

    /**
     * User-Agent（可选）
     */
    @Column(name = "user_agent", length = 500)
    public String userAgent;

    /**
     * 前一条审计记录的哈希值（SHA256 hex，用于构建防篡改链）
     * NULL 表示该租户的第一条记录（genesis block）
     */
    @Column(name = "prev_hash", length = 64)
    public String prevHash;

    /**
     * 当前记录的哈希值（SHA256 hex）
     *
     * <p>计算公式见 {@code io.aster.audit.chain.AuditHashPayload}——写侧与验侧
     * <b>共用</b>该类，公式只存在一处（此前两侧各有一份拷贝且无一致性测试）。
     */
    @Column(name = "current_hash", length = 64)
    public String currentHash;

    /**
     * 哈希公式版本。
     *
     * <p>{@code 1} = 历史公式，仅覆盖 6 个字段（event_type / timestamp / tenant_id /
     * policy_module / policy_function / success）。{@code 2} = 全业务字段 canonical 编码。
     *
     * <p>★存在的理由：历史行是用 V1 公式算出来的，换公式会让它们<b>全部</b>验证失败；
     * 而重算历史 hash 等于改写审计记录本身，会销毁「链从未被动过」这一属性。
     * 因此按行记录版本，验证器据此选择公式：旧行仍可验证，新行获得完整保护。
     */
    @Column(name = "hash_version", nullable = false)
    public Short hashVersion = io.aster.audit.chain.AuditHashPayload.CURRENT_VERSION;

    // Constructors
    public AuditLog() {
    }

    /** 分页结果上限，防止单次查询返回过多记录 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 安全分页参数：page ≥ 0，size ∈ [1, MAX_PAGE_SIZE] */
    private static io.quarkus.panache.common.Page safePage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return io.quarkus.panache.common.Page.of(safePage, safeSize);
    }

    /**
     * 查询指定租户的审计日志（默认第一页，最大条数）
     */
    public static java.util.List<AuditLog> findByTenant(String tenantId) {
        return findByTenant(tenantId, 0, MAX_PAGE_SIZE);
    }

    /**
     * 查询指定租户的审计日志（分页，按时间+ID稳定排序）
     */
    public static java.util.List<AuditLog> findByTenant(String tenantId, int page, int size) {
        return find("tenantId = ?1 order by timestamp desc, id desc", tenantId)
            .page(safePage(page, size))
            .list();
    }

    /**
     * 查询指定事件类型的审计日志（默认第一页，最大条数）
     */
    public static java.util.List<AuditLog> findByEventType(String eventType, String tenantId) {
        return findByEventType(eventType, tenantId, 0, MAX_PAGE_SIZE);
    }

    /**
     * 查询指定事件类型的审计日志（分页）
     */
    public static java.util.List<AuditLog> findByEventType(String eventType, String tenantId, int page, int size) {
        return find("eventType = ?1 and tenantId = ?2 order by timestamp desc, id desc", eventType, tenantId)
            .page(safePage(page, size))
            .list();
    }

    /**
     * 查询指定策略的审计日志（分页）
     */
    public static java.util.List<AuditLog> findByPolicy(String policyModule, String policyFunction, String tenantId, int page, int size) {
        return find("policyModule = ?1 and policyFunction = ?2 and tenantId = ?3 order by timestamp desc, id desc",
            policyModule, policyFunction, tenantId)
            .page(safePage(page, size))
            .list();
    }

    /**
     * 查询指定时间范围的审计日志（分页）
     */
    public static java.util.List<AuditLog> findByTimeRange(Instant startTime, Instant endTime, String tenantId, int page, int size) {
        return find("timestamp >= ?1 and timestamp <= ?2 and tenantId = ?3 order by timestamp desc, id desc",
            startTime, endTime, tenantId)
            .page(safePage(page, size))
            .list();
    }

    /**
     * 查询指定租户的最新哈希值（用于构建哈希链）
     * 返回 NULL 表示该租户的第一条记录（genesis block）
     *
     * <p>按 {@code id desc}（BIGSERIAL，严格单调=真实追加顺序）取链尾，而非 timestamp。
     * 并发追加下事件的 wall-clock timestamp 与实际持久化（id）顺序可能不一致——若按
     * timestamp 取「最新」会选到非链尾节点，导致 prev_hash 指向非末端 → 链分叉（issue #115）。
     * 以 id 定义链顺序还能抵御时钟回拨/乱序。须与 {@code AuditEventListener} 的 per-tenant
     * advisory lock 配合：advisory lock 串行化写、id-desc 取真链尾，二者缺一都会 fork。
     */
    public static String findLatestHash(String tenantId) {
        AuditLog log = find("tenantId = ?1 and currentHash is not null order by id desc", tenantId)
            .firstResult();
        return log != null ? log.currentHash : null;
    }

    /**
     * 查该租户内、id 小于 beforeId、current_hash == 给定值的前驱记录（issue #118）。
     * 用于时间窗验证时定位窗外前驱：窗口首条（非 genesis）的 prev_hash 指向前驱，本方法确认
     * 该前驱真实存在【且在链顺序上更早】（id 更小），以区分「合法链的时间窗切片」（前驱存在→
     * 用其 hash 作初始 expectedPrevHash）与「前驱被删/伪造」（前驱不存在→真断链）。
     * <p>加 {@code id < beforeId} 约束避免脏数据下 current_hash 重复时 seed 到错误方向
     * （Codex #118 审查）；按 id 降序取最近的合法前驱。current_hash 有索引（idx_audit_logs_current_hash），
     * 叠加 tenant + id 范围过滤，admin 验证路径的额外一次查询开销可接受。
     */
    public static AuditLog findPredecessorByCurrentHash(String tenantId, String currentHash, Long beforeId) {
        return find("tenantId = ?1 and currentHash = ?2 and id < ?3 order by id desc",
                tenantId, currentHash, beforeId)
            .firstResult();
    }
}
