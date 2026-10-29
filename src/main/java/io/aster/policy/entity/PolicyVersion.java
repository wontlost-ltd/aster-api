package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 策略版本实体
 *
 * 实现不可变部署：每次策略更新都创建新版本，旧版本标记为非活跃。
 * 使用 timestamp 作为版本号确保唯一性和排序。
 */
@RegisterForReflection
@Entity
@Table(name = "policy_versions", indexes = {
    @Index(name = "idx_policy_id_active", columnList = "policy_id,active"),
    @Index(name = "idx_policy_id_version", columnList = "policy_id,version")
})
public class PolicyVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 乐观锁版本号（审计 #98 Medium 2：双重激活竞态）。
     *
     * <p>{@code activateVersionInternal} 走 deactivate-then-activate，无行锁。手动
     * {@code rollback} 与 {@code AnomalyActionExecutor.executeAutoRollback} 并发时，
     * 两个事务都会读到并停用<b>同一</b>当前 active 行（{@link #deactivateAllVersions}
     * 把它 {@code active=false}），再各自激活不同目标 → 可能提交出两条 active 行，
     * {@code findActiveVersion(...).firstResult()} 返回任意一条 → 生产评估不确定。
     *
     * <p>{@code @Version} 让这两个事务对<b>共享的当前 active 行</b>的并发 UPDATE 互斥：
     * 后提交者命中 {@link jakarta.persistence.OptimisticLockException} 回滚，从而保证
     * 每个 {@code (policyId, tenantId)} 至多一条 active 行。activate/rollback 调用方
     * 需捕获该异常并转成可重试的冲突响应。
     *
     * <p>DB 列由 {@code V6.15.0__add_policy_version_lock.sql} 添加（DEFAULT 0，NOT NULL）。
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    public Long lockVersion;

    /**
     * 策略唯一标识符（业务ID，跨版本不变）
     */
    @Column(name = "policy_id", nullable = false, length = 100)
    public String policyId;

    /**
     * 版本号（使用 timestamp 确保唯一性和时间顺序）
     */
    @Column(name = "version", nullable = false)
    public Long version;

    /**
     * 策略模块名称（如 aster.finance.loan）
     */
    @Column(name = "module_name", nullable = false, length = 200)
    public String moduleName;

    /**
     * 策略函数名称（如 evaluateLoanEligibility）
     */
    @Column(name = "function_name", nullable = false, length = 200)
    public String functionName;

    /**
     * 策略内容（Aster CNL 代码或 JSON 配置）
     *
     * <p>审计 #98（Low）：{@code updatable=false} —— 版本一经创建即冻结，content 不可再改。
     * 与同族的 {@code aliasSet} / {@code sourceEnvelopeSha256} / {@code sourceToolchainId}
     * 对齐，使冻结成为<b>预防性</b>约束（JPA 层直接不发 UPDATE），而不仅是 envelope 对账的
     * <b>检测性</b>约束。任何试图改已存版本 content 的写入会被 Hibernate 静默忽略该列。
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT", updatable = false)
    public String content;

    /**
     * 是否为活跃版本（每个 policyId 只有一个活跃版本）
     */
    @Column(name = "active", nullable = false)
    public Boolean active;

    /**
     * 是否可作为 library 被其它策略经 {@code Use} 引用（ADR 0015 阶段3）。
     * 默认 false，需显式发布为可引用。ModuleResolver 仅解析 library_visible=true 的版本。
     */
    @Column(name = "library_visible", nullable = false)
    public Boolean libraryVisible = false;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * 创建者
     */
    @Column(name = "created_by", length = 100)
    public String createdBy;

    /**
     * 备注信息（版本变更说明）
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    public String notes;

    /**
     * 编译产物 SHA256 校验和（Phase 3.1）
     */
    @Column(name = "artifact_sha256", length = 64)
    public String artifactSha256;

    /**
     * 编译产物存储路径（Phase 3.1）
     */
    @Column(name = "artifact_uri", columnDefinition = "TEXT")
    public String artifactUri;

    /**
     * Runtime 构建版本（Phase 3.1）
     */
    @Column(name = "runtime_build", length = 50)
    public String runtimeBuild;

    /**
     * 版本激活时间（Phase 3.1）
     * 用于审计时间线分析
     */
    @Column(name = "activated_at")
    public Instant activatedAt;

    /**
     * 提交审批的操作者
     */
    @Column(name = "submitted_by", length = 100)
    public String submittedBy;

    /**
     * 提交审批的时间
     */
    @Column(name = "submitted_at")
    public Instant submittedAt;

    /**
     * 审批通过的操作者
     */
    @Column(name = "approved_by", length = 100)
    public String approvedBy;

    /**
     * 审批通过时间
     */
    @Column(name = "approved_at")
    public Instant approvedAt;

    /**
     * 激活操作人
     */
    @Column(name = "activated_by", length = 100)
    public String activatedBy;

    /**
     * 审批拒绝的操作者
     */
    @Column(name = "rejected_by", length = 100)
    public String rejectedBy;

    /**
     * 审批拒绝的时间
     */
    @Column(name = "rejected_at")
    public Instant rejectedAt;

    /**
     * 策略源码 SHA256 哈希（十六进制）
     * 用于内容完整性校验和链式信任
     */
    @Column(name = "source_hash", length = 64, nullable = false)
    public String sourceHash;

    /**
     * 前序版本的源码哈希
     * 用于构建链式信任，形成不可篡改的版本链
     */
    @Column(name = "prev_hash", length = 64)
    public String prevHash;

    /**
     * 该版本编译时冻结的用户自定义关键词别名集（ADR 0022 方案 D）。
     * JSON 文本：{@code {"TIMES":["multiplied by"], ...}}。NULL=无用户别名。
     * 不可变：版本一经创建即冻结；rollback 激活目标版本行=自动用其冻结别名（无需 copy）。
     */
    @Column(name = "alias_set", columnDefinition = "TEXT", updatable = false)
    public String aliasSet;

    /**
     * 完整编译输入的 SHA-256（ADR 0022 §11.5 C1，ADR 0051 §7）。
     * 覆盖 content + aliasSet + locale + 工具链身份 + 词汇作用域，防"源码哈希对得上、别名被替换"篡改。
     * 与只哈希 content 的 {@link #sourceHash} 互补：sourceHash 是版本身份/链，
     * sourceEnvelopeSha256 是完整编译输入的审计真相。NULL=本特性前创建的旧版本。
     */
    @Column(name = "source_envelope_sha256", length = 64, updatable = false)
    public String sourceEnvelopeSha256;

    /**
     * envelope 计算所用的工具链身份串（abi/core/validator/build）。
     * 供 tip-anchor verifier 用**创建时**的工具链重算 envelope 验证最新行（无后继断链），
     * 区分篡改与引擎升级。NULL=本特性前创建的旧版本。不可变。
     */
    @Column(name = "source_toolchain_id", length = 256, updatable = false)
    public String sourceToolchainId;

    /**
     * 版本状态
     * DRAFT/SUBMITTED/APPROVED/REJECTED/DEPRECATED/ARCHIVED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    public VersionStatus status = VersionStatus.DRAFT;

    /**
     * 标记当前租户默认版本
     * 每个租户的每个策略只能有一个默认版本
     */
    @Column(name = "is_default", nullable = false)
    public Boolean isDefault = false;

    /**
     * 变更说明 / Release Note
     * 记录本版本的主要变更内容
     */
    @Column(name = "release_note", columnDefinition = "TEXT")
    public String releaseNote;

    /**
     * 版本弃用时间戳
     */
    @Column(name = "deprecated_at")
    public Instant deprecatedAt;

    /**
     * 版本弃用操作人
     */
    @Column(name = "deprecated_by", length = 100)
    public String deprecatedBy;

    /**
     * 版本归档时间戳
     */
    @Column(name = "archived_at")
    public Instant archivedAt;

    /**
     * 版本归档操作人
     */
    @Column(name = "archived_by", length = 100)
    public String archivedBy;

    /**
     * 租户标识
     * 在多租户部署中隔离策略版本
     */
    @Column(name = "tenant_id", length = 100)
    public String tenantId;

    /**
     * 核心 IR JSON 内容
     * 用于动态加载策略执行结构
     */
    @Column(name = "core_json", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    public String coreJson;

    /**
     * 编译器版本
     * 记录生成该版本策略所使用的编译器
     */
    @Column(name = "compiler_version", length = 50)
    public String compilerVersion;

    /**
     * 版本来源类型
     * 用于北极星指标 WAADR：每周被业务专家采纳的 AI 草稿规则数
     * 取值：manual / ai_draft / ai_draft_edited / imported
     */
    @Column(name = "source_kind", length = 32, nullable = false)
    public String sourceKind = "manual";

    /**
     * 作者业务角色（v1.2）
     * 取值：business_expert / compliance_officer / risk_analyst / engineer / admin / unknown
     * WAADR 视图仅统计业务三角色（前三者），与 PM 02-NSM 精确定义对齐
     */
    @Column(name = "author_role", length = 64, nullable = false)
    public String authorRole = "unknown";

    /**
     * 策略语言环境
     * 区分不同区域或语言的策略版本
     */
    @Column(name = "locale", length = 20)
    public String locale;

    /**
     * 产出本行 envelope 与 Core IR 的名称解析版本（ADR 0051 §9）。
     * 1 = 按位置解析（新建行或存量重编译成功）；NULL = 迁移前的存量行（envelope 为旧 4 段格式）。
     */
    @Column(name = "resolver_version")
    public Integer resolverVersion;

    /** 最近一次存量重编译失败的原因；成功时清空。 */
    @Column(name = "resolver_migration_error", columnDefinition = "TEXT")
    public String resolverMigrationError;

    /** 是否由存量重编译任务归档（人工/业务归档恒为 false，只有本标记为 true 的行会在成功后恢复状态）。 */
    @Column(name = "archived_by_resolver_migration", nullable = false)
    public Boolean archivedByResolverMigration = false;

    /** 存量重编译任务归档前的状态，成功后据此恢复。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_archive", length = 32)
    public VersionStatus statusBeforeArchive;

    // 无参构造函数（JPA 要求）
    public PolicyVersion() {
    }

    /**
     * JPA 持久化前回调
     * 确保必填字段有默认值
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.version == null) {
            this.version = Instant.now().toEpochMilli();
        }
        if (this.status == null) {
            this.status = VersionStatus.DRAFT;
        }
        if (this.isDefault == null) {
            this.isDefault = false;
        }
        if (this.active == null) {
            this.active = true;
        }
        if (this.sourceKind == null) {
            this.sourceKind = "manual";
        }
        if (this.authorRole == null) {
            this.authorRole = "unknown";
        }
        // 确保 sourceHash 有值（非空约束）
        if (this.sourceHash == null && this.content != null) {
            this.sourceHash = computeSourceHash(this.content);
        } else if (this.sourceHash == null) {
            // 如果没有内容，使用空字符串的哈希
            this.sourceHash = computeSourceHash("");
        }
    }

    /**
     * 创建新版本
     *
     * @param policyId     策略ID
     * @param moduleName   模块名
     * @param functionName 函数名
     * @param content      策略内容
     * @param createdBy    创建者
     * @param notes        备注
     */
    public PolicyVersion(
        String policyId,
        String moduleName,
        String functionName,
        String content,
        String createdBy,
        String notes
    ) {
        this.policyId = policyId;
        this.version = Instant.now().toEpochMilli(); // 使用当前时间戳作为版本号
        this.moduleName = moduleName;
        this.functionName = functionName;
        this.content = content;
        this.active = true;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
        this.notes = notes;

        // 初始化安全字段
        this.sourceHash = computeSourceHash(content);
        this.prevHash = findPreviousVersionHash(policyId);
        this.status = VersionStatus.DRAFT;
        this.isDefault = false;
    }

    /**
     * 计算策略内容的 SHA-256 哈希
     */
    private static String computeSourceHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute source hash", e);
        }
    }

    /**
     * 计算完整编译输入的 SHA-256 信封哈希（ADR 0022 §11.5 C1，ADR 0051 §7 扩为 5 段）。
     *
     * <p>覆盖决定编译产物的全部输入，防"源码哈希对得上、别名或词汇被替换"篡改：
     * content + aliasSet（规范 JSON）+ locale + 工具链身份 + 词汇作用域（规范 JSON）。
     * 每段写 {@code <UTF-8 字节数>:<字节>|}，段序固定，杜绝字段间歧义；null 视为空串。
     * 算法与 cloud {@code computeSourceEnvelope} 逐字节一致，由共享向量 envelope-vectors.json 钉住。
     *
     * @param content      源码文本
     * @param aliasSetJson 版本冻结的 aliasSet JSON（已是规范形，这里不再二次解析）
     * @param locale       编译 locale
     * @param toolchainId  工具链身份串
     * @param scopesJson   {@link io.aster.policy.parser.CompileIdentity#scopesJson} 的结果；无词汇为空串
     */
    public static String computeSourceEnvelope(String content, String aliasSetJson,
                                               String locale, String toolchainId, String scopesJson) {
        return digestFields(content, aliasSetJson, locale, toolchainId, scopesJson);
    }

    /**
     * ADR 0051 之前的 4 段 envelope（无词汇段）。只用于校验尚未迁移、或迁移失败被归档而保留旧产物的存量行，
     * 新行一律用 {@link #computeSourceEnvelope}。
     */
    public static String computeLegacySourceEnvelope(String content, String aliasSetJson,
                                                     String locale, String toolchainId) {
        return digestFields(content, aliasSetJson, locale, toolchainId);
    }

    /** 每段写 {@code <UTF-8 字节数>:<字节>|} 后整体 SHA-256；null 视为空串。 */
    private static String digestFields(String... fields) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] b = (field == null ? "" : field).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(Integer.toString(b.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(b);
                digest.update((byte) '|');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute source envelope", e);
        }
    }

    /**
     * 本版本 envelope 的词汇段（ADR 0051 §7 第 5 段）。
     *
     * <p>api 侧版本不存词汇，按版本编译（{@code PolicyCompiler#compile(Long)}）也不传词汇，
     * 实际编译输入里没有词汇，故恒为空串。创建与校验都取这里，二者不会分叉；
     * 若将来 api 版本要冻结词汇，须同时加列并让编译与本方法读同一快照。
     */
    public String compileScopesJson() {
        return io.aster.policy.parser.CompileIdentity.scopesJson(null);
    }

    /**
     * 查找前序版本的链接哈希（{@link #chainLink()}）。
     *
     * <p>链接 = envelope（存在时）否则 sourceHash → 前序版本若带别名，其 envelope 进链，
     * 篡改 alias_set 会断链（ADR 0022 §11.5 C1：envelope 必须进哈希链，否则改 alias_set+
     * 同步改 source_envelope_sha256 链不变=篡改隐形）。旧版本无 envelope 时回落 sourceHash，
     * 向后兼容。
     */
    private static String findPreviousVersionHash(String policyId) {
        PolicyVersion prev = find("policyId = ?1 ORDER BY version DESC, id DESC", policyId).firstResult();
        return prev != null ? prev.chainLink() : null;
    }

    /**
     * 给策略的链尾版本加行锁（同一事务内随后构造新版本）。
     *
     * <p>新版本的 prevHash 取链尾的 chainLink；存量重编译（ADR 0051 §9）会在行锁内改写某行 envelope
     * 并把其直接后继的 prevHash 跟上。先锁链尾再读，二者对链尾串行：迁移先提交则读到新链接；
     * 新版本先提交则迁移随后把它当作后继改写。排序与迁移的后继判定一致（version, id）。
     */
    public static void lockChainTail(String policyId) {
        find("policyId = ?1 ORDER BY version DESC, id DESC", policyId)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    }

    /**
     * 版本链接哈希：进入下一版本 {@link #prevHash} 的值。
     *
     * <p>带 envelope 的版本用 envelope（覆盖 content+aliasSet+locale+工具链），否则用
     * content-only sourceHash。这让 alias_set 篡改对版本链可见——篡改者即使同步改
     * source_envelope_sha256，下一版本的 prevHash 已固化旧 envelope，对账即断链。
     */
    public String chainLink() {
        return (sourceEnvelopeSha256 != null && !sourceEnvelopeSha256.isBlank())
            ? sourceEnvelopeSha256 : sourceHash;
    }

    /**
     * 停用当前版本
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 查找指定策略的活跃版本
     *
     * @param policyId 策略ID
     * @return 活跃版本，如果不存在返回 null
     */
    public static PolicyVersion findActiveVersion(String policyId) {
        return find("policyId = ?1 and active = true", policyId).firstResult();
    }

    /**
     * 查找指定策略的所有版本（按版本号降序）
     *
     * @param policyId 策略ID
     * @return 版本列表
     */
    public static java.util.List<PolicyVersion> findAllVersions(String policyId) {
        return find("policyId = ?1 order by version desc", policyId).list();
    }

    /**
     * 查找指定策略的特定版本
     *
     * @param policyId 策略ID
     * @param version  版本号
     * @return 版本实体，如果不存在返回 null
     */
    public static PolicyVersion findByVersion(String policyId, Long version) {
        return find("policyId = ?1 and version = ?2", policyId, version).firstResult();
    }

    // ── 租户范围 finder（红队 P0-A：跨租户 IDOR 修复）───────────────────────────
    // policyId 是客户端提供且非租户命名空间，故按 policyId-only 查询会让 A 租户传 B
    // 租户的 policyId 越权读/回滚。以下重载强制 tenantId 交集，供所有面向外部请求的
    // 服务方法使用（内部 workflow/anomaly 路径继续用无 tenant 版，它们的 policyId 来自
    // 自身租户上下文）。

    public static PolicyVersion findActiveVersion(String policyId, String tenantId) {
        return find("policyId = ?1 and tenantId = ?2 and active = true", policyId, tenantId).firstResult();
    }

    public static java.util.List<PolicyVersion> findAllVersions(String policyId, String tenantId) {
        return find("policyId = ?1 and tenantId = ?2 order by version desc", policyId, tenantId).list();
    }

    public static PolicyVersion findByVersion(String policyId, Long version, String tenantId) {
        return find("policyId = ?1 and version = ?2 and tenantId = ?3", policyId, version, tenantId).firstResult();
    }

    /** 按主键 id 在租户范围查找（审计 IDOR 修复：校验 versionId 归属当前租户）。 */
    public static PolicyVersion findByIdInTenant(Long id, String tenantId) {
        return find("id = ?1 and tenantId = ?2", id, tenantId).firstResult();
    }

    /**
     * 查找可作为 library 被引用的模块版本（ADR 0015 阶段3 ModuleResolver）。
     *
     * <p>按 (tenant_id, module_name, version) 定位，且必须 library_visible=true。
     * tenant 隔离是安全铁律——不同 tenant 的同名模块互不可见。
     *
     * @param tenantId   引用方租户（与被引模块同租户才可见）
     * @param moduleName 被引模块名（如 risk.Scoring）
     * @param version    钉定版本号（Use ... as v2 → 2）
     * @return 匹配的 library 版本，不存在/不可见返回 null
     */
    public static PolicyVersion findLibraryVersion(String tenantId, String moduleName, Long version) {
        return find(
            "tenantId = ?1 and moduleName = ?2 and version = ?3 and libraryVisible = true",
            tenantId, moduleName, version
        ).firstResult();
    }

    /**
     * 列出某模块在 tenant 内全部 library-visible 版本号（用于「版本不存在」错误的候选提示）。
     */
    public static java.util.List<Long> findLibraryVersions(String tenantId, String moduleName) {
        return find(
            "tenantId = ?1 and moduleName = ?2 and libraryVisible = true order by version desc",
            tenantId, moduleName
        ).<PolicyVersion>list().stream().map(v -> v.version).toList();
    }

    /**
     * 列出某 tenant 内全部可作 library 被 {@code Use} 引用的模块版本（ADR 0015 阶段3d 模块目录）。
     *
     * <p>供编辑器拉取「当前租户可引用模块 + 版本」用于 Monaco 补全/hover。
     * tenant 隔离——只返回本租户的 library 模块，不泄露其它租户。
     */
    public static java.util.List<PolicyVersion> findLibraryCatalog(String tenantId) {
        return find(
            "tenantId = ?1 and libraryVisible = true order by moduleName asc, version desc",
            tenantId
        ).list();
    }

    /**
     * Tenant-scoped visibility probe used only to distinguish same-tenant hidden versions
     * from missing visible versions without falling back to global/cross-tenant lookup.
     */
    public static boolean existsLibraryVersionInTenant(String tenantId, String moduleName, Long version) {
        return count(
            "tenantId = ?1 and moduleName = ?2 and version = ?3",
            tenantId, moduleName, version
        ) > 0;
    }

    /**
     * 停用指定策略在**指定租户**内的所有活跃版本。
     *
     * <p>安全审计 C1：{@code policyId = moduleName + "." + functionName} 并非租户唯一
     * （两个租户可有同名 policyId），因此**必须**带 tenantId 过滤——否则租户 A 激活/回滚
     * 会把租户 B 同 policyId 的 active 版本一并停用，造成跨租户 DoS。激活路径统一走本方法。
     *
     * @param policyId 策略ID（module.function）
     * @param tenantId 租户ID（不可为 null——激活路径的版本必带 tenantId）
     * @return 停用的版本数量
     */
    public static long deactivateAllVersions(String policyId, String tenantId) {
        // null tenant 用 IS NULL 匹配（= ?2 对 null 永不命中）——保证 tenantId=null 的版本之间
        // 仍能互相停用，与 tenantless 语义一致；非 null 则严格按 (policyId, tenantId) 隔离。
        List<PolicyVersion> activeVersions = tenantId == null
            ? find("policyId = ?1 and tenantId is null and active = true", policyId).list()
            : find("policyId = ?1 and tenantId = ?2 and active = true", policyId, tenantId).list();
        activeVersions.forEach(v -> v.active = false);
        return activeVersions.size();
    }

    /**
     * 停用指定策略的所有活跃版本（**不分租户**）。
     *
     * @deprecated 跨租户不安全（policyId 非租户唯一）。激活/回滚路径请用
     *     {@link #deactivateAllVersions(String, String)}。本重载仅遗留 test-only
     *     {@code createVersion(String policyId,...)} 使用，其创建的版本 tenantId=null。
     * @param policyId 策略ID
     * @return 停用的版本数量
     */
    @Deprecated
    public static long deactivateAllVersions(String policyId) {
        // 使用 stream() 逐个停用，确保实体状态正确
        List<PolicyVersion> activeVersions = find("policyId = ?1 and active = true", policyId).list();
        activeVersions.forEach(v -> v.active = false);
        return activeVersions.size();
    }
}
