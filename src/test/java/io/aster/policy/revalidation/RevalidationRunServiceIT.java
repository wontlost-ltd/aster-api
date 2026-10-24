package io.aster.policy.revalidation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@QuarkusTest
class RevalidationRunServiceIT {
    @Inject RevalidationRunService runs;
    @Inject EntityManager entityManager;
    @Inject io.aster.policy.compiler.ValidationRepository validations;
    @Inject io.aster.policy.stability.ToolchainIdentityProvider toolchains;

    @Test
    @Transactional
    void repeatedStartupEnqueueCreatesSingleActiveRun() {
        String toolchain = toolchains.currentToolchainId();
        entityManager.createNativeQuery("DELETE FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).executeUpdate();
        runs.enqueue("all", null, "admin:user-7", "enforce");
        runs.enqueue("all", null, "admin:user-8", "enforce");
        assertThat(count(toolchain)).isEqualTo(1L);
        assertThat(entityManager.createNativeQuery("SELECT triggered_by FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult()).isEqualTo("admin:user-7");
    }

    @Test
    @Transactional
    void startupEnqueueRecordsToolchainChangeAsTrigger() {
        String toolchain = toolchains.currentToolchainId();
        entityManager.createNativeQuery("DELETE FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).executeUpdate();
        assertThat(runs.enqueueIfToolchainChanged()).isPresent();
        assertThat(entityManager.createNativeQuery("SELECT triggered_by FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult()).isEqualTo(RevalidationRunService.STARTUP_TRIGGER);
    }

    @Test
    @Transactional
    void reportRunLeavesAdvisoryRowsThatGateIgnoresUntilPromoted() {
        String toolchain = "it-adv-" + java.util.UUID.randomUUID();
        long versionId = 900_000_000L + (System.nanoTime() % 1_000_000);
        validations.insertIfAbsent(versionId, toolchain, "invalid", java.util.List.of(), null, true);
        assertThat(validations.findStatus(versionId, toolchain)).as("advisory 行对门禁不可见").isEmpty();
        validations.insertIfAbsent(versionId, toolchain, "valid", java.util.List.of(), null, false);
        var promoted = validations.findStatus(versionId, toolchain);
        assertThat(promoted).isPresent();
        assertThat(promoted.get().status()).as("提升只翻 advisory 标志，判定本身不可变").isEqualTo("invalid");
        validations.insertIfAbsent(versionId, toolchain, "valid", java.util.List.of(), null, true);
        assertThat(validations.findStatus(versionId, toolchain).get().status()).as("正式行不会被 report 任务降级").isEqualTo("invalid");
    }

    @Test
    @Transactional
    void previewCountsScopeAndEstimatesByPacing() {
        var preview = runs.preview("all", null);
        assertThat(preview.batchSize()).isEqualTo(50);
        assertThat(preview.intervalSeconds()).isEqualTo(30);
        assertThat(preview.estimatedMinutes()).isEqualTo((((preview.total() + 49) / 50) * 30 + 59) / 60);
    }

    private long count(String toolchain) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult()).longValue();
    }
}
