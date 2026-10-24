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
        runs.enqueue("all", null);
        runs.enqueue("all", null);
        assertThat(count(toolchain)).isEqualTo(1L);
    }

    private long count(String toolchain) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult()).longValue();
    }
}
