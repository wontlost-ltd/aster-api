package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每秒限流（#393）：plan RPS 表与 cloud api-rate-limiter.ts 一致；单副本内存窗口立即裁决，
 * Redis 回包的集群计数一旦达到上限即在本副本生效。
 *
 * <p>纯 JUnit：Redis INCR 打桩为同步完成的 Uni，可精确控制「集群计数」；时钟固定，避免秒边界抖动。
 */
class ApiKeyRateLimiterTest {

    private ApiKeyRateLimiter limiter;
    private LocalQuotaSnapshotService snapshot;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    /** 模拟其他副本已贡献的集群计数，INCR 回包 = 该值 + 本副本累计。 */
    private final AtomicLong clusterCount = new AtomicLong();

    @BeforeEach
    void setUp() {
        snapshot = mock(LocalQuotaSnapshotService.class);
        when(snapshot.incrementRateWindow(anyString(), anyLong()))
            .thenAnswer(inv -> Uni.createFrom().item(clusterCount.incrementAndGet()));
        limiter = new ApiKeyRateLimiter();
        limiter.snapshot = snapshot;
        limiter.clockMillis = clock::get;
    }

    private int admitted(String key, String plan, int attempts) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(key, plan).allowed()) ok++;
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
        assertThat(ApiKeyRateLimiter.limitFor(null)).isEqualTo(10);
    }

    @Test
    void pro_admitsTenPerSecond_thenRejects() {
        assertThat(admitted("k-pro", "pro", 25)).isEqualTo(10);
        ApiQuotaGuard.RateCheck denied = limiter.tryAcquire("k-pro", "pro");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).isEqualTo(10);
    }

    @Test
    void teamAndEnterprise_useTheirOwnLimits() {
        assertThat(admitted("k-team", "team", 80)).isEqualTo(50);
        clusterCount.set(0);
        assertThat(admitted("k-ent", "enterprise", 300)).isEqualTo(200);
    }

    @Test
    void free_isAlwaysRejected_withoutTouchingRedis() {
        ApiQuotaGuard.RateCheck r = limiter.tryAcquire("k-free", "free");
        assertThat(r.allowed()).isFalse();
        assertThat(r.limit()).isZero();
        verify(snapshot, never()).incrementRateWindow(anyString(), anyLong());
    }

    @Test
    void clusterCountFromRedis_rejectsBeforeLocalWindowIsFull() {
        // 其他副本本秒已用掉 8 个：本副本只能再放 2 个
        clusterCount.set(8);
        assertThat(admitted("k-shared", "pro", 10)).isEqualTo(2);
    }

    @Test
    void newSecond_resetsWindow() {
        assertThat(admitted("k-roll", "pro", 12)).isEqualTo(10);
        clock.addAndGet(1000);
        clusterCount.set(0);
        assertThat(admitted("k-roll", "pro", 12)).isEqualTo(10);
    }

    @Test
    void keysAreIsolated() {
        assertThat(admitted("k-a", "pro", 10)).isEqualTo(10);
        clusterCount.set(0);
        assertThat(limiter.tryAcquire("k-b", "pro").allowed()).isTrue();
    }

    @Test
    void redisUnavailable_degradesToLocalWindow() {
        // Redis 故障时 snapshot 恢复为 0：仍按单副本上限限流，而不是放开
        when(snapshot.incrementRateWindow(anyString(), anyLong())).thenReturn(Uni.createFrom().item(0L));
        assertThat(admitted("k-down", "pro", 15)).isEqualTo(10);
    }
}
