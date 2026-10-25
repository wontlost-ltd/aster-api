package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.aster.billing.snapshot.UserSnapshot;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #393 回归：配额守门在 Vert.x event-loop 上执行时必须真正生效，且不阻塞 event-loop。
 *
 * <p>生产上 evaluate 在 event-loop 调用 {@link ApiQuotaGuard}；此前阻塞式 Redis 在那里抛
 * "The current thread cannot be blocked" 被吞掉，导致快照永不命中、月度计数永不递增、200% 硬上限
 * 永不触发。本测试用真实 Redis + 真实 event-loop 上下文复现该调用路径：
 * <ul>
 *   <li>快照命中（source=local，limit 来自 Redis 快照）；</li>
 *   <li>recordAsync 让 Redis 计数真实递增，且本地视图随 INCR 回包更新 → 超 200% 后 RATE_LIMITED；</li>
 *   <li>每秒限流在 event-loop 上按 plan 上限生效，集群计数落在 Redis 窗口；</li>
 *   <li>微基准：event-loop 上上万次 check + checkRate 远低于 Vert.x 阻塞告警阈值。</li>
 * </ul>
 * plan gate 配置与 plan 查询用手写桩（不访问 cloud）；Redis、快照服务、限流器均为容器内真实 bean。
 */
@QuarkusTest
class ApiQuotaGuardEventLoopTest {

    private static final String PATH = "/api/v1/policies/evaluate";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Inject
    Vertx vertx;

    @Inject
    LocalQuotaSnapshotService snapshot;

    @Inject
    ApiKeyRateLimiter rateLimiter;

    @Inject
    RedisDataSource redis;

    private ApiQuotaGuard guard;
    private String owner;
    private String apiKeyId;
    private LongSupplier originalClock;

    @BeforeEach
    void setUp() {
        // 手写桩而非 Mockito：微基准要测的是守门逻辑本身，Mockito 拦截每次调用的开销会淹没它
        guard = new ApiQuotaGuard() {
            @Override
            void postUsage(JsonObject body) {
                // 不发真实 cloud 上报
            }
        };
        guard.config = new EnabledPlanGateConfig();
        guard.planGateService = new PlanGateService() {
            @Override
            public PlanInfo lookupPlan(String tenantId) {
                return PlanInfo.failOpen(); // pro → 10 RPS
            }
        };
        guard.snapshot = snapshot;
        guard.rateLimiter = rateLimiter;

        owner = "it393-" + UUID.randomUUID();
        apiKeyId = "key393-" + UUID.randomUUID();
        originalClock = rateLimiter.clockMillis;
    }

    @AfterEach
    void tearDown() {
        rateLimiter.clockMillis = originalClock;
        snapshot.resetCounter(owner, LocalQuotaSnapshotService.currentPeriod());
        snapshot.invalidateUser(owner);
    }

    /** plan gate 开启、指向不可达 cloud 的固定配置。 */
    private static final class EnabledPlanGateConfig implements PlanGateConfig {
        @Override public boolean enabled() { return true; }
        @Override public String cloudInternalUrl() { return "http://cloud.invalid:3000"; }
        @Override public Optional<String> hmacKey() { return Optional.empty(); }
        @Override public Duration cacheTtl() { return Duration.ofMinutes(5); }
        @Override public int cacheMaxEntries() { return 100; }
        @Override public Duration requestTimeout() { return Duration.ofSeconds(1); }
        @Override public boolean failOpen() { return true; }
    }

    /** 在 Vert.x event-loop 上下文执行并取回结果；断言确实运行在 event-loop 线程上。 */
    private <T> T onEventLoop(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(v -> {
            try {
                assertThat(Context.isOnEventLoopThread()).isTrue();
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private ApiQuotaGuard.GuardResult checkOnEventLoop() throws Exception {
        return onEventLoop(() -> guard.check("tenant-393", owner));
    }

    private void recordSuccesses(int n) throws Exception {
        onEventLoop(() -> {
            for (int i = 0; i < n; i++) {
                guard.recordAsync(owner, owner, "tenant-393", apiKeyId, PATH, "success", 1L);
            }
            return null;
        });
    }

    @Test
    void snapshotHit_counterIncrements_andHardRejectTriggersOnEventLoop() throws Exception {
        snapshot.setUser(new UserSnapshot(owner, "pro", 5L, "active", null, null));

        // 首访触发异步 MGET（本次可能 fail-open），随后必须命中本地快照：limit 取自 Redis 快照
        await().atMost(WAIT).until(() -> checkOnEventLoop().limit() == 5L);
        ApiQuotaGuard.GuardResult fresh = checkOnEventLoop();
        assertThat(fresh.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(fresh.used()).isZero();

        recordSuccesses(9);
        await().atMost(WAIT).until(() -> snapshot.getCounter(owner) == 9L);
        await().atMost(WAIT).until(() -> checkOnEventLoop().used() == 9L);
        ApiQuotaGuard.GuardResult soft = checkOnEventLoop();
        assertThat(soft.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(soft.warning()).isEqualTo("soft-overage");

        // 第 10 次成功调用 = 200% → 429 hard reject
        recordSuccesses(1);
        await().atMost(WAIT).until(() -> snapshot.getCounter(owner) == 10L);
        await().atMost(WAIT).until(() -> checkOnEventLoop().verdict() == ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(checkOnEventLoop().used()).isEqualTo(10L);
    }

    @Test
    void failedCalls_doNotIncrementCounter() throws Exception {
        onEventLoop(() -> {
            guard.recordAsync(owner, owner, "tenant-393", apiKeyId, PATH, "api_error", 1L);
            return null;
        });
        recordSuccesses(1);
        await().atMost(WAIT).until(() -> snapshot.getCounter(owner) == 1L);
        assertThat(snapshot.getCounter(owner)).isEqualTo(1L);
    }

    @Test
    void perSecondRateLimit_enforcedOnEventLoop_andCountedInRedis() throws Exception {
        long fixedMillis = System.currentTimeMillis();
        rateLimiter.clockMillis = () -> fixedMillis;

        List<ApiQuotaGuard.RateCheck> results = onEventLoop(() -> {
            List<ApiQuotaGuard.RateCheck> out = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                out.add(guard.checkRate("tenant-393", apiKeyId));
            }
            return out;
        });

        assertThat(results).filteredOn(ApiQuotaGuard.RateCheck::allowed).hasSize(10);
        assertThat(results.get(14).allowed()).isFalse();
        assertThat(results.get(14).limit()).isEqualTo(10);
        String windowKey = "aq:rl:" + apiKeyId + ":" + (fixedMillis / 1000);
        await().atMost(WAIT).until(() -> "10".equals(redis.value(String.class).get(windowKey)));
        assertThat(redis.key(String.class).ttl(windowKey)).isBetween(0L, 2L);
    }

    @Test
    void eventLoopIsNeverBlocked_microBenchmark() throws Exception {
        snapshot.setUser(new UserSnapshot(owner, "pro", 1_000_000L, "active", null, null));
        await().atMost(WAIT).until(() -> checkOnEventLoop().limit() == 1_000_000L);

        int iterations = 20_000;
        long elapsedNanos = onEventLoop(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                guard.check("tenant-393", owner);
                guard.checkRate("tenant-393", apiKeyId);
            }
            return System.nanoTime() - start;
        });

        // 每对 check + checkRate 都是纯内存裁决：2 万次合计远低于 Vert.x 默认 2s 阻塞告警阈值。
        // 旧实现单次 checkRate 就要在 event-loop 上等 ~100ms 的 cloud 出站调用。
        long totalMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        System.out.printf("[#393 micro-benchmark] %d x (check + checkRate) on event-loop: %d ms total, %.2f us/op%n",
            iterations, totalMillis, elapsedNanos / 1000.0 / iterations);
        assertThat(totalMillis).isLessThan(1_000L);
    }
}
