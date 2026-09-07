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

    // ── issue #309：窗口边界不得放行 2×max ──────────────────────────────

    /**
     * ★<b>任意 1 秒窗口内放行数不得超过 max</b>（issue #309）。
     *
     * <p>固定窗口的固有缺陷：窗口 N 的末尾放行 max 个、窗口 N+1 的开头再放行
     * max 个，这 2×max 可以挤在<b>不到 1 秒</b>内发生。滑动窗口不看「窗口序号」，
     * 只看「最近 1 秒内已放行几个」，故能堵住。
     *
     * <p>★<b>本用例最容易自我欺骗的地方</b>（issue 里专门记了）：负载必须
     * <b>均分到多个副本</b>。若拿<b>同一批已打满的副本</b>重放，本地滑动窗口
     * 会把第二波挡掉，测出 1.0x 的假象、看起来「边界已被覆盖」。
     * 只有让每个副本的本地窗口都<b>远未打满</b>（各自只用 max/replicas），
     * 才能暴露共享层的真实行为。
     */
    @Test
    @DisplayName("跨窗口边界的 1 秒内，总放行数不得超过 max")
    void boundaryBurstMustNotExceedMax() throws Exception {
        int max = 20;
        int replicas = 4;
        Duration window = Duration.ofSeconds(1);
        String id = "rest:tenant-" + UUID.randomUUID();

        // 对齐到窗口边界前 ~250ms，让两波请求分落在相邻两个固定窗口里
        long nowMs = System.currentTimeMillis();
        long toBoundary = 1000 - (nowMs % 1000);
        Thread.sleep(Math.max(0, toBoundary - 250));

        // 第一波：边界前，负载**均分**到 4 个副本（每个副本只用掉 max/4 = 5，本地远未打满）
        int firstWave = 0;
        for (int i = 0; i < replicas; i++) {
            RateLimiter r = newReplica();
            for (int n = 0; n < max / replicas; n++) {
                if (r.tryAcquire(id, max, window)) {
                    firstWave++;
                }
            }
        }

        // 跨过边界
        Thread.sleep(400);

        // 第二波：边界后，同样均分到 4 个**新**副本（本地窗口全新，挡不住任何东西）
        int secondWave = 0;
        for (int i = 0; i < replicas; i++) {
            RateLimiter r = newReplica();
            for (int n = 0; n < max / replicas; n++) {
                if (r.tryAcquire(id, max, window)) {
                    secondWave++;
                }
            }
        }

        int total = firstWave + secondWave;
        assertThat(total)
            .as("★两波相隔约 0.4 秒（远小于 1 秒窗口），合计放行 %d。"
                    + "固定窗口下二者落在相邻窗口序号里、各自计数，最坏放行 2×max=%d；"
                    + "滑动窗口只看「最近 1 秒放行了几个」，故必须 ≤ %d",
                total, 2 * max, max)
            .isLessThanOrEqualTo(max);
    }

    /**
     * ★<b>窗口内过期的条目必须被裁剪</b>，而不是靠整个 key 过期来「重置」。
     *
     * <p>★这条是补一个我自己造的假绿：既有的「窗口切换后配额必须重置」用例
     * 等了 2200ms，而 key 的 TTL 是 {@code win*2 = 2000ms} —— 整个 key 已经
     * <b>过期消失</b>了，所以「配额重置」是 TTL 干的，不是裁剪干的。
     * 实测：把 Lua 里的 {@code ZREMRANGEBYSCORE} 整行删掉，那条用例<b>照样绿</b>。
     *
     * <p>本用例把窗口拉长到 3 秒（TTL 6 秒），等待 3.5 秒 ——
     * <b>短于 TTL、长于窗口</b>。此时 key 仍然存在，只有真的裁剪掉窗口外的条目
     * 才能重新放行。不裁剪的话配额永不释放，租户被<b>永久封禁</b>。
     */
    @Test
    @DisplayName("窗口外的旧条目必须被裁剪（不能靠整个 key 过期来重置）")
    void staleEntriesAreTrimmedWithinLivingKey() throws Exception {
        int max = 3;
        Duration window = Duration.ofSeconds(3);   // TTL = 6s
        String id = "rest:tenant-" + UUID.randomUUID();
        RateLimiter replica = newReplica();

        for (int n = 0; n < max; n++) {
            assertThat(replica.tryAcquire(id, max, window))
                .as("配额之内应放行")
                .isTrue();
        }
        assertThat(replica.tryAcquire(id, max, window))
            .as("用满后应被拒")
            .isFalse();

        // 3.5s：> 窗口(3s) 但 < TTL(6s) —— key 还活着，只有裁剪能救
        Thread.sleep(3500);

        assertThat(newReplica().tryAcquire(id, max, window))
            .as("★key 尚未过期（TTL 6s），此时能重新放行只可能是因为窗口外的条目"
                + "被真正裁剪了。不裁剪 = 配额永不释放 = 租户被永久封禁")
            .isTrue();
    }

    /**
     * ★<b>并发打同一个 key 时，放行数仍不得超过 max</b>。
     *
     * <p>这条守的是 ZSET <b>成员唯一性</b>：成员若只用毫秒时间戳，
     * 同一毫秒内的多个请求会塌成<b>同一个成员</b>被 {@code ZADD} 覆盖，
     * 窗口内计数偏低 → 放行超额。串行用例撞不出这个（每次调用之间隔着一次
     * Redis 往返），必须并发才能让多个请求真正落在同一毫秒。
     *
     * <p>实测：把成员从 {@code <毫秒>-<实例>-<序号>} 改成只有 {@code <毫秒>}，
     * 串行的几条用例<b>全都照样绿</b>，只有本用例能抓到。
     */
    @Test
    @DisplayName("并发打同一 key 时放行数不得超过 max（成员唯一性）")
    void concurrentBurstRespectsMax() throws Exception {
        int max = 10;
        int threads = 16;
        int perThread = 5;   // 合计尝试 80 次，远超 max
        Duration window = Duration.ofSeconds(10);   // 窗口足够长，排除过期干扰
        String id = "rest:tenant-" + UUID.randomUUID();

        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger granted =
            new java.util.concurrent.atomic.AtomicInteger();

        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 每个线程一个「副本」——各有各的本地窗口，共享同一个 Redis
                RateLimiter replica = newReplica();
                fs.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        if (replica.tryAcquire(id, max, window)) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : fs) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get())
            .as("★%d 线程并发共尝试 %d 次，放行必须 ≤ %d。"
                    + "超出说明同毫秒的请求在 ZSET 里塌成了同一个成员，计数偏低",
                threads, threads * perThread, max)
            .isLessThanOrEqualTo(max);
        assertThat(granted.get())
            .as("★也不能一个都不放——那是另一种坏法")
            .isPositive();
    }

    /**
     * ★反向守卫：滑动窗口不得把<b>正常速率</b>的请求误杀。
     *
     * <p>没有这条，「永远返回 false」或「窗口算得过宽」也能让上一条变绿。
     * 这里让请求以低于配额的速率持续到达，全都必须放行。
     */
    @Test
    @DisplayName("低于配额的持续速率必须全部放行（不得误杀）")
    void steadyRateBelowQuotaIsFullyGranted() throws Exception {
        int max = 10;
        Duration window = Duration.ofSeconds(1);
        String id = "rest:tenant-" + UUID.randomUUID();
        RateLimiter replica = newReplica();

        // 每 150ms 发 1 个，持续 6 个（≈0.9s 内 6 个，远低于 10/秒）
        int granted = 0;
        for (int n = 0; n < 6; n++) {
            if (replica.tryAcquire(id, max, window)) {
                granted++;
            }
            Thread.sleep(150);
        }

        assertThat(granted)
            .as("★低于配额的稳定速率必须全部放行——误杀比放宽更糟，"
                + "且「一律拒绝」也能让边界用例变绿")
            .isEqualTo(6);
    }
}
