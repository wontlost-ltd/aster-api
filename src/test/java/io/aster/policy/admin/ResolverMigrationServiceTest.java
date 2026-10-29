package io.aster.policy.admin;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.service.PolicyVersionEnvelopeVerifier;
import io.aster.policy.service.PolicyVersionService;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 存量重编译任务（ADR 0051 §9）的数据库集成测试。
 *
 * <p>夹具模拟迁移前的存量行：4 段 envelope、旧格式工具链串、resolver_version 为 NULL。
 * 每个用例用独立 policyId 前缀，任务只扫描该前缀，避免影响共享库里其它用例的数据。
 */
@QuarkusTest
class ResolverMigrationServiceTest {

    private static final String OK_SRC =
        "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
    private static final String BAD_SRC = "this is not an aster policy";
    private static final String OLD_TOOLCHAIN = "abi=1.0;core=java;validator=1;build=old";

    @Inject ResolverMigrationService migration;
    @Inject EntityManager entityManager;
    @Inject ToolchainIdentityProvider toolchains;
    @Inject PolicyVersionService versionService;

    private final PolicyVersionEnvelopeVerifier verifier = new PolicyVersionEnvelopeVerifier();

    @Test
    void fullMigrationRecompilesEveryRowAndKeepsChainValid() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, OK_SRC + "\n", OK_SRC + "\n\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.DRAFT, VersionStatus.SUBMITTED));
        sql("UPDATE policy_versions SET core_json = NULL WHERE id = :id", ids.get(2));

        var report = migration.run(2, prefix);

        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.succeeded()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        String toolchain = toolchains.currentToolchainId();
        for (Long id : ids) {
            PolicyVersion v = load(id);
            assertThat(v.resolverVersion).isEqualTo(1);
            assertThat(v.resolverMigrationError).isNull();
            assertThat(v.sourceToolchainId).isEqualTo(toolchain);
            assertThat(v.sourceEnvelopeSha256).isEqualTo(PolicyVersion.computeSourceEnvelope(
                v.content, v.aliasSet, v.locale, toolchain, ""));
            assertThat(verifier.verify(v).status()).isEqualTo(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT);
        }
        assertThat(load(ids.get(0)).status).isEqualTo(VersionStatus.APPROVED);
        assertThat(load(ids.get(0)).coreJson).as("原有 core_json 改写为新 IR").isNotBlank().doesNotContain("legacy");
        assertThat(load(ids.get(2)).coreJson).as("原本按需编译的行不冻结 IR").isNull();
        assertChainValid(prefix + "p");
    }

    @Test
    void tamperedEnvelopeRowIsNotResealedArchivedOrRelinked() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, OK_SRC + "\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.DRAFT));
        sql("UPDATE policy_versions SET content = '" + OK_SRC.replace("3", "4") + "' WHERE id = :id", ids.get(0));
        PolicyVersion before = load(ids.get(0));
        String successorPrev = load(ids.get(1)).prevHash;

        var report = migration.run(50, prefix);

        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(ids.get(0));
            assertThat(f.error()).isEqualTo(ResolverMigrationService.INTEGRITY_ERROR);
        });
        PolicyVersion after = load(ids.get(0));
        assertThat(after.status).as("篡改不是名称解析失败，不归档").isEqualTo(VersionStatus.APPROVED);
        assertThat(after.archivedByResolverMigration).isFalse();
        assertThat(after.resolverVersion).isNull();
        assertThat(after.resolverMigrationError).isEqualTo(ResolverMigrationService.INTEGRITY_ERROR);
        assertThat(after.sourceEnvelopeSha256).isEqualTo(before.sourceEnvelopeSha256);
        assertThat(after.sourceToolchainId).isEqualTo(OLD_TOOLCHAIN);
        assertThat(after.coreJson).isEqualTo(before.coreJson);
        assertThat(verifier.verify(after).status()).as("篡改仍可见")
            .isEqualTo(PolicyVersionEnvelopeVerifier.VerifyResult.Status.TAMPERED);
        assertThat(load(ids.get(1)).prevHash).isEqualTo(successorPrev);
    }

    @Test
    void preEnvelopeRowIsCheckedAgainstSourceHash() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, OK_SRC + "\n", OK_SRC + "\n\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.APPROVED, VersionStatus.DRAFT));
        // 前两行模拟 envelope 特性之前的旧行：链接回落到 source_hash
        for (Long id : ids.subList(0, 2)) {
            sql("UPDATE policy_versions SET source_envelope_sha256 = NULL, source_toolchain_id = NULL WHERE id = :id", id);
        }
        sql("UPDATE policy_versions SET prev_hash = (SELECT source_hash FROM policy_versions WHERE id = "
            + ids.get(0) + ") WHERE id = :id", ids.get(1));
        sql("UPDATE policy_versions SET prev_hash = (SELECT source_hash FROM policy_versions WHERE id = "
            + ids.get(1) + ") WHERE id = :id", ids.get(2));
        sql("UPDATE policy_versions SET source_hash = '" + "0".repeat(64) + "' WHERE id = :id", ids.get(1));
        sql("UPDATE policy_versions SET prev_hash = '" + "0".repeat(64) + "' WHERE id = :id", ids.get(2));
        assertChainValid(prefix + "p");

        var report = migration.run(50, prefix);

        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(ids.get(1));
            assertThat(f.error()).isEqualTo(ResolverMigrationService.INTEGRITY_ERROR);
        });
        PolicyVersion mismatched = load(ids.get(1));
        assertThat(mismatched.status).isEqualTo(VersionStatus.APPROVED);
        assertThat(mismatched.archivedByResolverMigration).isFalse();
        assertThat(mismatched.sourceEnvelopeSha256).as("不重新封印").isNull();
        assertThat(load(ids.get(0)).resolverVersion).as("source_hash 相符的旧行正常迁移").isEqualTo(1);
        assertChainValid(prefix + "p");
    }

    @Test
    void createVersionWaitsForMigrationLockOnChainTail() throws Exception {
        String prefix = prefix();
        java.util.UUID catalogId = QuarkusTransaction.requiringNew().call(() -> {
            PolicyCatalog c = new PolicyCatalog();
            c.id = UUID.randomUUID();
            c.tenantId = "t-rmig";
            c.moduleName = prefix + "m";
            c.functionName = "p";
            c.domain = "finance";
            c.createdAt = java.time.Instant.now();
            c.updatedAt = c.createdAt;
            c.persist();
            return c.id;
        });
        PolicyVersion tail = versionService.createVersion(catalogId, OK_SRC, "en-US");
        String newLink = "e".repeat(64);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<PolicyVersion> created = QuarkusTransaction.requiringNew().call(() -> {
                // 模拟迁移事务：锁住链尾并改写其 envelope，提交前新版本创建必须等待
                entityManager.find(PolicyVersion.class, tail.id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                entityManager.createNativeQuery("UPDATE policy_versions SET source_envelope_sha256 = :e WHERE id = :id")
                    .setParameter("e", newLink).setParameter("id", tail.id).executeUpdate();
                var future = executor.submit(() -> versionService.createVersion(catalogId, OK_SRC + "\n", "en-US"));
                assertThat(catchThrowable(() -> future.get(700, java.util.concurrent.TimeUnit.MILLISECONDS)))
                    .as("链尾被锁时新版本创建应阻塞").isInstanceOf(java.util.concurrent.TimeoutException.class);
                return future;
            });
            PolicyVersion next = created.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(next.prevHash).as("新版本接在迁移后的链接上").isEqualTo(newLink);
        } finally {
            executor.shutdownNow();
            deleteDualWriteFiles(java.nio.file.Paths.get("target", "policies", prefix + "m"));
        }
    }

    @Test
    void failedMiddleRowIsArchivedWithOldArtifactsAndChainStaysValid() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, BAD_SRC, OK_SRC + "\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.APPROVED, VersionStatus.DRAFT));
        PolicyVersion before = load(ids.get(1));

        var report = migration.run(50, prefix);

        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(ids.get(1));
            assertThat(f.error()).isNotBlank();
        });
        PolicyVersion failed = load(ids.get(1));
        assertThat(failed.status).isEqualTo(VersionStatus.ARCHIVED);
        assertThat(failed.archivedByResolverMigration).isTrue();
        assertThat(failed.statusBeforeArchive).isEqualTo(VersionStatus.APPROVED);
        assertThat(failed.resolverVersion).isNull();
        assertThat(failed.resolverMigrationError).isNotBlank();
        assertThat(failed.coreJson).isEqualTo(before.coreJson);
        assertThat(failed.sourceEnvelopeSha256).isEqualTo(before.sourceEnvelopeSha256);
        assertThat(failed.sourceToolchainId).isEqualTo(OLD_TOOLCHAIN);
        for (Long id : ids) {
            assertThat(verifier.verify(load(id)).status())
                .as("迁移归档的存量行按迁移前格式校验，不应误报篡改")
                .isEqualTo(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT);
        }
        assertChainValid(prefix + "p");
    }

    @Test
    void rerunSkipsSucceededRowsRetriesFailedAndRestoresStatusOnceFixed() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, BAD_SRC, OK_SRC + "\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.APPROVED, VersionStatus.DRAFT));
        migration.run(50, prefix);
        long lockBefore = load(ids.get(0)).lockVersion;

        var again = migration.run(50, prefix);
        assertThat(again.processed()).isEqualTo(1);
        assertThat(again.failed()).isEqualTo(1);
        assertThat(load(ids.get(0)).lockVersion).as("成功行被跳过").isEqualTo(lockBefore);
        assertThat(load(ids.get(1)).archivedByResolverMigration).isTrue();
        assertThat(load(ids.get(1)).statusBeforeArchive).isEqualTo(VersionStatus.APPROVED);
        assertChainValid(prefix + "p");

        fixContent(ids.get(1), OK_SRC + "\n\n\n");
        var fixed = migration.run(50, prefix);
        assertThat(fixed.processed()).isEqualTo(1);
        assertThat(fixed.succeeded()).isEqualTo(1);
        PolicyVersion restored = load(ids.get(1));
        assertThat(restored.status).isEqualTo(VersionStatus.APPROVED);
        assertThat(restored.archivedByResolverMigration).isFalse();
        assertThat(restored.statusBeforeArchive).isNull();
        assertThat(restored.resolverMigrationError).isNull();
        assertThat(restored.resolverVersion).isEqualTo(1);
        assertChainValid(prefix + "p");
        assertThat(migration.run(50, prefix).processed()).isZero();
    }

    @Test
    void manuallyArchivedRowKeepsItsStatusOnFailureAndSuccess() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(BAD_SRC), List.of(VersionStatus.ARCHIVED));

        migration.run(50, prefix);
        PolicyVersion failed = load(ids.get(0));
        assertThat(failed.status).isEqualTo(VersionStatus.ARCHIVED);
        assertThat(failed.archivedByResolverMigration).isFalse();
        assertThat(failed.statusBeforeArchive).isNull();
        assertThat(failed.resolverMigrationError).isNotBlank();

        fixContent(ids.get(0), OK_SRC);
        migration.run(50, prefix);
        PolicyVersion migrated = load(ids.get(0));
        assertThat(migrated.resolverVersion).isEqualTo(1);
        assertThat(migrated.status).as("人工归档行状态不动").isEqualTo(VersionStatus.ARCHIVED);
        assertThat(migrated.archivedByResolverMigration).isFalse();
        assertThat(migrated.resolverMigrationError).isNull();
    }

    @Test
    void interruptedRunConvergesToSameResultAsUninterruptedRun() {
        String prefix = prefix();
        List<String> sources = List.of(OK_SRC, BAD_SRC, OK_SRC + "\n", BAD_SRC + ".", OK_SRC + "\n\n");
        List<VersionStatus> statuses = List.of(VersionStatus.APPROVED, VersionStatus.DRAFT,
            VersionStatus.APPROVED, VersionStatus.SUBMITTED, VersionStatus.DRAFT);
        List<Long> straight = seed(prefix + "a", sources, statuses);
        List<Long> interrupted = seed(prefix + "b", sources, statuses);

        migration.run(50, prefix + "a");
        migration.migrate(interrupted.get(0));
        migration.migrate(interrupted.get(1));
        migration.run(2, prefix + "b");
        migration.run(2, prefix + "b");

        assertThat(snapshot(interrupted)).isEqualTo(snapshot(straight));
        assertChainValid(prefix + "a");
        assertChainValid(prefix + "b");
    }

    @Test
    void preExistingChainBreakIsNotLaunderedByMigration() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, OK_SRC + "\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.DRAFT));
        QuarkusTransaction.requiringNew().run(() -> entityManager
            .createNativeQuery("UPDATE policy_versions SET prev_hash = :h WHERE id = :id")
            .setParameter("h", "f".repeat(64)).setParameter("id", ids.get(1)).executeUpdate());

        migration.run(50, prefix);

        assertThat(load(ids.get(1)).prevHash).as("迁移前已断的链保持可见").isEqualTo("f".repeat(64));
    }

    @Test
    void errorTruncationDoesNotSplitSurrogatePairs() {
        String error = "x".repeat(3999) + "\uD83D\uDE00" + "tail";
        String truncated = ResolverMigrationService.truncate(error);
        assertThat(truncated).hasSize(3999);
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    private static String prefix() {
        return "rmig" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".";
    }

    /** 按版本升序插入一组存量行（4 段 envelope + 旧工具链串），prevHash 接前一行的链接。 */
    private List<Long> seed(String policyId, List<String> sources, List<VersionStatus> statuses) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Long> ids = new ArrayList<>();
            String prevLink = null;
            long baseVersion = System.currentTimeMillis();
            for (int i = 0; i < sources.size(); i++) {
                PolicyVersion v = new PolicyVersion();
                v.policyId = policyId;
                v.version = baseVersion + i;
                v.moduleName = "M";
                v.functionName = "p";
                v.content = sources.get(i);
                v.active = false;
                v.status = statuses.get(i);
                v.tenantId = "t-rmig";
                v.locale = "en-US";
                v.coreJson = "{\"legacy\":" + i + "}";
                v.sourceToolchainId = OLD_TOOLCHAIN;
                v.sourceEnvelopeSha256 = PolicyVersion.computeLegacySourceEnvelope(
                    v.content, null, v.locale, OLD_TOOLCHAIN);
                v.prevHash = prevLink;
                v.persist();
                prevLink = v.chainLink();
                ids.add(v.id);
            }
            return ids;
        });
    }

    /** 模拟合法修复：改源码并同步旧封印（source_hash、4 段 envelope）与后继的 prevHash，使行自洽、链完整。 */
    private void fixContent(Long id, String content) {
        String oldEnvelope = load(id).sourceEnvelopeSha256;
        String envelope = PolicyVersion.computeLegacySourceEnvelope(content, null, "en-US", OLD_TOOLCHAIN);
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("UPDATE policy_versions SET content = :c, source_hash = :h, "
                    + "source_envelope_sha256 = :e WHERE id = :id")
                .setParameter("c", content).setParameter("id", id).setParameter("e", envelope)
                .setParameter("h", io.aster.policy.security.AdminHmacVerifier.sha256Hex(
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .executeUpdate();
            entityManager.createNativeQuery("UPDATE policy_versions SET prev_hash = :e WHERE prev_hash = :old")
                .setParameter("e", envelope).setParameter("old", oldEnvelope).executeUpdate();
        });
    }

    /** 测试 profile 开启了双写，清掉本用例写出的静态策略文件。 */
    private static void deleteDualWriteFiles(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir)) {
            return;
        }
        try (var files = java.nio.file.Files.walk(dir)) {
            for (var f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(f);
            }
        }
    }

    private void sql(String statement, Long id) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(statement)
            .setParameter("id", id).executeUpdate());
    }

    private PolicyVersion load(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            PolicyVersion v = entityManager.find(PolicyVersion.class, id);
            entityManager.detach(v);
            return v;
        });
    }

    /** 链规则与 PolicyVersion#findPreviousVersionHash 一致：同 policyId 按版本升序，后继 prevHash = 前驱 chainLink。 */
    private void assertChainValid(String policyId) {
        List<PolicyVersion> rows = QuarkusTransaction.requiringNew().call(() -> PolicyVersion
            .<PolicyVersion>find("policyId = ?1 order by version asc, id asc", policyId).list());
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).prevHash)
                .as("版本 %d 的 prevHash 应等于前驱链接", rows.get(i).id)
                .isEqualTo(rows.get(i - 1).chainLink());
        }
    }

    private List<String> snapshot(List<Long> ids) {
        List<String> out = new ArrayList<>();
        for (Long id : ids) {
            PolicyVersion v = load(id);
            out.add(String.join("|", String.valueOf(v.status), String.valueOf(v.resolverVersion),
                String.valueOf(v.archivedByResolverMigration), String.valueOf(v.statusBeforeArchive),
                String.valueOf(v.resolverMigrationError != null), v.sourceEnvelopeSha256,
                String.valueOf(v.sourceToolchainId), String.valueOf(v.prevHash), String.valueOf(v.coreJson)));
        }
        return out;
    }
}
