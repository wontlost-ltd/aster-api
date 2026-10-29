package io.aster.policy.admin;

import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.security.AdminHmacVerifier;
import io.aster.policy.service.PolicyVersionEnvelopeVerifier;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 存量重编译任务（ADR 0051 §9）：把迁移前的策略版本按"按位置解析"的新规则重新编译。
 *
 * <p>扫描 {@code resolver_version IS DISTINCT FROM 1} 的行（先按旧封印核对完整性，不符则只记错误），按主键升序分批，<b>每行一个事务</b>
 * （行锁后复查状态，并发或中断后重跑都收敛到同一结果）：
 * <ul>
 *   <li>成功：写新 Core IR（CORE_JSON 产物；core_json 仅原本非空时改写，原本按需编译的行继续按需编译）、用当前工具链重算 5 段 envelope 并改写
 *       source_toolchain_id、resolver_version=1、清空错误；仅本任务归档过的行恢复 status_before_archive。</li>
 *   <li>编译失败：旧 IR/envelope/工具链串原样保留并记录错误；当前非 ARCHIVED 才记下原状态、归档并置标记；
 *       已是 ARCHIVED（无论来源）只更新错误。resolver_version 保持 NULL，重跑会再试。</li>
 * </ul>
 *
 * <p><b>哈希链</b>：链规则是"同 policyId 按版本升序，后继 prev_hash = 前驱 chainLink"，chainLink 取 envelope。
 * 成功行的 envelope 变了，就在同一事务里把<b>直接后继</b>的 prev_hash 从旧链接改成新链接；失败行 envelope
 * 不变，其出边天然有效。于是每个行事务提交后链都成立，新旧行混杂、中断、重跑都不会断链。改写带比较条件
 * （后继 prev_hash 必须等于本行旧链接）：迁移前就已断开的链保持断开，不被迁移洗白。
 *
 * <p>api 版本不冻结词汇，按无词汇编译：曾依赖旧全局替换的非英文策略会以 E8xx 失败并被归档，属设计内行为
 * （带词汇的版本由 cloud 侧迁移处理）。
 */
@ApplicationScoped
public class ResolverMigrationService {

    private static final Logger LOG = Logger.getLogger(ResolverMigrationService.class);

    /** 按位置解析规则对应的 resolver 版本。 */
    public static final int RESOLVER_VERSION = 1;

    private static final int MAX_ERROR_LENGTH = 4000;

    /** 旧封印（envelope 或 source_hash）与当前字段不符时记录的错误。 */
    static final String INTEGRITY_ERROR = "pre-migration integrity mismatch";

    private static final String PENDING_SQL = """
        SELECT id FROM policy_versions
        WHERE resolver_version IS DISTINCT FROM :rv AND id > :cursor
          AND (CAST(:prefix AS text) IS NULL OR starts_with(policy_id, CAST(:prefix AS text)))
        ORDER BY id LIMIT :limit""";

    private static final String SUCCESS_SQL = """
        UPDATE policy_versions SET
          core_json = CASE WHEN core_json IS NULL THEN NULL ELSE CAST(:core AS jsonb) END,
          source_envelope_sha256 = :envelope,
          source_toolchain_id = :toolchain,
          resolver_version = :rv,
          resolver_migration_error = NULL,
          status = CASE WHEN archived_by_resolver_migration
                        THEN COALESCE(status_before_archive, status) ELSE status END,
          status_before_archive = CASE WHEN archived_by_resolver_migration
                                       THEN NULL ELSE status_before_archive END,
          archived_by_resolver_migration = false,
          lock_version = lock_version + 1
        WHERE id = :id""";

    private static final String FAILURE_SQL = """
        UPDATE policy_versions SET
          resolver_migration_error = :error,
          status_before_archive = CASE WHEN status <> 'ARCHIVED' THEN status ELSE status_before_archive END,
          archived_by_resolver_migration = CASE WHEN status <> 'ARCHIVED'
                                                THEN true ELSE archived_by_resolver_migration END,
          status = 'ARCHIVED',
          lock_version = lock_version + 1
        WHERE id = :id""";

    private static final String INTEGRITY_SQL = """
        UPDATE policy_versions SET resolver_migration_error = :error, lock_version = lock_version + 1
        WHERE id = :id""";

    private static final String ARTIFACT_SQL = """
        UPDATE policy_artifacts SET content = :content, content_sha256 = :sha
        WHERE policy_version_id = :id AND artifact_type = :type""";

    private static final String RELINK_SQL = """
        UPDATE policy_versions SET prev_hash = :newLink, lock_version = lock_version + 1
        WHERE prev_hash = :oldLink AND id = (
          SELECT s.id FROM policy_versions s
          WHERE s.policy_id = :policyId AND (s.version, s.id) > (:version, :id)
          ORDER BY s.version, s.id LIMIT 1)""";

    @Inject EntityManager entityManager;
    @Inject PolicyCompiler compiler;
    @Inject ToolchainIdentityProvider toolchains;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionEnvelopeVerifier envelopeVerifier;

    @Inject
    @CacheName("policy-compile-by-version")
    Cache compileByVersion;

    /** 单次运行报告：failures 只列本次失败的行。 */
    public record RunReport(int processed, int succeeded, int failed, List<Failure> failures) {}

    public record Failure(long id, String error) {}

    /** 单行结果；SKIPPED = 加锁后发现已迁移（被并发任务处理）或行已不存在，不计入报告。 */
    public record RowResult(long id, Status status, String error) {
        public enum Status { SUCCEEDED, FAILED, SKIPPED }
    }

    private record Migrated(RowResult result, PolicyVersion version) {}

    /**
     * 跑完所有待迁移行。游标保证同一次运行里失败行只尝试一次。
     *
     * @param batchSize      每批读取的主键数
     * @param policyIdPrefix 只处理 policy_id 以此开头的行；null = 全部（端点恒传 null，前缀供测试隔离）
     */
    public RunReport run(int batchSize, String policyIdPrefix) {
        List<RowResult> results = new ArrayList<>();
        List<Long> batch = pendingBatch(0, batchSize, policyIdPrefix);
        while (!batch.isEmpty()) {
            batch.forEach(id -> results.add(migrate(id)));
            batch = pendingBatch(batch.getLast(), batchSize, policyIdPrefix);
        }
        RunReport report = summarize(results);
        LOG.infof("resolver 迁移完成: processed=%d succeeded=%d failed=%d",
            report.processed(), report.succeeded(), report.failed());
        return report;
    }

    /** 在独立事务里迁移一行。基础设施异常只报告、不归档（不是名称解析失败），行保持待迁移以便重跑。 */
    public RowResult migrate(long id) {
        try {
            Migrated migrated = QuarkusTransaction.requiringNew().call(() -> migrateLocked(id));
            invalidateCaches(migrated.version());
            return migrated.result();
        } catch (RuntimeException e) {
            LOG.warnf(e, "resolver 迁移行异常，保持待迁移: id=%d", id);
            return new RowResult(id, RowResult.Status.FAILED,
                truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private Migrated migrateLocked(long id) {
        PolicyVersion v = entityManager.find(PolicyVersion.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (v == null || Integer.valueOf(RESOLVER_VERSION).equals(v.resolverVersion)) {
            return new Migrated(new RowResult(id, RowResult.Status.SKIPPED, null), null);
        }
        if (!sealIntact(v)) {
            update(INTEGRITY_SQL, id).setParameter("error", INTEGRITY_ERROR).executeUpdate();
            return new Migrated(new RowResult(id, RowResult.Status.FAILED, INTEGRITY_ERROR), null);
        }
        CompilationResult compiled = compiler.compile(v.content, v.locale, v.aliasSet);
        if (!compiled.isSuccess()) {
            String error = truncate(String.join("; ", compiled.getErrors()));
            update(FAILURE_SQL, id).setParameter("error", error).executeUpdate();
            return new Migrated(new RowResult(id, RowResult.Status.FAILED, error), null);
        }
        writeSuccess(v, compiled.getCoreJson());
        return new Migrated(new RowResult(id, RowResult.Status.SUCCEEDED, null), v);
    }

    /**
     * 迁移前先按旧封印核对完整性：有 envelope 的按其格式重算比对，无 envelope 的比对 source_hash。
     * 不符说明行已被改动，重新封印会把篡改洗成 INTACT，故不封印、不改链、不归档（篡改不是名称解析失败），
     * 只记错误留给人工处理。
     */
    private boolean sealIntact(PolicyVersion v) {
        var status = envelopeVerifier.verify(v).status();
        if (status == PolicyVersionEnvelopeVerifier.VerifyResult.Status.SKIPPED_LEGACY) {
            String actual = AdminHmacVerifier.sha256Hex(
                (v.content == null ? "" : v.content).getBytes(StandardCharsets.UTF_8));
            return actual.equals(v.sourceHash);
        }
        return status == PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT;
    }

    private void writeSuccess(PolicyVersion v, String coreJson) {
        String toolchain = toolchains.currentToolchainId();
        String envelope = PolicyVersion.computeSourceEnvelope(
            v.content, v.aliasSet, v.locale, toolchain, v.compileScopesJson());
        update(SUCCESS_SQL, v.id)
            .setParameter("core", coreJson)
            .setParameter("envelope", envelope)
            .setParameter("toolchain", toolchain)
            .setParameter("rv", RESOLVER_VERSION)
            .executeUpdate();
        byte[] content = coreJson.getBytes(StandardCharsets.UTF_8);
        update(ARTIFACT_SQL, v.id)
            .setParameter("content", content)
            .setParameter("sha", AdminHmacVerifier.sha256Hex(content))
            .setParameter("type", ArtifactType.CORE_JSON.name())
            .executeUpdate();
        relinkSuccessor(v, envelope);
    }

    /** 本行链接变了就让直接后继的 prev_hash 跟上；后继原本没接在旧链接上（链已断）则不动。 */
    private void relinkSuccessor(PolicyVersion v, String newLink) {
        String oldLink = v.chainLink();
        if (newLink.equals(oldLink)) {
            return;
        }
        update(RELINK_SQL, v.id)
            .setParameter("newLink", newLink)
            .setParameter("oldLink", oldLink)
            .setParameter("policyId", v.policyId)
            .setParameter("version", v.version)
            .executeUpdate();
    }

    private Query update(String sql, long id) {
        return entityManager.createNativeQuery(sql).setParameter("id", id);
    }

    private List<Long> pendingBatch(long cursor, int batchSize, String prefix) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(PENDING_SQL)
            .setParameter("cursor", cursor)
            .setParameter("rv", RESOLVER_VERSION)
            .setParameter("prefix", prefix)
            .setParameter("limit", batchSize)
            .getResultList().stream()
            .map(row -> ((Number) row).longValue())
            .toList());
    }

    /**
     * 成功行的旧编译结果在本节点缓存里失效；其它节点随发布重启清空。
     * 行事务已提交，失效失败只影响缓存新鲜度，不改变该行的迁移结果。
     */
    private void invalidateCaches(PolicyVersion v) {
        if (v == null) {
            return;
        }
        try {
            compileByVersion.invalidate(v.id).await().indefinitely();
            compiledPolicyCache.invalidate(v.tenantId, v.moduleName, v.functionName);
        } catch (RuntimeException e) {
            LOG.warnf(e, "resolver 迁移已提交，但缓存失效失败: id=%d", v.id);
        }
    }

    private static RunReport summarize(List<RowResult> results) {
        List<Failure> failures = results.stream()
            .filter(r -> r.status() == RowResult.Status.FAILED)
            .map(r -> new Failure(r.id(), r.error()))
            .toList();
        int succeeded = (int) results.stream().filter(r -> r.status() == RowResult.Status.SUCCEEDED).count();
        return new RunReport(succeeded + failures.size(), succeeded, failures.size(), failures);
    }

    static String truncate(String error) {
        String safe = error == null || error.isBlank() ? "unknown error" : error;
        if (safe.length() <= MAX_ERROR_LENGTH) {
            return safe;
        }
        // 截断点落在代理对中间时少截一位，避免留下孤立的高代理
        int end = Character.isHighSurrogate(safe.charAt(MAX_ERROR_LENGTH - 1)) ? MAX_ERROR_LENGTH - 1 : MAX_ERROR_LENGTH;
        return safe.substring(0, end);
    }
}
