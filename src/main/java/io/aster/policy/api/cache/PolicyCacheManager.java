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
import org.jboss.logging.Logger;

import io.vertx.core.Context;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * 策略缓存管理器，封装生命周期跟踪与租户索引。
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

    private CaffeineCache caffeineCacheDelegate;

    private com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, Boolean> cacheLifecycleTracker;

    private final ConcurrentHashMap<String, Set<PolicyCacheKey>> tenantCacheIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheHitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheMissCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheEvictionCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> remoteInvalidationCounters = new ConcurrentHashMap<>();
    private PubSubCommands<String> pubSubCommands;
    private volatile ResilientRedisSubscriber invalidationSubscriber;
    private ExecutorService invalidationExecutor;

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
     * <p>为保留旧实现「启动返回即已订阅」的语义，这里有界等待首次尝试出结果；在事件循环线程上
     * （懒加载 bean 首次被 reactive 路径触达时）不等待，只异步建立。down 期间本实例只收不到
     * 跨实例失效，主结果缓存仍按 TTL 过期兜底。重复调用会替换并关闭旧订阅。包级可见供集成测试调用。
     *
     * @return 返回时订阅是否已建立
     */
    boolean subscribeInvalidationChannel() {
        ResilientRedisSubscriber subscriber = broadcastSupervisor.subscribe(BROADCAST_NAME, INVALIDATION_CHANNEL,
            this::dispatchRemoteInvalidation, null);
        invalidationSubscriber = subscriber;
        boolean up = Context.isOnEventLoopThread() ? subscriber.isUp() : awaitFirstAttempt(subscriber);
        if (up) {
            LOG.infof("Redis 分布式缓存失效通道已启用: %s", INVALIDATION_CHANNEL);
        } else {
            LOG.warnf("Redis 缓存失效订阅尚未建立，后台持续重试（期间跨实例失效退化到 TTL 兜底）: %s",
                INVALIDATION_CHANNEL);
        }
        return up;
    }

    /** 订阅回调运行在 Vert.x 事件循环上：只转交单线程执行器（其内可安全 await 主缓存失效）。 */
    private void dispatchRemoteInvalidation(String payload) {
        try {
            invalidationExecutor.execute(() -> handleRemoteInvalidation(payload));
        } catch (RejectedExecutionException e) {
            LOG.debugf("失效执行器已关闭（停机中），丢弃远程失效消息: %s", payload);
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
