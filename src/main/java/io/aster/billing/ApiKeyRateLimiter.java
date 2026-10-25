package io.aster.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-API-key 每秒限流（API-7），event-loop 安全、热路径零等待。
 *
 * <p>算法与 aster-cloud {@code src/lib/api-rate-limiter.ts} 一致：按 plan 取 RPS 上限，
 * Redis 固定 1 秒窗口 INCR + EXPIRE 计集群用量。区别只在于「等不等回包」：
 * 原实现每个请求同步打 cloud {@code /api/internal/api/rate-check}，在 event-loop 上
 * {@code Future.get} 等 ~100ms（#393：吞吐被这条出站调用钉死）。现在：
 * <ol>
 *   <li>本副本内存窗口先计数并立即裁决——单副本永远不会超过上限；</li>
 *   <li>同时异步 INCR 本服务自己的 Redis 窗口，回包的集群计数写回内存窗口；
 *       集群计数一旦达到上限，本秒内所有副本的后续请求都被拒绝。</li>
 * </ol>
 * 代价：集群级超放量上界 = 一个 Redis RTT（亚毫秒）内各副本并发放行的请求数，
 * 与 cloud 实现「1 秒边界稍宽松」同量级，PM 口径可接受。Redis 故障时退化为单副本窗口
 * （仍按上限限流），与 cloud 的 fail-open 相比更保守但不阻塞业务。
 */
@ApplicationScoped
public class ApiKeyRateLimiter {

    /** 与 cloud PLAN_RPS 同步维护；free 为 0 表示不开放 API。 */
    static final Map<String, Integer> PLAN_RPS = Map.of(
        "free", 0,
        "trial", 10,
        "pro", 10,
        "team", 50,
        "enterprise", 200);
    /** 未知 plan 的兜底上限，与 cloud {@code PLAN_RPS[plan] ?? 10} 一致。 */
    static final int DEFAULT_RPS = 10;

    @Inject
    LocalQuotaSnapshotService snapshot;

    /** 包可见：测试注入固定时钟，避免秒边界造成的不稳定。 */
    LongSupplier clockMillis = System::currentTimeMillis;

    /** 每个 API key 只保留当前秒的窗口；闲置 key 几秒后自动淘汰。 */
    private final Cache<String, Window> windows = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(Duration.ofSeconds(5))
        .build();

    /** 单个 API key 在某一秒内的计数：本副本放行数 + 已观测到的集群计数。 */
    private static final class Window {
        final long second;
        final AtomicInteger admitted = new AtomicInteger();
        final AtomicLong clusterUsed = new AtomicLong();

        Window(long second) {
            this.second = second;
        }
    }

    static int limitFor(String plan) {
        if (plan == null) return DEFAULT_RPS;
        return PLAN_RPS.getOrDefault(plan.toLowerCase(Locale.ROOT), DEFAULT_RPS);
    }

    /** 尝试为该 key 消耗当前秒的一个配额；纯内存裁决，Redis 同步在后台进行。 */
    public ApiQuotaGuard.RateCheck tryAcquire(String apiKeyId, String plan) {
        int limit = limitFor(plan);
        if (limit <= 0) {
            return new ApiQuotaGuard.RateCheck(false, 0, 0);
        }
        long second = clockMillis.getAsLong() / 1000;
        Window w = windows.asMap().compute(apiKeyId,
            (k, old) -> old != null && old.second == second ? old : new Window(second));
        long cluster = w.clusterUsed.get();
        if (cluster >= limit) {
            return new ApiQuotaGuard.RateCheck(false, (int) cluster, limit);
        }
        int local = w.admitted.incrementAndGet();
        if (local > limit) {
            return new ApiQuotaGuard.RateCheck(false, local, limit);
        }
        snapshot.incrementRateWindow(apiKeyId, second)
            .subscribe().with(v -> w.clusterUsed.accumulateAndGet(v, Math::max));
        return new ApiQuotaGuard.RateCheck(true, (int) Math.max(local, cluster), limit);
    }
}
