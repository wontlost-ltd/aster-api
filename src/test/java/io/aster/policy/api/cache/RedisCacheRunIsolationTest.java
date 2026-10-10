package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 Redis 跨测试运行保留数据，而测试库会复用主键：Redis 缓存后端的键须按运行隔离，
 * 先前运行以同一缓存键写下的条目不得被本次运行读到。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class RedisCacheRunIsolationTest {

    @Inject RedisDataSource redis;
    @Inject @CacheName("idempotency-keys") Cache cache;

    @Test
    void 先前运行以同一键写下的条目对本次运行不可见() {
        String key = "run-isolation-" + UUID.randomUUID();
        // 先前运行（与生产同一前缀）写下的条目
        redis.value(String.class).set("policy-cache:" + key, "stale-from-previous-run");

        String value = cache.get(key, k -> "fresh").await().indefinitely();

        assertThat(value).isEqualTo("fresh");
    }
}
