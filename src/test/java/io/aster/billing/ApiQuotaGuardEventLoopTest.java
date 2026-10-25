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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>recordAsync 让 Redis 计数真实递增，且本地视图随 INCR 回包更新 → 超 200% 后 RATE_LIMITED；
 *       并发在途请求造成的超出量以在途数为上界；</li>
 *   <li>每秒限流以 Uni 在 event-loop 上组合，按 plan 上限生效、续体回到 event-loop，计数落在 Redis 窗口；</li>
 *   <li>plan 未知（冷启动 / 租户缓存过期）不按兜底 10 RPS 误限：优先用 owner 快照的 plan，都未知则放行；
 *       Free 由月度配额 403 拦截；</li>
 *   <li>微基准：event-loop 上 check 与限流发起都只占微秒级。</li>
 * </ul>
 * plan gate 配置与 plan 查询用手写桩（不访问 cloud）；Redis、快照服务、限流器均为容器内真实 bean。
 */
@QuarkusTest
class ApiQuotaGuardEventLoopTest {

    private static final String PATH = "/api/v1/policies/evaluate";
    private static final String TENANT = "tenant-393";
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
    /** 租户 plan 缓存的模拟内容：缺失 = 缓存未命中 / 已过期。 */
    private final Map<String, PlanInfo> tenantPlans = new ConcurrentHashMap<>();

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
            public Optional<PlanInfo> peekPlan(String tenantId) {
                return Optional.ofNullable(tenantPlans.get(tenantId));
            }

            @Override
            public PlanInfo lookupPlan(String tenantId) {
                return tenantPlans.getOrDefault(tenantId, PlanInfo.failOpen());
            }
        };
        tenantPlans.put(TENANT, PlanInfo.failOpen()); // 默认租户 plan 已缓存为 pro → 10 RPS
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
        return checkOnEventLoop(TENANT);
    }

    private ApiQuotaGuard.GuardResult checkOnEventLoop(String tenant) throws Exception {
        return onEventLoop(() -> guard.check(tenant, owner));
    }

    private void recordSuccesses(int n) throws Exception {
        onEventLoop(() -> {
            for (int i = 0; i < n; i++) {
                guard.recordAsync(owner, owner, TENANT, apiKeyId, PATH, "success", 1L);
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
            guard.recordAsync(owner, owner, TENANT, apiKeyId, PATH, "api_error", 1L);
            return null;
        });
        recordSuccesses(1);
        await().atMost(WAIT).until(() -> snapshot.getCounter(owner) == 1L);
        assertThat(snapshot.getCounter(owner)).isEqualTo(1L);
    }

    /** 在 event-loop 上发起 n 次限流裁决（只订阅、不等待），在测试线程收集结果。 */
    private List<ApiQuotaGuard.RateCheck> rateChecksFromEventLoop(String tenant, int n) throws Exception {
        List<CompletableFuture<ApiQuotaGuard.RateCheck>> pending = onEventLoop(() -> {
            List<CompletableFuture<ApiQuotaGuard.RateCheck>> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(guard.checkRateAsync(tenant, owner, apiKeyId)
                    .invoke(r -> assertThat(Context.isOnEventLoopThread()).isTrue())
                    .subscribeAsCompletionStage());
            }
            return out;
        });
        List<ApiQuotaGuard.RateCheck> results = new ArrayList<>();
        for (CompletableFuture<ApiQuotaGuard.RateCheck> f : pending) {
            results.add(f.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }
        return results;
    }

    private long allowedCount(List<ApiQuotaGuard.RateCheck> results) {
        return results.stream().filter(ApiQuotaGuard.RateCheck::allowed).count();
    }

    @Test
    void perSecondRateLimit_enforcedOnEventLoop_andCountedInRedis() throws Exception {
        long fixedMillis = System.currentTimeMillis();
        rateLimiter.clockMillis = () -> fixedMillis;

        List<ApiQuotaGuard.RateCheck> results = rateChecksFromEventLoop(TENANT, 15);

        assertThat(allowedCount(results)).isEqualTo(10);
        assertThat(results).allMatch(r -> r.limit() == 10);
        String windowKey = "aq:rl:" + apiKeyId + ":" + (fixedMillis / 1000);
        assertThat(redis.value(String.class).get(windowKey)).isEqualTo("15");
        assertThat(redis.key(String.class).ttl(windowKey)).isBetween(0L, 2L);
    }

    @Test
    void enterprise_tenantPlanCacheMiss_usesOwnerSnapshotPlan_notDefaultTen() throws Exception {
        long fixedMillis = System.currentTimeMillis();
        rateLimiter.clockMillis = () -> fixedMillis;
        String coldTenant = "cold-" + UUID.randomUUID();

        // 冷启动：租户 plan 与 owner 快照都未知 → 本次放行（不计数），而不是按兜底 10 RPS 限流
        List<ApiQuotaGuard.RateCheck> cold = rateChecksFromEventLoop(coldTenant, 20);
        assertThat(allowedCount(cold)).isEqualTo(20);
        assertThat(cold).allMatch(r -> r.limit() == 0);

        // 租户缓存仍未命中（如过期窗口），但 owner 快照已加载为 enterprise → 按 200 RPS
        snapshot.setUser(new UserSnapshot(owner, "enterprise", -1L, "active", null, null));
        await().atMost(WAIT).until(() -> checkOnEventLoop(coldTenant).limit() == -1L);
        List<ApiQuotaGuard.RateCheck> warm = rateChecksFromEventLoop(coldTenant, 30);
        assertThat(allowedCount(warm)).isEqualTo(30);
        assertThat(warm).allMatch(r -> r.limit() == 200);
    }

    @Test
    void free_isForbiddenByMonthlyQuota_evenWhileRatePlanUnknown() throws Exception {
        String coldTenant = "cold-free-" + UUID.randomUUID();
        snapshot.setUser(new UserSnapshot(owner, "free", 0L, "active", null, null));

        await().atMost(WAIT).until(() -> checkOnEventLoop(coldTenant).verdict() == ApiQuotaGuard.Verdict.FORBIDDEN);
        // 租户 plan 未知时 rate 不做裁决（fail-open），拦截由月度配额 403 承担
        tenantPlans.remove(coldTenant);
        assertThat(checkOnEventLoop(coldTenant).verdict()).isEqualTo(ApiQuotaGuard.Verdict.FORBIDDEN);
        // 租户 plan 一旦已知为 free，rate 也直接拒绝且不触达 Redis 计数
        tenantPlans.put(coldTenant, new PlanInfo("free", null, false, 0, 0L, 0L, 0));
        assertThat(rateChecksFromEventLoop(coldTenant, 3)).noneMatch(ApiQuotaGuard.RateCheck::allowed);
    }

    @Test
    void hardCap_concurrentInFlightOvershoot_isBoundedByInFlightCount() throws Exception {
        snapshot.setUser(new UserSnapshot(owner, "pro", 5L, "active", null, null));
        await().atMost(WAIT).until(() -> checkOnEventLoop().limit() == 5L);
        recordSuccesses(9);
        await().atMost(WAIT).until(() -> checkOnEventLoop().used() == 9L);

        // 最坏交错：20 个请求在任何一个 INCR 回包之前都已通过 check（各自看到 9 < 10）
        int inFlight = 20;
        int admitted = onEventLoop(() -> {
            int ok = 0;
            for (int i = 0; i < inFlight; i++) {
                if (guard.check(TENANT, owner).allowed()) ok++;
            }
            for (int i = 0; i < ok; i++) {
                guard.recordAsync(owner, owner, TENANT, apiKeyId, PATH, "success", 1L);
            }
            return ok;
        });
        long finalCount = 9L + admitted;
        await().atMost(WAIT).until(() -> snapshot.getCounter(owner) == finalCount);

        // 超出 200%（=10）的部分不超过在途请求数；之后的 check 立即 429
        assertThat(finalCount - 10L).isLessThanOrEqualTo(inFlight);
        await().atMost(WAIT).until(() -> checkOnEventLoop().verdict() == ApiQuotaGuard.Verdict.RATE_LIMITED);
    }

    @Test
    void eventLoopIsNeverBlocked_microBenchmark() throws Exception {
        snapshot.setUser(new UserSnapshot(owner, "pro", 1_000_000L, "active", null, null));
        await().atMost(WAIT).until(() -> checkOnEventLoop().limit() == 1_000_000L);

        int iterations = 20_000;
        long checkNanos = onEventLoop(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                guard.check(TENANT, owner);
            }
            return System.nanoTime() - start;
        });

        // 限流发起只组装 + 订阅（INCR 由 Redis 客户端异步发送），event-loop 不等待回包
        int rateCalls = 100;
        long[] issueNanos = new long[1];
        List<CompletableFuture<ApiQuotaGuard.RateCheck>> pending = onEventLoop(() -> {
            long start = System.nanoTime();
            List<CompletableFuture<ApiQuotaGuard.RateCheck>> out = new ArrayList<>();
            for (int i = 0; i < rateCalls; i++) {
                out.add(guard.checkRateAsync(TENANT, owner, apiKeyId).subscribeAsCompletionStage());
            }
            issueNanos[0] = System.nanoTime() - start;
            return out;
        });
        for (CompletableFuture<ApiQuotaGuard.RateCheck> f : pending) {
            f.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        long checkMillis = TimeUnit.NANOSECONDS.toMillis(checkNanos);
        long issueMillis = TimeUnit.NANOSECONDS.toMillis(issueNanos[0]);
        System.out.printf("[#393 micro-benchmark] check: %d x %.2f us/op (%d ms); checkRateAsync issue: %d x %.2f us/op (%d ms)%n",
            iterations, checkNanos / 1000.0 / iterations, checkMillis,
            rateCalls, issueNanos[0] / 1000.0 / rateCalls, issueMillis);
        // 旧实现单次 checkRate 就要在 event-loop 上等 ~100ms 的 cloud 出站调用
        assertThat(checkMillis).isLessThan(1_000L);
        assertThat(issueMillis).isLessThan(200L);
    }
}
