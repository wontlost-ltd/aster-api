package io.aster.policy.revalidation;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PolicyRevalidationJobIT {
    @Inject RevalidationRunService runs;
    @Inject EntityManager entityManager;

    @Test
    void workerReturnsWithoutClaimWhenNoRunIsQueued() {
        assertThat(runs.claimAndProcessBatch(50)).isFalse();
    }

    @Test
    void concurrentWorkersClaimOnlyOneRun() throws Exception {
        String toolchain = "it-" + UUID.randomUUID();
        // 测试类本身不开事务；准备数据用独立事务提交，确保并发 worker 可见。
        QuarkusTransaction.requiringNew().run(() -> entityManager
            .createNativeQuery("INSERT INTO policy_revalidation_run(toolchain_id,scope,status,scope_max_version_id) VALUES (:tc,'all','queued',0)")
            .setParameter("tc", toolchain).executeUpdate());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> runs.claimAndProcessBatch(5));
            var second = pool.submit(() -> runs.claimAndProcessBatch(5));
            int claimed = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredHeartbeatCanBeTakenOverAndCompleted() {
        String toolchain = "it-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> entityManager
            .createNativeQuery("INSERT INTO policy_revalidation_run(toolchain_id,scope,status,cursor,scope_max_version_id,heartbeat_at) VALUES (:tc,'all','running',0,0,:heartbeat)")
            .setParameter("tc", toolchain).setParameter("heartbeat", Instant.now().minusSeconds(180)).executeUpdate());
        assertThat(runs.claimAndProcessBatch(5)).isTrue();
        Object status = entityManager.createNativeQuery("SELECT status FROM policy_revalidation_run WHERE toolchain_id=:tc")
            .setParameter("tc", toolchain).getSingleResult();
        assertThat(status).isEqualTo("done");
    }
}
