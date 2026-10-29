package io.aster.policy.service;

import io.aster.billing.PlanGateService;
import io.aster.billing.PlanLimitException;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 策略版本服务
 *
 * 提供策略版本管理功能：
 * - 创建新版本（自动停用旧版本）
 * - 查询版本历史
 * - 回滚到指定版本
 */
@ApplicationScoped
public class PolicyVersionService {

    private static final Logger LOG = Logger.getLogger(PolicyVersionService.class);

    @Inject
    EntityManager entityManager;

    @Inject
    NsmTelemetry nsmTelemetry;

    @Inject
    PlanGateService planGate;

    @Inject
    io.aster.policy.stability.StabilityEnforcement stabilityEnforcement;

    /**
     * 工具链身份的**唯一**构造源（ADR 0030）：source envelope 侧（本服务）与 runtime evaluate 侧
     * （PolicyEvaluationResource）共用同一 {@code abi=;core=;validator=;build=} 口径，防两处字符串漂移
     * 导致「同一构建的 source/runtime 身份串不一致」＝回放地基裂缝。原本服务内私有复制已删。
     */
    @Inject
    io.aster.policy.stability.ToolchainIdentityProvider toolchainIdentityProvider;

    @ConfigProperty(name = "aster.policy.dual-write.enabled", defaultValue = "false")
    boolean dualWriteEnabled;

    @ConfigProperty(name = "aster.policy.dual-write.base-path", defaultValue = "target/policies")
    String dualWriteBasePath;

    /**
     * 创建策略版本。
     *
     * 步骤：
     * 1. 校验 catalog 并写入策略版本记录
     * 2. 策略源码存入数据库，运行时动态解析执行
     *
     * @param catalogId 策略目录 ID
     * @param sourceCnl 策略 CNL 源码
     * @param locale    策略语言环境
     * @return 新建的策略版本
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale) {
        return createVersion(catalogId, sourceCnl, locale, "manual", "unknown");
    }

    /**
     * 创建策略版本（带 sourceKind 标记，向后兼容）
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale, String sourceKind) {
        return createVersion(catalogId, sourceCnl, locale, sourceKind, "unknown");
    }

    /**
     * 创建策略版本（v1.2 带 sourceKind + authorRole）
     *
     * sourceKind: manual / ai_draft / ai_draft_edited / imported
     * authorRole: business_expert / compliance_officer / risk_analyst / engineer / admin / unknown
     *   仅前三个业务角色才计入 WAADR 北极星指标
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale, String sourceKind, String authorRole) {
        return createVersion(catalogId, sourceCnl, locale, sourceKind, authorRole, null);
    }

    /**
     * 创建版本并冻结用户自定义别名集（ADR 0022 方案 D）——**结构化入口（推荐）**。
     *
     * <p>aliasSet 经 {@link io.aster.policy.parser.UserAliasValidator#validate} 强制校验
     * （白名单/多词/不遮蔽/不撞领域词汇），再用 {@code canonicalJson} 确定性序列化后冻结。
     * 这保证同一别名集总产出同一 source envelope（可复现/跨租户一致）。
     *
     * @param aliasSet        结构化别名集，null/空=无用户别名
     * @param scopes          领域词汇作用域，只用于别名↔标识符碰撞校验，可为 null；不进 envelope——
     *                        版本不冻结词汇、按版本编译也不用词汇（见 {@link PolicyVersion#compileScopesJson()}）
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale,
                                       String sourceKind, String authorRole,
                                       java.util.Map<aster.core.lexicon.SemanticTokenKind, java.util.List<String>> aliasSet,
                                       aster.core.identifier.VocabularyScopes scopes) {
        io.aster.policy.parser.UserAliasValidator.Result vr =
            io.aster.policy.parser.UserAliasValidator.validate(aliasSet, locale, scopes);
        if (!vr.valid()) {
            throw new IllegalArgumentException("用户自定义别名校验失败: " + String.join("; ", vr.errors()));
        }
        String canonical = io.aster.policy.parser.UserAliasValidator.canonicalJson(aliasSet);
        return createVersion(catalogId, sourceCnl, locale, sourceKind, authorRole, canonical);
    }

    /**
     * 创建版本并冻结用户自定义别名集（ADR 0022 方案 D）——String 入口。
     *
     * <p>aliasSetJson 必须**已是规范形**（{@code canonicalJson} 输出）。本方法对其做规范性
     * 断言（重新规范化后须逐字节相同），不匹配则拒绝——杜绝非确定性/未校验 JSON 进入快照
     * （Codex 持久化复核 High：service 必须保证 canonical，不能接任意 raw string）。版本持久化时
     * 冻结 alias_set + source_envelope_sha256（覆盖完整编译输入），可审计/可复现/防替换篡改。
     *
     * @param aliasSetJson 已规范化的别名 JSON，null/空=无用户别名
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale,
                                       String sourceKind, String authorRole, String aliasSetJson) {
        PolicyCatalog catalog = PolicyCatalog.findById(catalogId);
        if (catalog == null) {
            throw new IllegalArgumentException("策略目录不存在: catalogId=" + catalogId);
        }

        // 规范性断言：传入 JSON 必须等于其重新规范化结果（防非确定性/未校验 JSON 进快照）。
        String normalizedAliasJson = canonicalizeAliasJson(aliasSetJson, locale);

        String policyId = catalog.moduleName + "." + catalog.functionName;
        PolicyVersion.lockChainTail(policyId);
        PolicyVersion version = new PolicyVersion(
            policyId,
            catalog.moduleName,
            catalog.functionName,
            sourceCnl,
            "system",
            "Dynamic policy version"
        );
        version.tenantId = catalog.tenantId;
        version.locale = locale;
        version.sourceKind = normalizeSourceKind(sourceKind);
        version.authorRole = normalizeAuthorRole(authorRole);
        version.active = false;
        // 方案 D：冻结规范化别名集 + 计算覆盖完整编译输入的信封哈希 + 记录工具链身份。
        version.aliasSet = normalizedAliasJson;
        String toolchain = toolchainIdentityProvider.currentToolchainId();
        version.sourceToolchainId = toolchain;
        version.sourceEnvelopeSha256 = PolicyVersion.computeSourceEnvelope(
            sourceCnl, version.aliasSet, locale, toolchain, version.compileScopesJson());
        // 新建行直接按位置解析规则产出（5 段 envelope），存量重编译任务无需再处理（ADR 0051 §9）。
        version.resolverVersion = io.aster.policy.parser.CompileIdentity.RESOLVER_VERSION;

        // P0-C 稳定性门禁（ADR 0031 M3）：保存 surface——regulated tenant strict（命中白名单才可存
        // Experimental），普通 tenant warn（不阻断）。tenantId 来自 catalog 归属（非 body 自报）。
        stabilityEnforcement.enforceVersion(null, policyId, catalog.tenantId,
            null, sourceCnl, locale, normalizedAliasJson,
            io.aster.policy.stability.StabilityEnforcement.Surface.SAVE, version.createdBy);

        version.persist();

        // 双写：同时写入静态文件作为兜底
        if (dualWriteEnabled) {
            writeStaticPolicyFile(catalog.moduleName, catalog.functionName, sourceCnl);
        }

        return version;
    }

    /**
     * 校验传入 aliasSetJson 已是规范形并返回之（null/空→null）。
     *
     * <p>解析 JSON→结构化→{@code canonicalJson} 重新序列化，与传入值逐字节比对：不等则拒绝。
     * 保证存进快照的 alias_set 永远是确定性规范形（Codex 持久化复核 High）。
     */
    private static String canonicalizeAliasJson(String aliasSetJson, String locale) {
        if (aliasSetJson == null || aliasSetJson.isBlank()) {
            return null;
        }
        java.util.Map<aster.core.lexicon.SemanticTokenKind, java.util.List<String>> parsed;
        try {
            java.util.Map<String, java.util.List<String>> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(aliasSetJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, java.util.List<String>>>() {});
            parsed = new java.util.EnumMap<>(aster.core.lexicon.SemanticTokenKind.class);
            for (var e : raw.entrySet()) {
                parsed.put(aster.core.lexicon.SemanticTokenKind.valueOf(e.getKey()), e.getValue());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("aliasSet JSON 非法或含未知 kind: " + e.getMessage());
        }
        // 语义校验（Codex 三轮复核：String 入口也必须校验，不能只验 canonical 形）。
        // 白名单/多词/不遮蔽——撞领域词汇的碰撞校验需词汇作用域，请用结构化入口
        // createVersion(...Map, VocabularyScopes)；此处至少挡敏感 kind/单词/遮蔽。
        io.aster.policy.parser.UserAliasValidator.Result vr =
            io.aster.policy.parser.UserAliasValidator.validate(parsed, locale, null);
        if (!vr.valid()) {
            throw new IllegalArgumentException("用户自定义别名校验失败: " + String.join("; ", vr.errors()));
        }
        String canonical = io.aster.policy.parser.UserAliasValidator.canonicalJson(parsed);
        if (!aliasSetJson.equals(canonical)) {
            throw new IllegalArgumentException(
                "aliasSet JSON 非规范形，请用 UserAliasValidator.canonicalJson 序列化后提交");
        }
        return canonical;
    }


    /**
     * 规范化 sourceKind，未识别值回落到 manual。
     */
    private static String normalizeSourceKind(String sourceKind) {
        if (sourceKind == null) {
            return "manual";
        }
        String s = sourceKind.trim().toLowerCase();
        return switch (s) {
            case "manual", "ai_draft", "ai_draft_edited", "imported" -> s;
            default -> "manual";
        };
    }

    /**
     * 规范化 authorRole（v1.2）
     * 与 PM 02-NSM 精确定义对齐：仅前三个业务角色计入 WAADR
     */
    private static String normalizeAuthorRole(String role) {
        if (role == null) return "unknown";
        String s = role.trim().toLowerCase();
        return switch (s) {
            case "business_expert", "compliance_officer", "risk_analyst",
                 "engineer", "admin" -> s;
            default -> "unknown";
        };
    }

    /**
     * 将策略源码写入静态文件作为兜底。
     *
     * @param moduleName   模块名
     * @param functionName 函数名
     * @param content      策略源码内容
     */
    private void writeStaticPolicyFile(String moduleName, String functionName, String content) {
        try {
            Path moduleDir = Paths.get(dualWriteBasePath, moduleName);
            Files.createDirectories(moduleDir);
            Path policyFile = moduleDir.resolve(functionName + ".aster");
            Files.writeString(policyFile, content, StandardCharsets.UTF_8);
            LOG.debugf("双写策略文件: %s", policyFile);
        } catch (IOException e) {
            LOG.warnf(e, "双写策略文件失败: module=%s, function=%s", moduleName, functionName);
        }
    }

    /**
     * 提交指定版本至审批流程。
     *
     * @param versionId  版本主键
     * @param submittedBy 提交人
     * @return 更新后的策略版本
     */
    @Transactional
    public PolicyVersion submitForApproval(Long versionId, String submittedBy) {
        PolicyVersion version = requireVersion(versionId);
        // PM v1.1：审批流是 Free→Pro 的核心转化抓手，Free 档位禁用 reviewer 流程
        // 详见 aster-deploy/docs/pm/05-pricing-packaging.md（SOX 职责分离）
        if (!planGate.allowsApproval(version.tenantId)) {
            throw new PlanLimitException("reviewer_required");
        }
        if (version.status != VersionStatus.DRAFT) {
            throw new IllegalStateException(
                String.format("仅草稿版本可提交审批: versionId=%d, status=%s", versionId, version.status)
            );
        }

        version.status = VersionStatus.SUBMITTED;
        version.submittedBy = submittedBy;
        version.submittedAt = Instant.now();
        version.rejectedBy = null;
        version.rejectedAt = null;
        version.persist();
        return version;
    }

    /**
     * 审批通过指定版本。
     *
     * @param versionId 版本主键
     * @param approvedBy 审批人
     * @return 已批准的策略版本
     */
    @Transactional
    public PolicyVersion approveVersion(Long versionId, String approvedBy) {
        PolicyVersion version = requireVersion(versionId);
        // 防御深度：即使 submitForApproval 没拦住，approve 也再检查一次 plan
        // 防止 Free 用户绕过 submit 直接调 approve（未来如开放此接口需要）
        if (!planGate.allowsApproval(version.tenantId)) {
            throw new PlanLimitException("reviewer_required");
        }
        if (version.status != VersionStatus.SUBMITTED) {
            throw new IllegalStateException(
                String.format("仅已提交版本可审批通过: versionId=%d, status=%s", versionId, version.status)
            );
        }

        // P0-C 稳定性门禁（ADR 0031）：批准=托付生产，Experimental 特性不该无声进批准链。
        // strict 扫版本 Core IR（coreJson 空则从 content 现编译，fail-closed），检出 W600 → 422。
        stabilityEnforcement.enforceVersion(versionId, version.policyId, version.tenantId,
            version.coreJson, version.content, version.locale, version.aliasSet,
            io.aster.policy.stability.StabilityEnforcement.Surface.APPROVE, approvedBy);

        version.status = VersionStatus.APPROVED;
        version.approvedBy = approvedBy;
        version.approvedAt = Instant.now();
        version.rejectedBy = null;
        version.rejectedAt = null;
        version.persist();
        return version;
    }

    /**
     * 拒绝指定版本的审批请求。
     *
     * @param versionId 版本主键
     * @param rejectedBy 拒绝人
     * @param reason     拒绝原因
     * @return 已拒绝的策略版本
     */
    @Transactional
    public PolicyVersion rejectVersion(Long versionId, String rejectedBy, String reason) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.SUBMITTED) {
            throw new IllegalStateException(
                String.format("仅已提交版本可被拒绝: versionId=%d, status=%s", versionId, version.status)
            );
        }

        version.status = VersionStatus.REJECTED;
        version.rejectedBy = rejectedBy;
        version.rejectedAt = Instant.now();
        version.approvedBy = null;
        version.approvedAt = null;
        if (reason != null && !reason.isBlank()) {
            String rejectionNote = "[Rejected] " + reason.trim();
            if (version.notes == null || version.notes.isBlank()) {
                version.notes = rejectionNote;
            } else {
                version.notes = version.notes + System.lineSeparator() + rejectionNote;
            }
        }
        version.persist();
        return version;
    }

    /**
     * 激活指定版本并更新 catalog 默认版本，同时通知依赖组件刷新缓存。
     *
     * @param versionId   策略版本主键
     * @param activatedBy 激活操作人
     */
    @Transactional
    public void activateVersion(Long versionId, String activatedBy) {
        // 正常发布激活：发 DRAFT_PUBLISHED 埋点（这是"草稿被审批后正式发布"语义）。
        activateVersionInternal(versionId, activatedBy, true);
    }

    /**
     * 激活的核心实现：状态校验 + 停旧 + 置 active + 同步 catalog 指针 + 发激活通知。
     *
     * <p>{@code emitDraftPublished} 控制是否发 {@link NsmEvents#DRAFT_PUBLISHED} 埋点：
     * 正常发布激活发（true）；回滚（{@link #rollbackToVersion}）不发（false）——回滚是
     * "恢复一个历史版本"而非"草稿被采纳发布"，发 DRAFT_PUBLISHED 会同时抬高
     * {@code draft_published_total}（回滚率分母）并污染 source_kind 维度的 AI 草稿采纳
     * 指标。回滚自有 {@code RULE_ROLLED_BACK} 埋点（REST 层发）。
     *
     * @param emitDraftPublished 是否发 DRAFT_PUBLISHED 埋点（正常激活 true，回滚 false）
     */
    private void activateVersionInternal(Long versionId, String activatedBy, boolean emitDraftPublished) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.APPROVED) {
            throw new IllegalStateException(
                String.format("仅已审批通过的版本可激活: versionId=%d, status=%s", versionId, version.status)
            );
        }

        // P0-C 稳定性门禁（ADR 0031）：激活=生产上线，strict 拒 Experimental。回滚也经此路径
        // （activateVersionInternal），故回滚同受门禁——防已批准的 Experimental 版本经回滚旁路上线。
        // coreJson 空则从 content 现编译（fail-closed），不漏报。
        stabilityEnforcement.enforceVersion(versionId, version.policyId, version.tenantId,
            version.coreJson, version.content, version.locale, version.aliasSet,
            io.aster.policy.stability.StabilityEnforcement.Surface.ACTIVATE, activatedBy);

        // 安全审计 C1：按 (policyId, tenantId) 停用，堵跨租户 DoS——policyId 非租户唯一，
        // tenantless 停用会波及其它租户同 policyId 的 active 版本。
        PolicyVersion.deactivateAllVersions(version.policyId, version.tenantId);
        version.active = true;
        version.activatedAt = Instant.now();
        version.activatedBy = activatedBy;
        version.persist();

        // 审计 #98（Medium 2）：显式 flush 触发 @Version 乐观锁检查，在事务提交前就把
        // 双重激活竞态暴露成 OptimisticLockException。两个并发激活/回滚都会 UPDATE 同一
        // 当前 active 行（deactivateAllVersions 把它置 active=false），后提交者版本号不匹配
        // → 抛 OLE 回滚，保证每个 (policyId, tenantId) 至多一条 active 行。不 flush 的话
        // OLE 会推迟到 @Transactional 边界提交时才抛，越过本方法的 try/catch。
        try {
            entityManager.flush();
        } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
            LOG.warnf("双重激活竞态被乐观锁拦截: versionId=%d, policyId=%s, tenantId=%s",
                versionId, version.policyId, version.tenantId);
            // WebApplicationException 的构造签名是 (Throwable cause, Response response)，
            // 没有 (Response, Throwable) 重载——按 cause 在前传入，保留 OLE 作为 cause。
            throw new jakarta.ws.rs.WebApplicationException(
                e,
                jakarta.ws.rs.core.Response.status(409)
                    .entity(java.util.Map.of(
                        "error", "concurrent_activation",
                        "message", "Another activation/rollback for this policy committed "
                            + "concurrently. Retry the operation.",
                        "policyId", version.policyId))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build());
        }

        PolicyCatalog catalog = PolicyCatalog.find(
            "tenantId = ?1 and moduleName = ?2 and functionName = ?3",
            version.tenantId,
            version.moduleName,
            version.functionName
        ).firstResult();
        if (catalog != null) {
            catalog.defaultVersionId = versionId;
            catalog.updatedAt = Instant.now();
            catalog.persist();
        }

        emitActivationNotification(version, catalog);
        if (emitDraftPublished) {
            trackDraftPublished(version, activatedBy);
        }
    }

    /**
     * 设置版本的 library 可见性（ADR 0015 阶段3）。
     *
     * <p>标记 true 后，该版本可被其它策略经 {@code Use 模块名 as vN} 引用。
     * 这是显式的发布治理动作——默认 false，需主动开启。
     *
     * @param versionId      策略版本主键
     * @param libraryVisible 是否可作为 library 被引用
     */
    @Transactional
    public void setLibraryVisible(Long versionId, boolean libraryVisible) {
        PolicyVersion version = requireVersion(versionId);
        version.libraryVisible = libraryVisible;
        version.persist();
    }

    /**
     * NSM 埋点：发布版本被激活
     *
     * 后端为 draft_published 的权威信号源（强一致），前端同名事件保留作为 UX 计时器。
     * 分析时按 emitted_by="server" 过滤即可去重。
     */
    private void trackDraftPublished(PolicyVersion version, String activatedBy) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("rule_id", version.policyId);
        props.put("draft_id", version.policyId);
        props.put("version", version.version);
        props.put("source_kind", version.sourceKind);
        props.put("tenant_id", version.tenantId != null ? version.tenantId : "unknown");
        props.put("reviewer_id", activatedBy);
        // v1.2：authorRole 从 PolicyVersion 持久化读取（cloud→api 通过 createVersion 传播）
        props.put("author_role", version.authorRole != null ? version.authorRole : "unknown");
        props.put("emitted_by", "server");
        nsmTelemetry.track(activatedBy, NsmEvents.DRAFT_PUBLISHED, props);
    }

    /**
     * 创建新版本
     *
     * 自动停用旧的活跃版本，确保每个 policyId 只有一个活跃版本。
     *
     * @param policyId     策略ID
     * @param moduleName   模块名
     * @param functionName 函数名
     * @param content      策略内容
     * @param createdBy    创建者
     * @param notes        备注
     * @return 新创建的版本
     * @deprecated ★仅 test 用（tenantless、直接 active、**不走 P0-C SAVE 门禁**，Codex M3 审查
     *     标记的潜在旁路）。生产创建须走 catalogId 版本 {@link #createVersion(UUID, String, String,
     *     String, String, String)}（有 tenant 归属 + SAVE stability enforcement）。禁止生产调用。
     */
    @Deprecated
    @Transactional
    public PolicyVersion createVersion(
        String policyId,
        String moduleName,
        String functionName,
        String content,
        String createdBy,
        String notes
    ) {
        // 停用旧的活跃版本
        PolicyVersion.deactivateAllVersions(policyId);

        // 创建新版本（先锁链尾，prevHash 与存量重编译的后继改写串行）
        PolicyVersion.lockChainTail(policyId);
        PolicyVersion newVersion = new PolicyVersion(
            policyId,
            moduleName,
            functionName,
            content,
            createdBy,
            notes
        );

        newVersion.persist();

        return newVersion;
    }

    /**
     * 获取活跃版本
     *
     * @param policyId 策略ID
     * @return 活跃版本，如果不存在返回 null
     */
    public PolicyVersion getActiveVersion(String policyId) {
        return PolicyVersion.findActiveVersion(policyId);
    }

    /**
     * 获取所有版本（按版本号降序）
     *
     * @param policyId 策略ID
     * @return 版本列表
     */
    public List<PolicyVersion> getAllVersions(String policyId) {
        return PolicyVersion.findAllVersions(policyId);
    }

    /**
     * 获取指定版本
     *
     * @param policyId 策略ID
     * @param version  版本号
     * @return 版本实体，如果不存在返回 null
     */
    public PolicyVersion getVersion(String policyId, Long version) {
        return PolicyVersion.findByVersion(policyId, version);
    }

    // ── 租户范围重载（红队 P0-A：跨租户 IDOR 修复）────────────────────────────
    // 面向外部请求的路径必须用带 tenantId 的版本：policyId 客户端可控且非租户命名空间，
    // 只按 policyId 查会让 A 租户传 B 租户 policyId 越权读/回滚。REST 层传当前请求的
    // 已鉴权 tenantId（来自 ApiKey/TenantFilter，不可伪造）。

    /** 租户范围获取活跃版本。 */
    public PolicyVersion getActiveVersion(String policyId, String tenantId) {
        return PolicyVersion.findActiveVersion(policyId, tenantId);
    }

    /** 租户范围获取所有版本。 */
    public List<PolicyVersion> getAllVersions(String policyId, String tenantId) {
        return PolicyVersion.findAllVersions(policyId, tenantId);
    }

    /** 租户范围获取指定版本。 */
    public PolicyVersion getVersion(String policyId, Long version, String tenantId) {
        return PolicyVersion.findByVersion(policyId, version, tenantId);
    }

    /**
     * 回滚到指定版本
     *
     * <p>回滚 = 把某个历史版本重新设为活跃版本。它**复用正常激活路径**
     * （{@link #activateVersion}），因此天然继承同一组发布治理不变量：
     * <ul>
     *   <li>仅 {@code status == APPROVED} 的版本可被激活——堵住"未经审批的草稿
     *       （含 AI 起草的 {@code ai_draft}）经回滚旁路直接上线"的治理缺口。
     *       合法回滚（目标是曾被正常激活过的版本）天然满足此约束，因为激活不改
     *       version.status，曾激活版本的 status 一直是 APPROVED。</li>
     *   <li>同步更新 {@code catalog.defaultVersionId}——否则 active 指向新版本但
     *       catalog 仍指旧版本，{@code findActiveVersion}（id==defaultVersionId
     *       AND active==true）会两条件不交集而返回空，导致回滚后 /evaluate 读不到
     *       活跃版本、评估失败。</li>
     *   <li>发出版本激活通知（pg_notify），缓存失效由监听方负责。</li>
     * </ul>
     *
     * @param policyId    策略ID
     * @param version     目标版本号
     * @param performedBy 执行回滚的操作人（写入 activatedBy / 审计 / telemetry）
     * @return 回滚后的活跃版本
     * @throws IllegalArgumentException 如果目标版本不存在
     * @throws IllegalStateException    如果目标版本未审批通过（status != APPROVED）
     */
    @Transactional
    public PolicyVersion rollbackToVersion(String policyId, Long version, String performedBy) {
        return rollbackToVersion(policyId, version, performedBy, null);
    }

    /**
     * 租户范围回滚（红队 P0-A）。tenantId 非空时强制目标版本归属该租户，堵住跨租户回滚
     * （A 租户传 B 租户 policyId 回滚其版本）。REST 层必须传当前已鉴权 tenantId。
     * tenantId==null 保留给内部可信路径（workflow/anomaly，policyId 已来自自身租户）。
     */
    @Transactional
    public PolicyVersion rollbackToVersion(String policyId, Long version, String performedBy, String tenantId) {
        // 查找目标版本（tenantId 非空时租户范围查询，避免跨租户回滚）
        PolicyVersion targetVersion = tenantId != null
            ? PolicyVersion.findByVersion(policyId, version, tenantId)
            : PolicyVersion.findByVersion(policyId, version);

        if (targetVersion == null) {
            throw new IllegalArgumentException(
                String.format("版本不存在: policyId=%s, version=%d", policyId, version)
            );
        }

        // 收敛到激活核心：status 校验 + catalog 指针同步 + 激活通知一并继承。
        // emitDraftPublished=false：回滚不发 DRAFT_PUBLISHED（避免污染回滚率/草稿采纳
        // 指标），回滚的 RULE_ROLLED_BACK 埋点由 REST 层单独发。
        activateVersionInternal(targetVersion.id, performedBy, false);

        return targetVersion;
    }

    /**
     * 删除策略的所有版本
     *
     * 注意：此操作不可逆，仅用于测试或管理目的。
     *
     * @param policyId 策略ID
     * @return 删除的版本数量
     */
    @Transactional
    public long deleteAllVersions(String policyId) {
        return PolicyVersion.delete("policyId = ?1", policyId);
    }

    private PolicyVersion requireVersion(Long versionId) {
        PolicyVersion version = PolicyVersion.findById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("策略版本不存在: versionId=" + versionId);
        }
        return version;
    }

    private void emitActivationNotification(PolicyVersion version, PolicyCatalog catalog) {
        if (entityManager == null) {
            return;
        }

        String tenantId = catalog != null && catalog.tenantId != null ? catalog.tenantId : version.tenantId;
        String moduleName = catalog != null ? catalog.moduleName : version.moduleName;
        String functionName = catalog != null ? catalog.functionName : version.functionName;
        String payload = String.format(
            "{\"tenantId\":\"%s\",\"module\":\"%s\",\"function\":\"%s\",\"versionId\":%d}",
            tenantId,
            moduleName,
            functionName,
            version.id
        );

        try {
            entityManager
                .createNativeQuery("SELECT pg_notify('policy_version_activated', :payload)")
                .setParameter("payload", payload)
                .getSingleResult();
        } catch (Exception e) {
            LOG.warnf(e, "发送策略版本激活通知失败: versionId=%d", version.id);
        }
    }
}
