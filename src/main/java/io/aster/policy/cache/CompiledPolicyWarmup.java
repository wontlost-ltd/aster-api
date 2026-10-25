package io.aster.policy.cache;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动时预编译各策略当前默认版本，填满编译缓存（issue #392）。
 *
 * <p>新副本（0.5 CPU）上首次编译要 2s 以上；若让线上流量去承担，扩容/发版后的并发首次评估
 * 会排队、超时甚至失败。预热在独立 daemon 线程里逐个编译（小副本不并行，避免与启动抢 CPU），
 * 与请求共用 single-flight，期间到达的同版本请求直接加入同一次编译。
 *
 * <p>有界：最多 {@code max-versions} 个版本、最长 {@code max-duration}。预热进行中且未超时
 * {@link #isReady()} 为 false，由 readiness 检查据此推迟接流量；超时后无论是否完成都视为就绪，
 * 预热失败只记日志，不影响启动。
 */
@ApplicationScoped
public class CompiledPolicyWarmup {

    private static final Logger LOG = Logger.getLogger(CompiledPolicyWarmup.class);

    @Inject
    PolicySourceRepository repository;

    @Inject
    PolicyEvaluationService evaluationService;

    @ConfigProperty(name = "aster.policy.compile-warmup.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "aster.policy.compile-warmup.max-versions", defaultValue = "20")
    int maxVersions;

    @ConfigProperty(name = "aster.policy.compile-warmup.max-duration", defaultValue = "45s")
    Duration maxDuration;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile long deadlineNanos;
    private volatile int warmed;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("编译缓存预热已关闭");
            return;
        }
        if (!begin()) {
            return;
        }
        Thread thread = new Thread(this::runWarmup, "compile-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 同步执行一次预热（幂等：同一实例只执行一次，已执行过则直接返回上次结果）。返回成功编译的版本数。
     */
    public int warmUp() {
        return begin() ? runWarmup() : warmed;
    }

    private int runWarmup() {
        try {
            List<PolicyVersion> candidates = QuarkusTransaction.requiringNew()
                .call(() -> repository.findDefaultActiveVersions(maxVersions));
            int ok = 0;
            for (PolicyVersion version : candidates) {
                Duration remaining = remaining();
                if (remaining.isNegative() || remaining.isZero()) {
                    LOG.warnf("编译缓存预热超时，已预热 %d/%d 个版本，其余留给首个请求", ok, candidates.size());
                    break;
                }
                ok += warmOne(version, remaining) ? 1 : 0;
            }
            warmed = ok;
            LOG.infof("编译缓存预热完成: %d/%d 个版本", ok, candidates.size());
            return ok;
        } catch (Exception e) {
            LOG.warnf(e, "编译缓存预热失败（不影响启动，首个请求按需编译）");
            return warmed;
        } finally {
            running = false;
        }
    }

    /** 预热进行中且未超过时限时为 false；未启用、已完成或已超时均为 true。 */
    public boolean isReady() {
        return !running || System.nanoTime() - deadlineNanos >= 0;
    }

    public int warmedCount() {
        return warmed;
    }

    private boolean begin() {
        if (!started.compareAndSet(false, true)) {
            return false;
        }
        deadlineNanos = System.nanoTime() + maxDuration.toNanos();
        running = true;
        return true;
    }

    private Duration remaining() {
        return Duration.ofNanos(deadlineNanos - System.nanoTime());
    }

    /** 单个版本编译失败（源码错误、门禁拒绝等）只跳过，不中断其余版本的预热。 */
    private boolean warmOne(PolicyVersion version, Duration remaining) {
        try {
            evaluationService.warmUpCompiled(version).await().atMost(remaining);
            return true;
        } catch (Exception e) {
            LOG.debugf("预热跳过版本 %d（%s.%s）: %s", version.id, version.moduleName, version.functionName, e.getMessage());
            return false;
        }
    }
}
