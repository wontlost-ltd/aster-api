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
    @Inject io.aster.policy.stability.ToolchainIdentityProvider toolchains;

    @Test
    @Transactional
    void repeatedStartupEnqueueCreatesSingleActiveRun() {
        String toolchain = toolchains.currentToolchainId();
        entityManager.createNativeQuery("DELETE FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).executeUpdate();
        runs.enqueue("all", null, "admin:user-7");
        runs.enqueue("all", null, "admin:user-8");
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

    private long count(String toolchain) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult()).longValue();
    }
}
