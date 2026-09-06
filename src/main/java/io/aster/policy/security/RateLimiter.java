package io.aster.policy.security;

import io.quarkus.scheduler.Scheduled;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于滑动窗口的限流器
 *
 * 使用 ConcurrentHashMap + ConcurrentLinkedDeque 实现线程安全的滑动窗口计数。
 * 过期条目通过惰性清理机制移除，避免后台线程开销。
 */
@ApplicationScoped
public class RateLimiter {

    private static final Logger LOG = Logger.getLogger(RateLimiter.class);

    /**
     * 滑动窗口数据：key → 请求时间戳队列
     */
    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /**
     * 并发连接计数器：key → 当前活跃连接数
     */
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounters = new ConcurrentHashMap<>();

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * 是否启用跨副本共享计数（issue #302 H2）。
     *
     * <p>默认开启：不开的话多副本下实际上限是配置值的副本数倍。
     * 留开关是为了单副本部署或 Redis 故障演练时能一键退回纯本地行为。
     */
    @ConfigProperty(name = "aster.ratelimit.shared.enabled", defaultValue = "true")
    boolean sharedEnabled;

    /** Redis 可选：未配置时 {@code isResolvable()} 为 false，自动退回纯本地限流。 */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<io.quarkus.redis.datasource.RedisDataSource> redisDataSource;

    /**
     * R30+ audit P1：DoS 攻击下 high-cardinality identifier（如轮换的 IP）
     * 会在 5 分钟的 idle eviction 窗口内让 windows/connectionCounters
     * 单调增长。给一个硬上限：当 map 大小越过门槛时立刻触发一次 evict，
     * 把窗口从 5min 缩到「下一次 acquire/release」。
     *
     * 50k 条目对应 ~50k * 24bytes = 1.2MB 常驻，足以让正常 tenant 自由
     * 使用，又能在攻击场景下把内存占用钉在 ~2MB 量级。门槛可以通过
     * ConfigProperty 调，默认值与 trial guard 的成本控制窗口对齐。
     */
    @ConfigProperty(name = "aster.ratelimit.bounded.max-entries", defaultValue = "50000")
    int maxBoundedEntries;

    /**
     * 尝试获取一个请求许可
     *
     * @param identifier  限流标识（如租户ID、IP地址）
     * @param maxRequests 窗口内最大请求数
     * @param window      滑动窗口时长
     * @return true 如果请求被允许
     */
    public boolean tryAcquire(String identifier, int maxRequests, Duration window) {
        if (!enabled) {
            return true;
        }

        // ★跨副本共享计数（issue #302 H2）。本类的 windows/connectionCounters 都是
        //   **进程内**结构：aster-api 跑 4 副本时，每个副本各自放行 maxRequests 个，
        //   实际生效上限是配置值的 4 倍——限流形同虚设。
        //
        //   ★分层而非替换：Redis 层先判，判过再走本地窗口。
        //   - Redis 可用 → 全局上限由它把关，本地窗口作为第二道（单副本内的突发）
        //   - Redis 不可用 → 退回纯本地（fail-open 到「旧行为」而非「不限流」）
        //   本地这套滑动窗口经过多轮加固（见下方 R-Round-3 / R31-5 注释），
        //   不能为了上 Redis 就把它拆掉。
        if (!sharedTryAcquire(identifier, maxRequests, window)) {
            return false;
        }

        // R-Round-3 关键修复：整个 "evict + check + addLast" 事务搬进
        // ConcurrentHashMap.compute()，与 evictIdleEntries() 共享同一把
        // per-bucket 锁。这是唯一能消除 acquire ↔ evict 竞争的姿态：
        //
        // 错误姿态（round-2）：
        //   Deque q = windows.computeIfAbsent(id, ...);
        //   // bucket 锁已释放 ↓
        //   synchronized (q) { evict + check + addLast }
        //   ↑ evictIdleEntries.compute(id, ...) 在这一窗口里可以删掉 q，
        //   后续的 addLast 把数据写进游离 deque，配额事实上被重置。
        //
        // 正确姿态（round-3）：
        //   windows.compute(id, (k, q) -> {
        //     evict + check + addLast/skip
        //     return q.isEmpty() ? null : q;
        //   });
        //   ↑ 整个事务全在 bucket 锁内；evictIdleEntries 同样走 compute()，
        //   两条路径互斥串行。
        //
        // 通过/拒绝的返回值用 boolean[1] holder 传出 compute()。
        Instant now = Instant.now();
        Instant windowStart = now.minus(window);
        // 用 boolean[1] 而非 AtomicBoolean：compute lambda 是同步执行的（被
        // bucket 锁保护），不需要 volatile / 原子语义；写完 compute 返回后
        // 当前线程读 holder[0] 已发生在 lambda 写入之后（happens-before
        // 通过 ConcurrentHashMap 的内部同步保证），所以普通数组单元已经够用。
        // 硬上限 fail-closed（仅 IP/匿名 bucket）必须在 maybeEagerEvict **之前**：
        // 否则一个轮换的伪造新 IP 会先触发 active-drop 淘汰**真实用户**的活跃窗口，
        // 才轮到拒绝这个新 IP——相当于攻击者用新 IP 重置了别人的限流窗口。
        // 先在门口拒掉新 IP bucket，就既挡住了 high-cardinality 增长，又不动既有
        // entry。已认证租户 bucket（不含 :ip:）与已在册 IP 不受此分支影响。
        if (isIpBucket(identifier)
                && maxBoundedEntries > 0
                && windows.size() >= maxBoundedEntries
                && !windows.containsKey(identifier)) {
            LOG.warnf("限流拒绝（windows map 达硬上限 %d，拒绝新 IP/匿名 key）: %s",
                maxBoundedEntries, identifier);
            return false;
        }

        // DoS 防御 —— 写之前先检查 map 是否越过硬上限，越过就 evict 一次（先清
        // 过期，再按需 active-drop 最旧的）。compute() 持 bucket 锁，全 map 扫描
        // 放外面避免与 evictIdleEntries 抢锁。IP 洪泛已被上面 fail-closed 挡住，
        // 这里主要服务于合法租户/IP churn 的内存回收。
        maybeEagerEvict(windowStart);

        final boolean[] granted = { false };
        windows.compute(identifier, (k, existing) -> {
            Deque<Instant> q = existing != null ? existing : new ConcurrentLinkedDeque<>();
            evictExpired(q, windowStart);
            if (q.size() >= maxRequests) {
                LOG.debugf("限流拒绝: identifier=%s, current=%d, max=%d",
                    k, q.size(), maxRequests);
                granted[0] = false;
                // 拒绝路径：保留 q 现状；不释放也不收缩，下次到来按窗口正常 evict。
                // 如果 q 实际已空（理论上 size == max 且 max == 0 才会，几乎不会发生），
                // 返回 null 把 entry 从 map 里清出去节省内存。
                return q.isEmpty() ? null : q;
            }
            q.addLast(now);
            granted[0] = true;
            return q;
        });
        return granted[0];
    }

    /**
     * 跨副本共享的固定窗口计数（issue #302 H2）。
     *
     * <p>用 Redis 的 {@code INCR} + 首次设 {@code TTL} 实现固定窗口：
     * key 里带上窗口序号（{@code epochSecond / windowSeconds}），
     * 窗口切换时 key 自然更换，旧 key 由 TTL 自行消失，无需清理任务。
     *
     * <p>★<b>为什么是固定窗口而不是滑动窗口</b>：滑动窗口要在 Redis 上维护
     * 有序集合并逐次裁剪（{@code ZADD}+{@code ZREMRANGEBYSCORE}+{@code ZCARD}），
     * 每次请求多次往返且需要 Lua 保证原子。固定窗口只要一次 {@code INCR}。
     *
     * <p>★<b>已知代价：窗口边界最坏放行 2×maxRequests</b>（跨相邻两窗口的 1 秒内）。
     * 这是固定窗口的固有性质，<b>本地那层滑动窗口挡不住它</b>——
     * 负载经 LB 均分到 4 个副本时，每个副本的本地窗口只用掉 max/4、远未打满，
     * 对边界突发零贡献。实测（max=20、4 副本均分）跨边界合计放行 40 = 2.0x。
     * 相比修复前的 <b>4x 且持续</b>（每副本各算各的），2x 且仅限边界是可接受的收敛；
     * 要彻底消除得上 Lua 滑动窗口，那是另一笔工程。
     *
     * <p>★<b>fail-open 到「旧行为」</b>：Redis 不可用时返回 true，
     * 让请求落到本地窗口去判。这不是「不限流」——是退回本次修复之前的状态。
     * 反之若 fail-closed，一次 Redis 抖动就会把全站请求打成 429。
     * ★这条依赖 {@code quarkus.redis.timeout} 足够小（见 application.properties，
     * 已显式设为 100ms）：默认 10s 的话，「降级」会先让每个请求阻塞 10 秒。
     *
     * @return true 表示全局配额尚有余量（或 Redis 不可用）
     */
    private boolean sharedTryAcquire(String identifier, int maxRequests, Duration window) {
        if (!sharedEnabled || redisDataSource == null || !redisDataSource.isResolvable()) {
            return true;
        }
        long windowSeconds = Math.max(1L, window.getSeconds());
        try {
            var commands = redisDataSource.get().value(String.class, Long.class);
            String key = "ratelimit:" + identifier + ":"
                + (Instant.now().getEpochSecond() / windowSeconds);

            long count = commands.incr(key);
            if (count == 1L) {
                // ★首次创建才设 TTL：每次都设会让持续受压的 key 永远不过期
                //   （窗口被无限延长），配额就再也不会重置。
                //   窗口 key 已含序号，故只需覆盖本窗口时长，多给 1s 容忍时钟抖动。
                redisDataSource.get().key(String.class)
                    .expire(key, windowSeconds + 1);
            }
            if (count > maxRequests) {
                LOG.debugf("全局限流拒绝: identifier=%s, count=%d, max=%d",
                    identifier, count, maxRequests);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // Redis 抖动不应把全站打成 429——退回本地窗口判定
            LOG.debugf(e, "共享限流不可用，退回本地窗口: identifier=%s", identifier);
            return true;
        }
    }

    /**
     * 是否为 IP/匿名限流桶（key 含 {@code :ip:}，如 rest:ip:、trial:ip:）。
     * 这类桶来源（客户端 IP）高基数且在 trust-forwarded-headers=false 前可被
     * 伪造，是硬上限 fail-closed 的对象；已认证租户桶（rest:&lt;tenant&gt;）不在内。
     */
    private static boolean isIpBucket(String identifier) {
        return identifier != null && identifier.contains(":ip:");
    }

    /**
     * R30+ audit P1：当 windows 大小越过 maxBoundedEntries 时立刻触发一次
     * 全量 evictExpired。不阻塞调用线程：用 AtomicBoolean 互斥避免并发抢
     * 锁，单线程跑完即可。这是 best-effort 减压，不保证严格上限。
     *
     * <p>正常负载下永远不会进入分支（默认 50k）。攻击场景下让内存增长被
     * 锚定到一个常数，而非任意 IP 数量。
     */
    private final java.util.concurrent.atomic.AtomicBoolean eagerEvictInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void maybeEagerEvict(Instant windowStart) {
        // R31-5：<=0 表示 unbounded（测试 / 老配置缺省）。
        if (maxBoundedEntries <= 0 || windows.size() <= maxBoundedEntries) return;
        if (!eagerEvictInFlight.compareAndSet(false, true)) return;
        try {
            // 第一步：扫一遍把已过期 deque 清掉（不影响活跃 identifier）
            for (String key : windows.keySet()) {
                windows.compute(key, (k, q) -> {
                    if (q == null) return null;
                    evictExpired(q, windowStart);
                    return q.isEmpty() ? null : q;
                });
            }
            // 第二步：如果还是越限，挑"距离过期最远"的 entry 淘汰。
            //
            // R31-5 关键修复：原版只 evictExpired，high-cardinality 攻击
            // 下每个 entry 的 timestamp 都在窗口内 → 没有任何 entry 被清，
            // map 持续增长。现在用 deque 最早 timestamp 排序，把最旧的
            // tail 一次性淘汰到 maxBoundedEntries 以下。
            //
            // 牺牲：被淘汰的 identifier 下次 acquire 会重新计算窗口，等于
            // 重置 rate-limit。攻击场景下这是可接受 trade-off —— 攻击者
            // 仍要付出"被周期性 throttle"成本，但不会让我们 OOM。
            if (windows.size() > maxBoundedEntries) {
                int toDrop = windows.size() - maxBoundedEntries;
                // 按最早 timestamp 排序 (LRU-ish)
                var sorted = new java.util.ArrayList<Map.Entry<String, Instant>>();
                for (var e : windows.entrySet()) {
                    Instant head = e.getValue().peekFirst();
                    if (head != null) sorted.add(Map.entry(e.getKey(), head));
                }
                sorted.sort(Map.Entry.comparingByValue());
                int dropped = 0;
                for (int i = 0; i < sorted.size() && dropped < toDrop; i++) {
                    if (windows.remove(sorted.get(i).getKey()) != null) dropped++;
                }
                LOG.warnf("限流器越限 eager evict：active dropped=%d (剩余 size=%d, bound=%d)",
                    dropped, windows.size(), maxBoundedEntries);
            } else {
                LOG.warnf("限流器触发 eager evict：windows.size 达上限 %d", maxBoundedEntries);
            }
        } finally {
            eagerEvictInFlight.set(false);
        }
    }

    /**
     * R30+ audit follow-up：connectionCounters 的 best-effort 守门，跟
     * {@link #maybeEagerEvict} 用同一份 max-entries 配置。零计数 entry
     * 直接清，正在被持有的连接保留 —— 与 evictIdleEntries 的策略一致。
     */
    private final java.util.concurrent.atomic.AtomicBoolean eagerEvictConnInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void maybeEagerEvictConnections() {
        if (maxBoundedEntries <= 0 || connectionCounters.size() <= maxBoundedEntries) return;
        if (!eagerEvictConnInFlight.compareAndSet(false, true)) return;
        try {
            // 与 evictIdleEntries 同：走 compute() 而非裸 removeIf，避免与
            // acquire 的判定-删除竞争窗口。
            for (String key : connectionCounters.keySet()) {
                connectionCounters.compute(key, (k, counter) ->
                    (counter == null || counter.get() == 0) ? null : counter);
            }
            LOG.warnf("限流器触发 connectionCounters eager evict：达上限 %d", maxBoundedEntries);
        } finally {
            eagerEvictConnInFlight.set(false);
        }
    }

    /**
     * 查询剩余可用请求数
     *
     * @param identifier  限流标识
     * @param maxRequests 窗口内最大请求数
     * @param window      滑动窗口时长
     * @return 剩余可用请求数
     */
    public int remaining(String identifier, int maxRequests, Duration window) {
        if (!enabled) {
            return maxRequests;
        }

        // 同 tryAcquire：用 compute() 跟 evictIdleEntries 共享 bucket 锁，
        // 避免 evict + size 读取被中间穿插的清理改写。Holder 用普通 int[1]
        // 即可——lambda 在 bucket 锁里同步执行，无并发写。
        Instant windowStart = Instant.now().minus(window);
        final int[] out = { maxRequests };
        windows.compute(identifier, (k, q) -> {
            if (q == null) {
                out[0] = maxRequests;
                return null;
            }
            evictExpired(q, windowStart);
            out[0] = Math.max(0, maxRequests - q.size());
            return q.isEmpty() ? null : q;
        });
        return out[0];
    }

    /**
     * 计算限流重置时间（Unix 秒）
     *
     * @param identifier 限流标识
     * @param window     滑动窗口时长
     * @return 最早条目过期时间的 Unix 秒，如果无条目则返回当前时间
     */
    public long resetAtEpochSecond(String identifier, Duration window) {
        long now = Instant.now().getEpochSecond();
        final long[] out = { now };
        windows.compute(identifier, (k, q) -> {
            if (q == null || q.isEmpty()) return q;
            Instant oldest = q.peekFirst();
            if (oldest != null) {
                out[0] = oldest.plus(window).getEpochSecond();
            }
            return q;
        });
        return out[0];
    }

    /**
     * 尝试获取一个连接许可（并发连接限制）
     *
     * @param identifier     连接标识（如 IP）
     * @param maxConnections 最大并发连接数
     * @return true 如果连接被允许
     */
    public boolean tryAcquireConnection(String identifier, int maxConnections) {
        if (!enabled) {
            return true;
        }

        // R30+ audit P3：和 windows 一样，connectionCounters 在 high-cardinality
        // 攻击下会单调增长直到 5 分钟 cron 扫到。复用同一份 best-effort 守门：
        // 越过 maxBoundedEntries 时立刻把所有零计数 entry 清掉，把内存锚在
        // 一个常数上。
        maybeEagerEvictConnections();

        // 硬上限兜底：若清理零计数 entry 后 map 仍越界（说明都是活跃的
        // high-cardinality identifier，如轮换 IP 的长连接攻击），则拒绝**新**
        // identifier，对连接上限 fail-closed。已在册的 identifier 不受影响，
        // 不误伤正常活跃连接。这把连接计数 map 钉在 ~maxBoundedEntries，杜绝
        // 攻击期无界增长。
        if (maxBoundedEntries > 0
                && connectionCounters.size() >= maxBoundedEntries
                && !connectionCounters.containsKey(identifier)) {
            LOG.warnf("连接限流拒绝（map 达硬上限 %d，拒绝新 identifier）: %s",
                maxBoundedEntries, identifier);
            return false;
        }

        // R-audit：check+increment 必须与 evictIdleEntries 的 remove 在同一把
        // per-bucket 锁内串行，否则存在与 windows 同类的游离-counter race：
        //   computeIfAbsent 拿到 AtomicInteger（此刻 count==0）
        //   → 清理线程 removeIf(count==0) 把 entry 从 map 删除
        //   → 这里对已游离的 AtomicInteger 自增成功，但下一次 acquire 会
        //     computeIfAbsent 建一个全新 counter → 并发上限被悄悄重置。
        // 用 connectionCounters.compute() 把"读 + 判满 + 自增"全放进 bucket 锁；
        // 清理改用同样走 compute 语义的 remove(key, zeroValue)（见 evictIdleEntries），
        // 两条路径互斥，消除游离窗口。
        final boolean[] granted = { false };
        connectionCounters.compute(identifier, (k, existing) -> {
            AtomicInteger counter = existing != null ? existing : new AtomicInteger(0);
            int current = counter.get();
            if (current >= maxConnections) {
                LOG.debugf("连接限流拒绝: identifier=%s, current=%d, max=%d", k, current, maxConnections);
                granted[0] = false;
            } else {
                counter.set(current + 1);
                granted[0] = true;
            }
            return counter;
        });
        return granted[0];
    }

    /**
     * 释放一个连接许可
     *
     * @param identifier 连接标识
     */
    public void releaseConnection(String identifier) {
        // 与 tryAcquireConnection / evictIdleEntries 共享 bucket 锁：在 compute
        // 内自减，归零即把 entry 从 map 移除（return null），避免空 counter 堆积，
        // 也消除"自减后被另一线程读到游离 counter"的窗口。
        connectionCounters.compute(identifier, (k, counter) -> {
            if (counter == null) {
                return null;
            }
            int next = Math.max(0, counter.get() - 1);
            if (next == 0) {
                return null;
            }
            counter.set(next);
            return counter;
        });
    }

    /**
     * 获取当前活跃连接数
     *
     * @param identifier 连接标识
     * @return 当前活跃连接数
     */
    public int activeConnections(String identifier) {
        AtomicInteger counter = connectionCounters.get(identifier);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 移除滑动窗口中过期的时间戳
     *
     * 使用 pollFirst() 而非 iterator.remove()，利用 deque 的 FIFO 特性：
     * 最早的时间戳在队首，逐个弹出直到遇到未过期的条目。
     */
    private void evictExpired(Deque<Instant> timestamps, Instant windowStart) {
        while (true) {
            Instant head = timestamps.peekFirst();
            if (head == null || !head.isBefore(windowStart)) {
                break;
            }
            timestamps.pollFirst();
        }
    }

    /**
     * 定期清理已无活动记录的 idle 标识符，防止高基数流量导致内存无界增长。
     * 每 5 分钟执行一次：清除空的时间戳队列和零计数的连接计数器。
     */
    @Scheduled(every = "5m",
               skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    void evictIdleEntries() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        // R-Round-3 关键：清理和 tryAcquire / remaining / resetAtEpochSecond
        // 全部走 windows.compute(key, ...)。ConcurrentHashMap 保证同一 key 上
        // 的 compute() 互斥串行，因此 "tryAcquire 拿到 deque → 还没 addLast"
        // 这个窗口被消除：cleanup 要么在 acquire 之前发生（删空 deque，acquire
        // 重建），要么之后（看到 acquire 已加入的时间戳，不会误判为空）。
        for (String key : windows.keySet()) {
            windows.compute(key, (k, q) -> {
                if (q == null) return null;
                evictExpired(q, cutoff);
                return q.isEmpty() ? null : q;
            });
        }
        // 连接计数清理也走 compute()，与 acquire/release 共享 bucket 锁：只在
        // count 确实为 0 时移除。裸 removeIf 在判定与删除之间存在与 acquire 的
        // 竞争窗口（acquire 刚自增、removeIf 读到旧值误删）；compute 内读到的
        // 一定是最新值，且整段操作原子。
        for (String key : connectionCounters.keySet()) {
            connectionCounters.compute(key, (k, counter) ->
                (counter == null || counter.get() == 0) ? null : counter);
        }
        LOG.debugf("限流器空闲清理完成: windows=%d, connections=%d",
            windows.size(), connectionCounters.size());
    }
}
