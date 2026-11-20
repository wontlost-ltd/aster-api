package io.aster.policy.api.cache;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 租户结果缓存 epoch 的存储实现。结果一律在 worker 线程上发出：调用方（评估链路）随后可能做阻塞操作，
 * 不能落在 Redis 事件循环上。
 *
 * <p>Redis 键 {@code <根>:result-epoch:<租户>} 不设过期。键不存在（首次使用、Redis 重启丢数据、被内存淘汰）
 * 时以「当前毫秒 × 1000」为种子 {@code SET NX} 初始化，而不是从 0 开始：重建后的 epoch 一定大于此前用过的任何值
 * （除非两次重建之间单租户失效超过每毫秒 1000 次），各副本本地「只进不退」的合并不会把它当成迟到的旧值丢掉，
 * 进程内 Caffeine 后端里旧 epoch 的条目也不会因编号回绕而复活。
 */
final class RedisEpochStore implements TenantCacheEpochs.Store {

    private final ReactiveValueCommands<String, Long> values;
    private final String keyPrefix;
    private final LongSupplier seed;

    RedisEpochStore(ReactiveRedisDataSource redis, String keyRoot, LongSupplier seed) {
        this.values = redis.value(Long.class);
        this.keyPrefix = keyRoot + ":result-epoch:";
        this.seed = seed;
    }

    static long timeSeed() {
        return System.currentTimeMillis() * 1000L;
    }

    String key(String tenant) {
        return keyPrefix + tenant;
    }

    @Override
    public Uni<Long> read(String tenant) {
        String key = key(tenant);
        return values.get(key).chain(epoch -> epoch != null
            ? Uni.createFrom().item(epoch)
            : values.setnx(key, seed.getAsLong()).chain(ignored -> values.get(key)))
            .emitOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Long> bump(String tenant) {
        String key = key(tenant);
        return values.setnx(key, seed.getAsLong()).chain(ignored -> values.incr(key))
            .emitOn(Infrastructure.getDefaultWorkerPool());
    }

    /** 未配置 Redis 的单实例：epoch 只在本进程内，单调递增即可。 */
    static final class InProcess implements TenantCacheEpochs.Store {

        private final ConcurrentHashMap<String, AtomicLong> epochs = new ConcurrentHashMap<>();

        @Override
        public Uni<Long> read(String tenant) {
            return Uni.createFrom().item(() -> counter(tenant).get());
        }

        @Override
        public Uni<Long> bump(String tenant) {
            return Uni.createFrom().item(() -> counter(tenant).incrementAndGet());
        }

        private AtomicLong counter(String tenant) {
            return epochs.computeIfAbsent(tenant, k -> new AtomicLong(1L));
        }
    }
}
