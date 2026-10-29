package io.aster.policy.admin;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.service.PolicyVersionEnvelopeVerifier;
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

    private final PolicyVersionEnvelopeVerifier verifier = new PolicyVersionEnvelopeVerifier();

    @Test
    void fullMigrationRecompilesEveryRowAndKeepsChainValid() {
        String prefix = prefix();
        List<Long> ids = seed(prefix + "p", List.of(OK_SRC, OK_SRC + "\n", OK_SRC + "\n\n"),
            List.of(VersionStatus.APPROVED, VersionStatus.DRAFT, VersionStatus.SUBMITTED));

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
            assertThat(v.coreJson).isNotBlank();
            assertThat(v.sourceToolchainId).isEqualTo(toolchain);
            assertThat(v.sourceEnvelopeSha256).isEqualTo(PolicyVersion.computeSourceEnvelope(
                v.content, v.aliasSet, v.locale, toolchain, ""));
            assertThat(verifier.verify(v).status()).isEqualTo(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT);
        }
        assertThat(load(ids.get(0)).status).isEqualTo(VersionStatus.APPROVED);
        assertChainValid(prefix + "p");
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

    private void fixContent(Long id, String content) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
            .createNativeQuery("UPDATE policy_versions SET content = :c WHERE id = :id")
            .setParameter("c", content).setParameter("id", id).executeUpdate());
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
