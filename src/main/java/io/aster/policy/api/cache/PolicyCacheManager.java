package io.aster.policy.api.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.aster.common.redis.RedisBroadcastSupervisor;
import io.aster.common.redis.ResilientRedisSubscriber;
import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.metrics.PolicyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 策略缓存管理器，封装生命周期跟踪、租户索引与租户结果缓存 epoch。
 *
 * <p>正确性由租户 epoch 保证（issue #484，见 {@link TenantCacheEpochs}）：结果缓存键带租户 epoch，失效 = 推进
 * epoch。租户索引、逐键删除与失效广播里的键信息只用于回收内存与统计，漏收不再导致陈旧结果。
 */
@ApplicationScoped
public class PolicyCacheManager {

    private static final Logger LOG = Logger.getLogger(PolicyCacheManager.class);
    private static final String INVALIDATION_CHANNEL = "policy-cache:invalidate";
    private static final String CACHE_NAME = "policy-results";
    private static final String BROADCAST_NAME = "policy_cache_invalidate_broadcast";

    /**
     * 启动时等待首次订阅出结果的上限：与 {@code RedisBroadcastSupervisor} 单次订阅超时同量级，
     * 保留旧实现「启动即订阅」的就绪语义，又不会因 Redis 不可达而无限阻塞启动。
     */
    static final Duration STARTUP_SUBSCRIBE_WAIT = Duration.ofSeconds(6);

    /** epoch 存储读失败后的退避：期间不回源、直接绕过结果缓存，避免 Redis 挂掉时每个请求都等满命令超时。 */
    static final Duration EPOCH_STORE_FAILURE_BACKOFF = Duration.ofSeconds(1);

    /** 写不进存储的失效（待补发）的重试间隔。 */
    static final Duration EPOCH_RETRY_INTERVAL = Duration.ofSeconds(1);

    @Inject
    @CacheName("policy-results")
    Cache policyResultCache;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    RedisBroadcastSupervisor broadcastSupervisor;

    @Inject
    PolicyMetrics policyMetrics;

    /** 与 policy-results 的 Redis 键前缀同根（{@code quarkus.cache.redis.prefix}），测试按运行隔离时一并隔离 epoch。 */
    @ConfigProperty(name = "aster.cache.redis.key-root", defaultValue = "policy-cache")
    String redisKeyRoot;

    /** 订阅在线时本地 epoch 的可信时长：推送随时更新，此值只兜底静默断开的订阅连接。 */
    @ConfigProperty(name = "aster.policy.cache.epoch.ttl", defaultValue = "5s")
    Duration epochTrustedTtl;

    /** 订阅不在线（或取值时不在线）时本地 epoch 的有效时长，过期即回源 Redis。 */
    @ConfigProperty(name = "aster.policy.cache.epoch.unsubscribed-ttl", defaultValue = "500ms")
    Duration epochUntrustedTtl;

    private CaffeineCache caffeineCacheDelegate;

    private com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, Boolean> cacheLifecycleTracker;

    private final ConcurrentHashMap<String, Set<PolicyCacheKey>> tenantCacheIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheHitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheMissCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheEvictionCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> remoteInvalidationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> epochCounters = new ConcurrentHashMap<>();
    private PubSubCommands<String> pubSubCommands;
    private io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands<String> reactivePubSub;
    private volatile ResilientRedisSubscriber invalidationSubscriber;
    private ExecutorService invalidationExecutor;
    private ScheduledExecutorService epochRetryExecutor;
    private TenantCacheEpochs epochs;

    @PostConstruct
    void initCacheLifecycleTracking() {
        Duration expireAfterWrite = null;
        Long maximumSize = null;
        Integer initialCapacity = null;

        try {
            caffeineCacheDelegate = policyResultCache.as(CaffeineCache.class);
            if (caffeineCacheDelegate instanceof io.quarkus.cache.runtime.caffeine.CaffeineCacheImpl impl) {
                var info = impl.getCacheInfo();
                initialCapacity = info.initialCapacity;
                maximumSize = info.maximumSize;
                expireAfterWrite = info.expireAfterWrite;
            }
        } catch (IllegalStateException ex) {
            LOG.warn("政策评估缓存未使用Caffeine后端，租户索引将使用默认监听配置", ex);
            caffeineCacheDelegate = null;
        }

        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (initialCapacity != null) {
            builder.initialCapacity(initialCapacity);
        }
        if (maximumSize != null) {
            builder.maximumSize(maximumSize);
        }
        if (expireAfterWrite != null) {
            builder.expireAfterWrite(expireAfterWrite);
        }

        cacheLifecycleTracker = builder.removalListener((PolicyCacheKey removedKey, Boolean ignored, RemovalCause cause) -> {
            if (removedKey != null) {
                recordEviction(removedKey);
                removeTrackedKey(removedKey);
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("Caffeine移除缓存键, cause=%s, key=%s", cause, removedKey);
                }
            }
        }).build();

        registerCacheMetrics();
        initRedisInvalidationChannel();
        initEpochs();
    }

    /**
     * 未配置 Redis 时 epoch 只在进程内（单实例，失效即时可见）；配置了 Redis 时存在 Redis，多副本共享，
     * 并启动待补发失效的定期重试。
     */
    private void initEpochs() {
        if (reactivePubSub == null) {
            epochs = new TenantCacheEpochs(new RedisEpochStore.InProcess(), () -> true, System::nanoTime,
                epochTrustedTtl, epochUntrustedTtl, EPOCH_STORE_FAILURE_BACKOFF, (tenant, epoch) -> { });
            return;
        }
        epochs = new TenantCacheEpochs(
            new RedisEpochStore(redisDataSource.get().getReactive(), redisKeyRoot, RedisEpochStore::timeSeed),
            this::isInvalidationChannelUp, System::nanoTime,
            epochTrustedTtl, epochUntrustedTtl, EPOCH_STORE_FAILURE_BACKOFF, this::publishEpoch);
        epochRetryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "policy-cache-epoch-retry");
            thread.setDaemon(true);
            return thread;
        });
        epochRetryExecutor.scheduleWithFixedDelay(this::retryPendingEpochBumps,
            EPOCH_RETRY_INTERVAL.toMillis(), EPOCH_RETRY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 租户当前结果缓存 epoch，评估读写 policy-results 时放进键里。empty 表示此刻无法确认（Redis 不可用，
     * 或本副本的失效尚未写进 Redis），调用方必须绕过结果缓存直接执行。
     *
     * <p>需要回源 Redis 时结果在 worker 线程上发出，调用方后续的阻塞操作不会落到 Redis 事件循环上。
     */
    public Uni<OptionalLong> resultEpoch(String tenantId) {
        return epochs.current(normalizeTenant(tenantId))
            .invoke(value -> recordEpochLookup(value.isPresent() ? "ok" : "bypass"));
    }

    /**
     * 失效租户全部策略结果：推进租户 epoch 并广播。完成后本副本立即读到新 epoch；其他副本经推送立即、
     * 漏收时最迟 {@code aster.policy.cache.epoch.ttl} 后回源拿到新 epoch。Redis 写入失败不会让返回的 Uni 失败，
     * 而是记为待补发：补发成功前本副本对该租户绕过结果缓存。
     */
    public Uni<Void> invalidateTenantResults(String tenantId) {
        return epochs.invalidate(normalizeTenant(tenantId));
    }

    /** 本地缓存的租户 epoch（不回源）。包级可见供测试断言推送是否已送达。 */
    OptionalLong cachedEpoch(String tenantId) {
        return epochs.cached(normalizeTenant(tenantId));
    }

    /** 丢弃本地全部租户 epoch（等同重启后的空状态）。包级可见供测试模拟副本重启。 */
    void forgetLocalEpochs() {
        epochs.onChannelUp();
    }

    /** 租户 epoch 的 Redis 键。包级可见供测试模拟其他副本推进 epoch。 */
    String epochRedisKey(String tenantId) {
        return redisKeyRoot + ":result-epoch:" + normalizeTenant(tenantId);
    }

    /** 定期重试待补发的失效（epoch 写不进 Redis 的租户），在专用调度线程上阻塞执行。 */
    private void retryPendingEpochBumps() {
        try {
            if (epochs.hasPendingBumps()) {
                epochs.retryPendingBumps(Duration.ofSeconds(2));
            }
        } catch (RuntimeException e) {
            LOG.debugf("补发租户结果缓存 epoch 异常: %s", e.toString());
        }
    }

    public Cache getPolicyResultCache() {
        return policyResultCache;
    }

    public CaffeineCache getCaffeineCacheDelegate() {
        return caffeineCacheDelegate;
    }

    public com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, Boolean> getLifecycleTracker() {
        return cacheLifecycleTracker;
    }

    public com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> getNativePolicyCache() {
        if (caffeineCacheDelegate == null) {
            return null;
        }
        try {
            var getCache = caffeineCacheDelegate.getClass().getDeclaredMethod("getCache");
            getCache.setAccessible(true);
            Object cache = getCache.invoke(caffeineCacheDelegate);
            if (cache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
                @SuppressWarnings("unchecked")
                com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> typed =
                    (com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?>) caffeineCache;
                return typed;
            }
        } catch (NoSuchMethodException ignored) {
            // ignore and try field scan
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("通过反射获取底层Caffeine缓存失败", e);
            }
        }
        try {
            for (var field : caffeineCacheDelegate.getClass().getDeclaredFields()) {
                if (com.github.benmanes.caffeine.cache.Cache.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(caffeineCacheDelegate);
                    if (value instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
                        @SuppressWarnings("unchecked")
                        com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> typed =
                            (com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?>) caffeineCache;
                        return typed;
                    }
                }
            }
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("通过字段访问底层Caffeine缓存失败", e);
            }
        }
        return null;
    }

    private void initRedisInvalidationChannel() {
        if (redisDataSource == null || redisDataSource.isUnsatisfied()) {
            LOG.debug("RedisDataSource 未配置，跳过分布式缓存广播");
            return;
        }
        try {
            invalidationExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "policy-cache-invalidation");
                thread.setDaemon(true);
                return thread;
            });
            pubSubCommands = redisDataSource.get().pubsub(String.class);
            reactivePubSub = redisDataSource.get().getReactive().pubsub(String.class);
        } catch (Exception e) {
            LOG.warn("初始化 Redis 缓存失效订阅失败", e);
            return;
        }
        subscribeInvalidationChannel();
    }

    /**
     * 订阅交给 {@link RedisBroadcastSupervisor}：先挂处理器再 SUBSCRIBE（无确认丢失竞态），失败或断线后
     * 退避重订阅，并暴露 {@code aster_redis_broadcast_up{channel="policy-cache:invalidate"}}。
     *
     * <p>每次（重新）订阅成功后丢弃本地缓存的租户 epoch，下一次评估回源 Redis，补齐 down 期间漏收的推送
     * （见 {@link #resyncAfterSubscribe()}）。
     *
     * <p>为保留旧实现「启动返回即已订阅」的语义，这里有界等待首次尝试出结果；在事件循环线程上
     * （懒加载 bean 首次被 reactive 路径触达时）不等待，只异步建立。down 期间本地 epoch 只按
     * {@code aster.policy.cache.epoch.unsubscribed-ttl} 短期缓存、频繁回源，跨实例失效照样生效。
     * 重复调用会替换并关闭旧订阅。包级可见供集成测试调用。
     *
     * @return 返回时订阅是否已建立
     */
    boolean subscribeInvalidationChannel() {
        ResilientRedisSubscriber subscriber = broadcastSupervisor.subscribe(BROADCAST_NAME, INVALIDATION_CHANNEL,
            this::dispatchRemoteInvalidation, this::resyncAfterSubscribe);
        invalidationSubscriber = subscriber;
        boolean up = Context.isOnEventLoopThread() ? subscriber.isUp() : awaitFirstAttempt(subscriber);
        if (up) {
            LOG.infof("Redis 分布式缓存失效通道已启用: %s", INVALIDATION_CHANNEL);
        } else {
            LOG.warnf("Redis 缓存失效订阅尚未建立，后台持续重试（期间租户 epoch 短期缓存、频繁回源）: %s",
                INVALIDATION_CHANNEL);
        }
        return up;
    }

    /**
     * 订阅回调运行在 Vert.x 事件循环上：消息带的新 epoch 当场合并（只是一次 map 合并，越早可见越好），
     * 其余（按索引回收旧条目）转交单线程执行器（其内可安全 await 主缓存失效）。
     */
    private void dispatchRemoteInvalidation(String payload) {
        applyRemoteEpoch(payload);
        try {
            invalidationExecutor.execute(() -> handleRemoteInvalidation(payload));
        } catch (RejectedExecutionException e) {
            LOG.debugf("失效执行器已关闭（停机中），丢弃远程失效消息: %s", payload);
        }
    }

    /**
     * 每次（重新）订阅成功后执行：pub/sub 不持久，订阅建立前 / 断线期间其他副本推送的新 epoch 可能已漏收，
     * 丢弃本地全部 epoch，下一次评估回源 Redis 取最新值。只清一张小 map，在订阅调度线程上同步完成；
     * 结果缓存条目本身无需作废（旧 epoch 的键不会再被读到）。
     */
    private void resyncAfterSubscribe() {
        TenantCacheEpochs current = epochs;
        if (current != null) {
            current.onChannelUp();
        }
    }

    private void applyRemoteEpoch(String payload) {
        TenantCacheEpochs current = epochs;
        if (current == null || payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject message = new JsonObject(payload);
            Object epoch = message.getValue("epoch");
            if (epoch instanceof Number number) {
                current.onRemoteEpoch(normalizeTenant(message.getString("tenantId")), number.longValue());
            }
        } catch (RuntimeException e) {
            LOG.debugf("解析失效消息中的 epoch 失败: %s", payload);
        }
    }

    /** 等待失效执行器处理完此前排队的任务（远程失效 / 恢复作废）。包级可见供测试消除异步时序。 */
    void awaitInvalidationQueueIdle(Duration timeout) throws Exception {
        if (invalidationExecutor != null) {
            invalidationExecutor.submit(() -> { }).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static boolean awaitFirstAttempt(ResilientRedisSubscriber subscriber) {
        try {
            return subscriber.awaitFirstAttempt(STARTUP_SUBSCRIBE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return subscriber.isUp();
        }
    }

    /** 跨实例失效订阅当前是否已建立（就绪信号）；false 表示本实例暂时收不到其他实例的失效广播。 */
    public boolean isInvalidationChannelUp() {
        ResilientRedisSubscriber subscriber = invalidationSubscriber;
        return subscriber != null && subscriber.isUp();
    }

    public void registerCacheEntry(PolicyCacheKey key, String tenantId) {
        if (key == null) {
            return;
        }
        String normalizedTenant = normalizeTenant(tenantId != null ? tenantId : key.getTenantId());
        tenantCacheIndex
            .computeIfAbsent(normalizedTenant, k -> ConcurrentHashMap.newKeySet())
            .add(key);
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.put(key, Boolean.TRUE);
        }
    }

    public void removeCacheEntry(PolicyCacheKey key, String tenantId) {
        if (key == null) {
            return;
        }
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.invalidate(key);
        }
        removeTrackedKey(key, tenantId);
        publishInvalidation(key);
    }

    public boolean isCacheHit(PolicyCacheKey key) {
        if (key == null) {
            return false;
        }
        String normalizedTenant = normalizeTenant(key.getTenantId());
        // 直接探测真实的 policy-results Caffeine 缓存（getIfPresent 不触发加载）。
        // 此前读 tenantCacheIndex 侧索引 —— 它由 registerCacheEntry 每次调用无条件回填、
        // 与真实缓存靠独立 mirror tracker 松散同步、各自按 TTL/容量独立驱逐，两者会漂移：
        // 索引说 present（isCacheHit=true）但真缓存条目已被驱逐 → getAsync 找不到照样重跑
        // loader（全量重执行 + 重复 DB + 审计），"命中"却不省时、DB 反增。改为查真实缓存后，
        // isCacheHit 与 getAsync 命中判定一致：真命中才 true，才真正跳过执行。
        boolean hit = false;
        if (caffeineCacheDelegate != null) {
            hit = caffeineCacheDelegate.getIfPresent(key) != null;
        } else {
            // 非 Caffeine 后端（理论罕见）回退到侧索引，保持行为不崩。
            Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
            hit = keys != null && keys.contains(key);
        }
        recordCacheLookup(normalizedTenant, hit);
        return hit;
    }

    public Set<PolicyCacheKey> snapshotTenantCacheKeys(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(keys));
    }

    public void invalidateTenantCache(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        Set<PolicyCacheKey> keys = tenantCacheIndex.remove(normalizedTenant);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (cacheLifecycleTracker != null) {
            for (PolicyCacheKey key : keys) {
                cacheLifecycleTracker.invalidate(key);
            }
        }
        publishInvalidation(normalizedTenant, null, null, null);
    }

    public void clearAllCache() {
        tenantCacheIndex.clear();
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.invalidateAll();
        }
    }

    private void removeTrackedKey(PolicyCacheKey cacheKey) {
        removeTrackedKey(cacheKey, cacheKey != null ? cacheKey.getTenantId() : null);
    }

    private void removeTrackedKey(PolicyCacheKey cacheKey, String tenantId) {
        if (cacheKey == null) {
            return;
        }
        String normalizedTenant = normalizeTenant(tenantId != null ? tenantId : cacheKey.getTenantId());
        Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
        if (keys != null) {
            keys.remove(cacheKey);
            if (keys.isEmpty()) {
                tenantCacheIndex.remove(normalizedTenant, keys);
            }
        }
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    // 包内可见，便于集成测试直接触发远程失效逻辑
    void handleRemoteInvalidation(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject message = new JsonObject(payload);
            String tenantId = normalizeTenant(message.getString("tenantId"));
            String policyModule = message.getString("policyModule");
            String policyFunction = message.getString("policyFunction");
            Integer hash = message.containsKey("hash") ? message.getInteger("hash") : null;

            recordRemoteInvalidation(tenantId);

            // 安全审计 C3：远程（跨 pod）失效必须真正失效 Quarkus @CacheName("policy-results")
            // **主结果缓存**（policyResultCache），而不仅仅清 tenantCacheIndex 追踪表或
            // cacheLifecycleTracker（后者只是镜像 TTL/size 的追踪 Caffeine，其 removalListener 只
            // 更新索引+埋点，不驱动主缓存驱逐）。否则本 pod 主缓存条目留到 15m TTL，跨 pod
            // 发版/回滚后仍读旧决策。与本地路径 PolicyEvaluationService（policyResultCache.invalidate
            // 后 removeCacheEntry）对齐。本 handler 已在单线程 invalidationExecutor 内，await 安全；
            // origin pod 会收自己的广播，二次 invalidate 幂等无害。
            if (policyModule == null && policyFunction == null && hash == null) {
                // 全租户失效：对该租户所有已追踪 key 逐一失效主缓存，再清追踪表。
                Set<PolicyCacheKey> tenantKeys = tenantCacheIndex.get(tenantId);
                if (tenantKeys != null) {
                    for (PolicyCacheKey key : new HashSet<>(tenantKeys)) {
                        invalidateResultCache(key);
                        removeTrackedKey(key, tenantId);
                    }
                }
                tenantCacheIndex.remove(tenantId);
                return;
            }

            Set<PolicyCacheKey> keys = tenantCacheIndex.get(tenantId);
            if (keys == null || keys.isEmpty()) {
                return;
            }
            new HashSet<>(keys).stream()
                .filter(k -> matches(policyModule, k.getPolicyModule()))
                .filter(k -> matches(policyFunction, k.getPolicyFunction()))
                .filter(k -> hash == null || k.hashCode() == hash)
                .forEach(k -> {
                    invalidateResultCache(k);   // C3：真正失效主结果缓存条目
                    removeTrackedKey(k, tenantId);
                });
        } catch (Exception e) {
            LOG.warnf(e, "解析 Redis 缓存失效消息失败: %s", payload);
        }
    }

    /**
     * 安全审计 C3：真正失效 Quarkus 主结果缓存（policy-results）里的一个 key。
     *
     * <p>远程失效 handler 专用——本地失效路径由 PolicyEvaluationService 直接对
     * {@code policyResultCache} 调 invalidate。本 handler 在单线程 invalidationExecutor 内运行，
     * {@code await().indefinitely()} 同步失效安全（不阻塞请求线程/event-loop）。
     */
    private void invalidateResultCache(PolicyCacheKey key) {
        if (key == null || policyResultCache == null) {
            return;
        }
        try {
            policyResultCache.invalidate(key).await().indefinitely();
        } catch (Exception e) {
            LOG.warnf(e, "远程失效主结果缓存条目失败: %s", key);
        }
    }

    private boolean matches(String pattern, String value) {
        if (pattern == null) {
            return true;
        }
        return pattern.equals(value);
    }

    private void publishInvalidation(PolicyCacheKey key) {
        if (key == null) {
            return;
        }
        publishInvalidation(key.getTenantId(), key.getPolicyModule(), key.getPolicyFunction(), key.hashCode());
    }

    /**
     * 广播租户新 epoch。消息仍带 tenantId 与空的 policyModule / policyFunction：不认识 epoch 的旧版本副本
     * （滚动发布期间）把它当作租户级失效处理。异步发布，不阻塞调用线程（可能是事件循环）。
     */
    private void publishEpoch(String tenantId, long epoch) {
        recordEpochBump();
        if (reactivePubSub == null) {
            return;
        }
        String payload = new JsonObject()
            .put("tenantId", tenantId)
            .putNull("policyModule")
            .putNull("policyFunction")
            .put("epoch", epoch)
            .encode();
        reactivePubSub.publish(INVALIDATION_CHANNEL, payload).subscribe().with(
            ignored -> { },
            e -> LOG.debugf("广播租户结果缓存 epoch 失败（其他副本最迟按 epoch TTL 回源） tenant=%s: %s",
                tenantId, e.toString()));
    }

    private void publishInvalidation(String tenantId, String module, String function, Integer hash) {
        if (pubSubCommands == null || tenantId == null) {
            return;
        }
        try {
            JsonObject json = new JsonObject()
                .put("tenantId", normalizeTenant(tenantId))
                .put("policyModule", module)
                .put("policyFunction", function);
            if (hash != null) {
                json.put("hash", hash);
            }
            pubSubCommands.publish(INVALIDATION_CHANNEL, json.encode());
        } catch (Exception e) {
            LOG.debug("广播缓存失效消息失败", e);
        }
    }

    @PreDestroy
    void shutdownInvalidationChannel() {
        if (epochRetryExecutor != null) {
            epochRetryExecutor.shutdownNow();
        }
        ResilientRedisSubscriber subscriber = invalidationSubscriber;
        if (subscriber != null) {
            // close 幂等且自行吞掉退订异常（记 debug），与监护者停机时的统一关闭不冲突。
            subscriber.close();
        }
        if (invalidationExecutor != null) {
            // P3-R20: graceful shutdown — give in-flight tasks 2s to finish
            // before forcing interrupt. Prevents losing the last cache
            // invalidation message during rolling restarts.
            invalidationExecutor.shutdown();
            try {
                if (!invalidationExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    invalidationExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                invalidationExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void registerCacheMetrics() {
        if (meterRegistry == null) {
            return;
        }
        // 缓存容量 Gauge：实时反映 cacheLifecycleTracker 估算的条目数
        Gauge.builder("policy_cache_size", () -> cacheLifecycleTracker != null ? cacheLifecycleTracker.estimatedSize() : 0)
            .description("策略缓存当前条目数量")
            .tag("cache_name", CACHE_NAME)
            .register(meterRegistry);

        // 活跃租户 Gauge：跟踪维护索引的租户个数
        Gauge.builder("policy_cache_active_tenants", tenantCacheIndex, ConcurrentHashMap::size)
            .description("策略缓存活跃租户数量")
            .tag("cache_name", CACHE_NAME)
            .register(meterRegistry);

        // 预注册默认租户计数器，避免冷启动首次命中/未命中时阻塞
        String defaultTenant = normalizeTenant(null);
        cacheHitCounters.computeIfAbsent(defaultTenant, this::createHitCounter);
        cacheMissCounters.computeIfAbsent(defaultTenant, this::createMissCounter);
        cacheEvictionCounters.computeIfAbsent(defaultTenant, this::createEvictionCounter);
        remoteInvalidationCounters.computeIfAbsent(defaultTenant, this::createRemoteInvalidationCounter);
    }

    private void recordCacheLookup(String tenant, boolean hit) {
        if (meterRegistry == null || tenant == null) {
            return;
        }
        if (hit) {
            incrementCounter(cacheHitCounters, tenant, this::createHitCounter);
            if (policyMetrics != null) {
                policyMetrics.recordCacheHit();
            }
        } else {
            incrementCounter(cacheMissCounters, tenant, this::createMissCounter);
            if (policyMetrics != null) {
                policyMetrics.recordCacheMiss();
            }
        }
    }

    private void recordEpochLookup(String result) {
        if (meterRegistry == null) {
            return;
        }
        incrementCounter(epochCounters, "lookup:" + result, k -> Counter.builder("policy_cache_epoch_lookups_total")
            .description("策略结果缓存读取租户 epoch 的结果：ok=取到 epoch，bypass=无法确认、绕过结果缓存")
            .tag("cache_name", CACHE_NAME)
            .tag("result", result)
            .register(meterRegistry));
    }

    private void recordEpochBump() {
        if (meterRegistry == null) {
            return;
        }
        incrementCounter(epochCounters, "bump", k -> Counter.builder("policy_cache_epoch_bumps_total")
            .description("租户结果缓存 epoch 推进（失效）成功次数，含补发")
            .tag("cache_name", CACHE_NAME)
            .register(meterRegistry));
    }

    private void recordEviction(PolicyCacheKey removedKey) {
        if (meterRegistry == null || removedKey == null) {
            return;
        }
        incrementCounter(cacheEvictionCounters, normalizeTenant(removedKey.getTenantId()), this::createEvictionCounter);
    }

    private void recordRemoteInvalidation(String tenant) {
        if (meterRegistry == null || tenant == null) {
            return;
        }
        incrementCounter(remoteInvalidationCounters, tenant, this::createRemoteInvalidationCounter);
    }

    private void incrementCounter(ConcurrentHashMap<String, Counter> store, String tenant, Function<String, Counter> factory) {
        store.computeIfAbsent(tenant, factory).increment();
    }

    private Counter createHitCounter(String tenant) {
        return Counter.builder("policy_cache_hits_total")
            .description("策略缓存命中次数统计")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createMissCounter(String tenant) {
        return Counter.builder("policy_cache_misses_total")
            .description("策略缓存未命中次数统计")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createEvictionCounter(String tenant) {
        return Counter.builder("policy_cache_evictions_total")
            .description("策略缓存移除事件次数")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createRemoteInvalidationCounter(String tenant) {
        return Counter.builder("policy_cache_remote_invalidations_total")
            .description("策略缓存远程失效次数")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }
}
