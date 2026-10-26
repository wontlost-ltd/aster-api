package io.aster.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Per-API-key 每秒限流（API-7），非阻塞：裁决以 {@code Uni} 交给调用方组合进请求链。
 *
 * <p>算法与 aster-cloud {@code src/lib/api-rate-limiter.ts} 一致：按 plan 取 RPS 上限，Redis
 * 固定 1 秒窗口 {@code INCR}（首个 INCR 设 2s TTL），<b>INCR 回包 ≤ 上限即放行</b>。INCR 在 Redis
 * 端是原子的，所以无论多少副本并发，同一秒被放行的请求总数严格不超过上限——不存在「各副本
 * 先本地放行、回包后再纠正」的 N 倍超放。区别只在于计数放在本服务自己的 Redis，而不是每个
 * 请求同步打 cloud {@code /api/internal/api/rate-check}（#393）。
 *
 * <p>Redis 故障或超过 {@link #REDIS_TIMEOUT} 未回包时，退化为<b>单副本本地窗口</b>：每个副本
 * 只放行 {@code ceil(limit / fallbackReplicas)}。{@code fallbackReplicas} 由
 * {@code aster.billing.rate-limit.fallback-replicas} 配置为部署副本数时，集群总量仍不超过上限；
 * 未配置（默认 1）时，降级期间的集群上界是 {@code 副本数 × limit}——这是刻意的取舍：Redis
 * 不可用时宁可短时超放，也不把所有请求挡在门外。降级只影响故障期间，Redis 恢复后下一次
 * 请求即回到集群原子裁决。
 */
@ApplicationScoped
public class ApiKeyRateLimiter {

    private static final Logger LOG = Logger.getLogger(ApiKeyRateLimiter.class);

    /** 与 cloud PLAN_RPS 同步维护；free 为 0 表示不开放 API。 */
    static final Map<String, Integer> PLAN_RPS = Map.of(
        "free", 0,
        "trial", 10,
        "pro", 10,
        "team", 50,
        "enterprise", 200);
    /** 未知 plan 名的兜底上限，与 cloud {@code PLAN_RPS[plan] ?? 10} 一致。 */
    static final int DEFAULT_RPS = 10;
    /** 等 Redis 裁决的上限：正常亚毫秒，超过即说明 Redis 抖动，走本地兜底而不是拖慢请求。 */
    static final Duration REDIS_TIMEOUT = Duration.ofMillis(50);

    @Inject
    LocalQuotaSnapshotService snapshot;

    @ConfigProperty(name = "aster.billing.rate-limit.fallback-replicas", defaultValue = "1")
    int fallbackReplicas = 1;

    /** 包可见：测试注入固定时钟 / 更短超时，避免秒边界与等待造成的不稳定。 */
    LongSupplier clockMillis = System::currentTimeMillis;
    Duration redisTimeout = REDIS_TIMEOUT;

    /** 仅 Redis 降级时使用的本地窗口；每个 key 只保留当前秒，闲置几秒后自动淘汰。 */
    private final Cache<String, Window> fallbackWindows = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(Duration.ofSeconds(5))
        .build();

    private record Window(long second, AtomicInteger admitted) {}

    static int limitFor(String plan) {
        if (plan == null) return DEFAULT_RPS;
        return PLAN_RPS.getOrDefault(plan.toLowerCase(Locale.ROOT), DEFAULT_RPS);
    }

    /** 为该 key 消耗当前秒的一个配额；Redis 原子裁决，故障 / 超时退化为本地窗口。 */
    public Uni<ApiQuotaGuard.RateCheck> acquire(String apiKeyId, String plan) {
        int limit = limitFor(plan);
        if (limit <= 0) {
            return Uni.createFrom().item(new ApiQuotaGuard.RateCheck(false, 0, 0));
        }
        long second = clockMillis.getAsLong() / 1000;
        return snapshot.incrementRateWindow(apiKeyId, second)
            .ifNoItem().after(redisTimeout).fail()
            .map(used -> new ApiQuotaGuard.RateCheck(used <= limit, (int) Math.min(used, Integer.MAX_VALUE), limit))
            .onFailure().recoverWithItem(e -> localFallback(apiKeyId, second, limit, e));
    }

    private ApiQuotaGuard.RateCheck localFallback(String apiKeyId, long second, int limit, Throwable cause) {
        LOG.debugf("rate-limit redis 不可用，按本地窗口限流 apiKeyId=%.8s: %s", apiKeyId, cause.toString());
        int perReplica = (limit + Math.max(1, fallbackReplicas) - 1) / Math.max(1, fallbackReplicas);
        Window w = fallbackWindows.asMap().compute(apiKeyId,
            (k, old) -> old != null && old.second() == second ? old : new Window(second, new AtomicInteger()));
        int used = w.admitted().incrementAndGet();
        return new ApiQuotaGuard.RateCheck(used <= perReplica, used, perReplica);
    }
}
