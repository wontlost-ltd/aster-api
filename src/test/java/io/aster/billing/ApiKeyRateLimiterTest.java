package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每秒限流（#393 / 复审）：裁决来自 Redis 原子 INCR 的回包，多副本并发、回包延迟时集群放行总数
 * 仍不超过上限；Redis 异步失败 / 超时退化为按副本均分的本地窗口，恢复后回到集群裁决。
 *
 * <p>「Redis」由 {@link FakeRedis} 模拟：INCR 在订阅（命令到达）时原子执行，回包延迟后才在
 * 另一线程发射——与真实网络往返同序，能暴露「先放行、后纠正」类实现的竞态。
 */
class ApiKeyRateLimiterTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Duration REPLY_DELAY = Duration.ofMillis(20);

    /** 多个「副本」共享的 Redis 计数；mode 控制健康 / 失败 / 不回包。 */
    static final class FakeRedis {
        enum Mode { HEALTHY, FAILING, HANGING }

        final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        volatile Mode mode = Mode.HEALTHY;

        Uni<Long> incr(String apiKeyId, long second) {
            return switch (mode) {
                case HEALTHY -> Uni.createFrom()
                    .item(() -> counters.computeIfAbsent(apiKeyId + ":" + second, k -> new AtomicLong()).incrementAndGet())
                    .onItem().delayIt().by(REPLY_DELAY);
                case FAILING -> Uni.createFrom().<Long>failure(new RuntimeException("connection reset"))
                    .onFailure().call(() -> Uni.createFrom().voidItem().onItem().delayIt().by(REPLY_DELAY));
                case HANGING -> Uni.createFrom().nothing();
            };
        }
    }

    private FakeRedis redis;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
    }

    /** 一个「副本」：独立的限流器实例（独立本地兜底窗口），共享同一个 Redis。 */
    private ApiKeyRateLimiter pod(int fallbackReplicas) {
        LocalQuotaSnapshotService snapshot = mock(LocalQuotaSnapshotService.class);
        when(snapshot.incrementRateWindow(anyString(), anyLong()))
            .thenAnswer(inv -> redis.incr(inv.getArgument(0), inv.getArgument(1)));
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter();
        limiter.snapshot = snapshot;
        limiter.clockMillis = () -> NOW;
        limiter.redisTimeout = Duration.ofMillis(100);
        limiter.fallbackReplicas = fallbackReplicas;
        return limiter;
    }

    /** 各副本同时发起 attemptsPerPod 个请求（全部先订阅，再统一等回包），返回集群放行总数。 */
    private int clusterAdmitted(List<ApiKeyRateLimiter> pods, String key, String plan, int attemptsPerPod)
            throws Exception {
        List<CompletableFuture<ApiQuotaGuard.RateCheck>> inFlight = new ArrayList<>();
        for (int i = 0; i < attemptsPerPod; i++) {
            for (ApiKeyRateLimiter p : pods) {
                inFlight.add(p.acquire(key, plan).subscribeAsCompletionStage());
            }
        }
        int ok = 0;
        for (CompletableFuture<ApiQuotaGuard.RateCheck> f : inFlight) {
            if (f.get(5, TimeUnit.SECONDS).allowed()) ok++;
        }
        return ok;
    }

    @Test
    void planLimits_matchCloudTable() {
        assertThat(ApiKeyRateLimiter.limitFor("free")).isZero();
        assertThat(ApiKeyRateLimiter.limitFor("trial")).isEqualTo(10);
        assertThat(ApiKeyRateLimiter.limitFor("pro")).isEqualTo(10);
        assertThat(ApiKeyRateLimiter.limitFor("team")).isEqualTo(50);
        assertThat(ApiKeyRateLimiter.limitFor("enterprise")).isEqualTo(200);
        assertThat(ApiKeyRateLimiter.limitFor("ENTERPRISE")).isEqualTo(200);
        assertThat(ApiKeyRateLimiter.limitFor("unknown")).isEqualTo(10);
    }

    @Test
    void twoPods_delayedRedisReplies_clusterTotalNeverExceedsLimit() throws Exception {
        // 两个副本各打 30 个并发请求，回包延迟 20ms：旧实现会各自先放行 10 个（集群 20）
        int admitted = clusterAdmitted(List.of(pod(1), pod(1)), "k-pro", "pro", 30);

        assertThat(admitted).isEqualTo(10);
        assertThat(redis.counters.get("k-pro:" + NOW / 1000).get()).isEqualTo(60L);
    }

    @Test
    void teamAndEnterprise_useTheirOwnLimitsAcrossPods() throws Exception {
        List<ApiKeyRateLimiter> pods = List.of(pod(1), pod(1), pod(1));
        assertThat(clusterAdmitted(pods, "k-team", "team", 30)).isEqualTo(50);
        assertThat(clusterAdmitted(pods, "k-ent", "enterprise", 100)).isEqualTo(200);
    }

    @Test
    void free_isRejectedWithoutTouchingRedis() {
        ApiKeyRateLimiter p = pod(1);
        ApiQuotaGuard.RateCheck r = p.acquire("k-free", "free").await().indefinitely();
        assertThat(r.allowed()).isFalse();
        assertThat(r.limit()).isZero();
        verify(p.snapshot, never()).incrementRateWindow(anyString(), anyLong());
    }

    @Test
    void newSecond_opensNewWindow() throws Exception {
        ApiKeyRateLimiter p = pod(1);
        AtomicLong clock = new AtomicLong(NOW);
        p.clockMillis = clock::get;
        assertThat(clusterAdmitted(List.of(p), "k-roll", "pro", 12)).isEqualTo(10);
        clock.addAndGet(1000);
        assertThat(clusterAdmitted(List.of(p), "k-roll", "pro", 12)).isEqualTo(10);
    }

    @Test
    void asyncRedisFailure_fallsBackToPerReplicaShare() throws Exception {
        redis.mode = FakeRedis.Mode.FAILING;
        // 配置了 2 个副本：每副本只放 ceil(10/2)=5，集群总量仍不超过上限
        assertThat(clusterAdmitted(List.of(pod(2), pod(2)), "k-fail", "pro", 20)).isEqualTo(10);
    }

    @Test
    void asyncRedisFailure_withoutReplicaHint_isBoundedByPodsTimesLimit() throws Exception {
        redis.mode = FakeRedis.Mode.FAILING;
        // 未配置副本数（默认 1）：降级期间的已知上界是「副本数 × limit」，见类注释
        assertThat(clusterAdmitted(List.of(pod(1), pod(1)), "k-fail-n", "pro", 20)).isEqualTo(20);
    }

    @Test
    void redisTimeout_fallsBackWithinTimeout() {
        redis.mode = FakeRedis.Mode.HANGING;
        ApiKeyRateLimiter p = pod(1);
        p.redisTimeout = Duration.ofMillis(30);

        long start = System.nanoTime();
        ApiQuotaGuard.RateCheck r = p.acquire("k-hang", "pro").await().atMost(Duration.ofSeconds(2));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(r.allowed()).isTrue();
        assertThat(r.limit()).isEqualTo(10);
        assertThat(waitedMs).isLessThan(1_000L);
    }

    @Test
    void redisRecovery_returnsToClusterDecision() throws Exception {
        ApiKeyRateLimiter p = pod(1);
        redis.mode = FakeRedis.Mode.FAILING;
        assertThat(clusterAdmitted(List.of(p), "k-rec", "pro", 3)).isEqualTo(3);
        assertThat(redis.counters).doesNotContainKey("k-rec:" + NOW / 1000);

        redis.mode = FakeRedis.Mode.HEALTHY;
        assertThat(clusterAdmitted(List.of(p), "k-rec", "pro", 15)).isEqualTo(10);
        assertThat(redis.counters.get("k-rec:" + NOW / 1000).get()).isEqualTo(15L);
    }
}
