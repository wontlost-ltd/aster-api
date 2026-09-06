package io.aster.policy.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流的<b>跨副本共享上限</b>（issue #302 H2）。
 *
 * <p>★被修的缺陷：{@link RateLimiter} 的滑动窗口是<b>进程内</b>结构
 * （{@code ConcurrentHashMap}）。{@code aster-api} 跑 4 副本时，
 * 每个副本各自放行 {@code maxRequests} 个，<b>实际生效上限是配置值的 4 倍</b>——
 * 配 100/分钟实际能过 400/分钟，限流形同虚设。
 *
 * <p>★<b>怎么模拟多副本</b>：每个副本有自己独立的进程内状态，
 * 但共享同一个 Redis。故用<b>多个 RateLimiter 实例</b>来代表多个副本——
 * 它们各有各的 {@code windows} map（正是"per-副本"的本质），
 * 却指向同一个 Redis。若共享计数没生效，N 个实例就能放行 N×max 个。
 */
@QuarkusTest
class RateLimiterSharedCounterIT {

    @Inject
    jakarta.enterprise.inject.Instance<io.quarkus.redis.datasource.RedisDataSource> redis;

    /** 造一个「独立副本」：自己的进程内窗口 + 共享的 Redis。 */
    private RateLimiter newReplica() {
        RateLimiter r = new RateLimiter();
        r.enabled = true;
        r.sharedEnabled = true;
        r.maxBoundedEntries = 50_000;
        r.redisDataSource = redis;
        return r;
    }

    /**
     * ★核心断言：4 个「副本」合计放行数不得超过配置上限。
     *
     * <p>缺陷版下每个副本各放行 max 个，合计 4×max。
     */
    @Test
    @DisplayName("多副本合计放行数不得超过配置上限")
    void replicasShareTheSameQuota() {
        int max = 10;
        int replicas = 4;
        Duration window = Duration.ofSeconds(60);
        String id = "rest:tenant-" + UUID.randomUUID();

        int granted = 0;
        for (int i = 0; i < replicas; i++) {
            RateLimiter replica = newReplica();
            // 每个副本都尝试用满自己的本地配额
            for (int n = 0; n < max; n++) {
                if (replica.tryAcquire(id, max, window)) {
                    granted++;
                }
            }
        }

        assertThat(granted)
            .as("★%d 个副本各自尝试 %d 次，合计放行必须 ≤ %d（全局上限）。"
                    + "实际放行 %d —— 等于 %d 倍配置值说明每个副本各算各的，"
                    + "限流在多副本下形同虚设（issue #302 H2）",
                replicas, max, max, granted, granted / max)
            .isLessThanOrEqualTo(max);
    }

    /**
     * 反向守卫：上限之内必须<b>放行</b>，不能一律拒绝。
     *
     * <p>没有这条，「永远返回 false」也能让上面那条变绿。
     */
    @Test
    @DisplayName("配额之内必须放行（守卫：不能一律拒绝）")
    void withinQuotaMustBeGranted() {
        int max = 10;
        String id = "rest:tenant-" + UUID.randomUUID();
        RateLimiter replica = newReplica();

        int granted = 0;
        for (int n = 0; n < max; n++) {
            if (replica.tryAcquire(id, max, Duration.ofSeconds(60))) {
                granted++;
            }
        }

        assertThat(granted)
            .as("★配额之内的请求必须全部放行——一律拒绝会让上一条断言也变绿，"
                + "但那是把服务打死而不是限流")
            .isEqualTo(max);
    }

    /**
     * ★窗口切换后配额必须重置：否则 key 永不过期，租户被永久封禁。
     *
     * <p>用 1 秒窗口实测跨窗口行为——这条守的是「首次才设 TTL」那段逻辑：
     * 若每次 INCR 都重设 TTL，持续受压的 key 会被无限续期，配额再也不重置。
     */
    @Test
    @DisplayName("窗口切换后配额必须重置")
    void quotaResetsAfterWindowRollover() throws Exception {
        int max = 3;
        Duration window = Duration.ofSeconds(1);
        String id = "rest:tenant-" + UUID.randomUUID();
        RateLimiter replica = newReplica();

        for (int n = 0; n < max; n++) {
            replica.tryAcquire(id, max, window);
        }
        assertThat(replica.tryAcquire(id, max, window))
            .as("用满配额后应被拒")
            .isFalse();

        // 跨过窗口边界（窗口 key 含 epochSecond/windowSeconds，切窗即换 key）
        Thread.sleep(2200);

        // 换一个「副本」来试，确保拿到的是共享侧的新窗口而非本地残留
        assertThat(newReplica().tryAcquire(id, max, window))
            .as("★新窗口必须重新放行——不重置意味着租户被永久封禁")
            .isTrue();
    }
}
