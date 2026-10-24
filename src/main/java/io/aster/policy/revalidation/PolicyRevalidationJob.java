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

    @Scheduled(every = "30s", concurrentExecution = SKIP)
    void tick() {
        runs.claimAndProcessBatch(batchSize);
    }
}
