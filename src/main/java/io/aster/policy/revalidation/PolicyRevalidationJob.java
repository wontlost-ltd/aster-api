package io.aster.policy.revalidation;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class PolicyRevalidationJob {
    @Inject RevalidationRunService runs;
    @ConfigProperty(name = "aster.revalidation.batch-size", defaultValue = "50") int batchSize;

    // 节奏可运维调整（ASTER_REVALIDATION_INTERVAL / ASTER_REVALIDATION_BATCH_SIZE），忙时放慢全量重验。
    @Scheduled(every = "${aster.revalidation.interval}", concurrentExecution = SKIP)
    void tick() {
        runs.claimAndProcessBatch(Math.max(1, batchSize));
    }
}
