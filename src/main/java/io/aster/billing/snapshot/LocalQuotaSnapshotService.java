package io.aster.billing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.common.JacksonMappers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地配额 snapshot 服务（aster-api Redis 后端）
 *
 * 角色：
 *   - evaluate hot path 的真源（cloud 不可达时仍准确）
 *   - 通过 cloud webhook 推送 + 启动 warm-up + 1h cron 对账保持新鲜
 *   - 配额计数 INCR 本地，同时双写到 cloud（SNAP-7）
 *
 * Redis key 设计：
 *   user:{userId}                       → JSON UserSnapshot   (TTL 1h)
 *   apikey:{keyHash}                    → JSON ApiKeySnapshot (TTL 1h)
 *   counter:user:{userId}:m:{period}    → INT  (无 TTL，月切时手动清)
 *
 *   rl:{apiKeyId}:{epochSecond}        → INT  (TTL 2s，每秒限流固定窗口)
 *
 * 命名空间前缀 `aq:` 避免与既有 PolicyCacheManager 等冲突。
 *
 * <p>线程模型（#393）：evaluate 热路径跑在 Vert.x event-loop 上，阻塞式 Redis 客户端在那里
 * 直接抛 "The current thread cannot be blocked"。因此热路径只用三个非阻塞入口：
 * <ul>
 *   <li>{@link #peekOwner}：纯内存读取进程内配额视图；未加载时触发一次异步 MGET 并立即返回空；</li>
 *   <li>{@link #incrementCounter}：响应式 INCR，回包的全局计数同步回写进程内视图；</li>
 *   <li>{@link #incrementRateWindow}：响应式 INCR 每秒窗口计数，回包即 ApiKeyRateLimiter 的集群裁决依据。</li>
 * </ul>
 * 其余阻塞方法（set / invalidate / getApiKey / reset）只允许在 worker 线程调用：
 * {@code @Blocking} 的推送端点、定时对账、verifyPool / precheckPool 均满足。
 */
@ApplicationScoped
public class LocalQuotaSnapshotService {

    private static final Logger LOG = Logger.getLogger(LocalQuotaSnapshotService.class);
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(1);
    private static final String NS = "aq:";
    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    /**
     * 进程内配额视图刷新间隔：其他副本的计数增长与 cloud 推送的套餐变更最迟在此间隔后可见。
     * 本副本自己的 INCR 回包即时回写，不受此间隔限制。
     */
    static final Duration OWNER_REFRESH = Duration.ofSeconds(30);
    private static final int OWNER_CACHE_MAX = 10_000;
    /** 每秒限流窗口 key 的存活期：多留 1s 防止窗口边界上的 INCR 落到已过期 key。 */
    private static final long RATE_WINDOW_TTL_SECONDS = 2;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    Instance<ReactiveRedisDataSource> reactiveRedis;

    @Inject
    Instance<MeterRegistry> meterRegistry;

    /** 键含账期：月切后自然落到新条目，旧账期的计数不会通过 max 合并"带入"新月。 */
    record OwnerKey(String ownerId, String period) {}

    /** 进程内可变状态：快照随刷新整体替换，计数只增（同账期内取最大值合并）。 */
    record OwnerState(UserSnapshot user, AtomicLong used) {
        LocalQuota view() {
            return new LocalQuota(user, used.get());
        }
    }

    /** 热路径读取到的不可变配额视图。 */
    public record LocalQuota(UserSnapshot user, long used) {}

    private final AsyncLoadingCache<OwnerKey, OwnerState> owners = Caffeine.newBuilder()
        .maximumSize(OWNER_CACHE_MAX)
        .refreshAfterWrite(OWNER_REFRESH)
        .expireAfterAccess(SNAPSHOT_TTL)
        .buildAsync(new OwnerLoader());

    private boolean redisAvailable() {
        return redisDataSource.isResolvable() && !redisDataSource.isUnsatisfied();
    }

    /** 热路径每请求都会经过：Redis 客户端与计数器在启动时解析一次，避免每次走 CDI 动态查找。 */
    private ReactiveRedisDataSource reactive;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (reactiveRedis.isResolvable() && !reactiveRedis.isUnsatisfied()) {
            reactive = reactiveRedis.get();
        }
    }

    private boolean reactiveAvailable() {
        return reactive != null;
    }

    private ValueCommands<String, String> strings() {
        return redisDataSource.get().value(String.class);
    }

    private KeyCommands<String> keys() {
        return redisDataSource.get().key(String.class);
    }

    // ============================================================
    // UserSnapshot
    // ============================================================

    /**
     * 非阻塞读取配额 owner 的本地视图（event-loop 安全）。
     *
     * <p>只有「已从 Redis 加载完成且存在 UserSnapshot」才返回值；首次访问 / 加载中 / Redis 中
     * 无快照 / 加载失败都返回空，由调用方走既有的 precheck 降级路径。首次访问会触发一次异步
     * MGET（同 key 并发访问由 Caffeine 合并为一次），下一次请求即命中。
     */
    public Optional<LocalQuota> peekOwner(String ownerId) {
        if (ownerId == null || !reactiveAvailable()) return Optional.empty();
        OwnerState state = doneValue(owners.get(new OwnerKey(ownerId, currentPeriod())));
        boolean hit = state != null && state.user() != null;
        countLookup(hit ? "hit" : state == null ? "loading" : "absent");
        return hit ? Optional.of(state.view()) : Optional.empty();
    }

    public void setUser(UserSnapshot s) {
        if (!redisAvailable() || s == null || s.userId() == null) return;
        try {
            String json = MAPPER.writeValueAsString(s);
            strings().setex(NS + "user:" + s.userId(), SNAPSHOT_TTL.getSeconds(), json);
        } catch (Exception e) {
            LOG.warnf("setUser redis write failed userId=%s: %s", s.userId(), e.getMessage());
        }
        // 本副本立即可见：下一次 peekOwner 重新加载新快照（其他副本按 OWNER_REFRESH 收敛）
        forgetOwner(s.userId());
    }

    public void invalidateUser(String userId) {
        if (!redisAvailable() || userId == null) return;
        try {
            keys().del(NS + "user:" + userId);
        } catch (Exception e) {
            LOG.warnf("invalidateUser failed userId=%s: %s", userId, e.getMessage());
        }
        forgetOwner(userId);
    }

    // ============================================================
    // ApiKeySnapshot
    // ============================================================

    /**
     * 阻塞读取 API key 快照——仅限 worker 线程：调用方 ApiKeyVerifierService 的慢路径
     * 只在 verifyPool 上执行（REST filter 经 runSubscriptionOn，event-loop 直调时同样 offload）。
     */
    public Optional<ApiKeySnapshot> getApiKey(String keyHash) {
        if (!redisAvailable() || keyHash == null) return Optional.empty();
        try {
            String json = strings().get(NS + "apikey:" + keyHash);
            if (json == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(json, ApiKeySnapshot.class));
        } catch (Exception e) {
            LOG.warnf("getApiKey redis read failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public void setApiKey(String keyHash, ApiKeySnapshot s) {
        if (!redisAvailable() || keyHash == null || s == null) return;
        try {
            String json = MAPPER.writeValueAsString(s);
            strings().setex(NS + "apikey:" + keyHash, SNAPSHOT_TTL.getSeconds(), json);
        } catch (Exception e) {
            LOG.warnf("setApiKey redis write failed: %s", e.getMessage());
        }
    }

    public void invalidateApiKeysForUser(String userId) {
        // userId → keyHash 反向索引未持久化；当前实现：让 keyHash 自然 TTL 过期
        // 未来如需精确失效，加 SADD aq:user-keys:{userId} {keyHash} 索引
        if (!redisAvailable() || userId == null) return;
        // 占位：触发对账 cron 异步纠正（SNAP-6）
        LOG.debugf("invalidateApiKeysForUser userId=%s (lazy via TTL)", userId);
    }

    // ============================================================
    // 配额计数（INCR，月度）
    // ============================================================

    /**
     * 响应式 INCR 当月计数（event-loop 安全，惰性：调用方必须订阅）。
     * 回包是集群全局计数，同步回写进程内视图，使本副本下一次 check 立刻看到最新用量；
     * 任何故障按 0 返回并计入失败指标——计量失败不能影响业务请求。
     */
    public Uni<Long> incrementCounter(String userId) {
        if (!reactiveAvailable() || userId == null) return Uni.createFrom().item(0L);
        OwnerKey key = new OwnerKey(userId, currentPeriod());
        return Uni.createFrom().deferred(() -> reactive.value(String.class, Long.class).incr(counterKey(key)))
            .invoke(v -> mergeUsed(key, v))
            .onFailure().recoverWithItem(e -> redisFailure("incr", userId, e));
    }

    /**
     * 响应式 INCR 每秒限流窗口（event-loop 安全，惰性）。首个 INCR 顺带设置 TTL。
     *
     * <p>与计数不同，这里<b>不吞异常</b>：INCR 的回包就是集群裁决依据，故障必须让调用方
     * （ApiKeyRateLimiter）明确知道并切到本地兜底窗口，而不是被一个伪造的 0 当成「集群空闲」。
     */
    public Uni<Long> incrementRateWindow(String apiKeyId, long epochSecond) {
        if (!reactiveAvailable()) {
            return Uni.createFrom().failure(new IllegalStateException("reactive redis unavailable"));
        }
        String key = NS + "rl:" + apiKeyId + ":" + epochSecond;
        return Uni.createFrom().deferred(() -> reactive.value(String.class, Long.class).incr(key))
            .call(v -> v == 1L
                ? reactive.key(String.class).expire(key, RATE_WINDOW_TTL_SECONDS)
                : Uni.createFrom().voidItem())
            .onFailure().invoke(e -> count("aster_quota_redis_errors_total", "op", "rate"));
    }

    /** 阻塞读取当月计数——仅供运维 / 测试在 worker 线程使用，热路径请用 {@link #peekOwner}。 */
    public long getCounter(String userId) {
        if (!redisAvailable() || userId == null) return 0L;
        try {
            String v = strings().get(counterKey(new OwnerKey(userId, currentPeriod())));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            LOG.warnf("getCounter failed userId=%s: %s", userId, e.getMessage());
            return 0L;
        }
    }

    public void resetCounter(String userId, String period) {
        if (!redisAvailable() || userId == null) return;
        try {
            keys().del(NS + "counter:user:" + userId + ":m:" + period);
        } catch (Exception e) {
            LOG.warnf("resetCounter failed: %s", e.getMessage());
        }
    }

    // ============================================================
    // Util
    // ============================================================

    public static String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).toString(); // YYYY-MM
    }

    private static String userKey(OwnerKey key) {
        return NS + "user:" + key.ownerId();
    }

    private static String counterKey(OwnerKey key) {
        return NS + "counter:user:" + key.ownerId() + ":m:" + key.period();
    }

    /** 一次 MGET 取快照 + 计数：热路径首访只付一个 Redis RTT，且全程不阻塞。 */
    private Uni<OwnerState> loadOwner(OwnerKey key) {
        return reactive.value(String.class).mget(userKey(key), counterKey(key))
            .map(values -> toState(key, values));
    }

    private static OwnerState toState(OwnerKey key, Map<String, String> values) {
        String counter = values.get(counterKey(key));
        long used = counter == null ? 0L : Long.parseLong(counter);
        return new OwnerState(parseUser(values.get(userKey(key))), new AtomicLong(used));
    }

    private static UserSnapshot parseUser(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, UserSnapshot.class);
        } catch (Exception e) {
            LOG.warnf("UserSnapshot 反序列化失败，按缺失处理: %s", e.getMessage());
            return null;
        }
    }

    /** INCR 回包是全局计数：同账期内取最大值，乱序回包不会把视图往回拨。 */
    private void mergeUsed(OwnerKey key, long globalCount) {
        OwnerState state = doneValue(owners.getIfPresent(key));
        if (state != null) {
            state.used().accumulateAndGet(globalCount, Math::max);
        }
    }

    private void forgetOwner(String ownerId) {
        if (ownerId != null) {
            owners.synchronous().invalidate(new OwnerKey(ownerId, currentPeriod()));
        }
    }

    /** 已成功完成的 future 取值；未完成 / 异常 / 不存在一律返回 null，绝不等待。 */
    private static <T> T doneValue(CompletableFuture<T> f) {
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
        return f.join();
    }

    private long redisFailure(String op, String id, Throwable e) {
        LOG.debugf("quota redis %s failed id=%s: %s", op, id, e.getMessage());
        count("aster_quota_redis_errors_total", "op", op);
        return 0L;
    }

    private void countLookup(String result) {
        count("aster_quota_snapshot_lookups_total", "result", result);
    }

    private void count(String name, String tagKey, String tagValue) {
        Counter c = counters.computeIfAbsent(name + "|" + tagValue, k -> newCounter(name, tagKey, tagValue));
        if (c != null) c.increment();
    }

    private Counter newCounter(String name, String tagKey, String tagValue) {
        if (meterRegistry == null || !meterRegistry.isResolvable()) return null;
        return meterRegistry.get().counter(name, tagKey, tagValue);
    }

    /**
     * 首载走 MGET；刷新时与旧状态合并计数（取最大值），避免刷新期间到达的 INCR 回包被旧读数覆盖。
     */
    private final class OwnerLoader implements AsyncCacheLoader<OwnerKey, OwnerState> {
        @Override
        public CompletableFuture<OwnerState> asyncLoad(OwnerKey key, Executor executor) {
            return loadOwner(key).subscribeAsCompletionStage();
        }

        @Override
        public CompletableFuture<OwnerState> asyncReload(OwnerKey key, OwnerState old, Executor executor) {
            return loadOwner(key)
                .invoke(fresh -> fresh.used().accumulateAndGet(old.used().get(), Math::max))
                .subscribeAsCompletionStage();
        }
    }
}
