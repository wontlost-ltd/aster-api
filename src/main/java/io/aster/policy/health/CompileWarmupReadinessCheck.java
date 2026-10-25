package io.aster.policy.health;

import io.aster.policy.cache.CompiledPolicyWarmup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * 编译缓存预热未完成（且未超过预热时限）前报告未就绪，让新副本在冷编译完成后再接流量。
 * 时限由预热自身保证，预热卡住也不会让副本永远不就绪。
 */
@Readiness
@ApplicationScoped
public class CompileWarmupReadinessCheck implements HealthCheck {

    private final CompiledPolicyWarmup warmup;

    @Inject
    public CompileWarmupReadinessCheck(CompiledPolicyWarmup warmup) {
        this.warmup = warmup;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("compile-warmup")
            .status(warmup.isReady())
            .withData("warmed", warmup.warmedCount())
            .build();
    }
}
