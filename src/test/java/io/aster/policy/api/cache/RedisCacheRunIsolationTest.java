package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 Redis 跨测试运行保留数据，而测试库会复用主键：Redis 缓存后端的键须按运行隔离，
 * 先前运行以同一缓存键写下的条目不得被本次运行读到；本次运行写下的键须带过期时间，
 * 不在共享 Redis 中无限累积。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class RedisCacheRunIsolationTest {

    /** 测试自己直接写入 Redis 的探针键的存活时间（秒）。 */
    private static final long PROBE_TTL_SECONDS = 60;

    @Inject RedisDataSource redis;
    @Inject @CacheName("idempotency-keys") Cache cache;

    @Test
    void 先前运行以同一键写下的条目对本次运行不可见() {
        String key = "run-isolation-" + UUID.randomUUID();
        String previousRunKey = "policy-cache:" + key;
        try {
            // 先前运行（与生产同一前缀）写下的条目
            redis.value(String.class).setex(previousRunKey, PROBE_TTL_SECONDS, "stale-from-previous-run");

            String value = cache.get(key, k -> "fresh").await().indefinitely();

            assertThat(value).isEqualTo("fresh");
        } finally {
            redis.key().del(previousRunKey);
        }
    }

    @Test
    void 本次运行写入的缓存键带过期时间() {
        String key = "run-ttl-" + UUID.randomUUID();
        cache.get(key, k -> "v").await().indefinitely();

        List<String> written = redis.key().keys("*test-run-" + RedisBackedCacheProfile.RUN_ID + "*" + key + "*");

        assertThat(written).hasSize(1);
        assertThat(redis.key().ttl(written.get(0))).isPositive();
    }
}
