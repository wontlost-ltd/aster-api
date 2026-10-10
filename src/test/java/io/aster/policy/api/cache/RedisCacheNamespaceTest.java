package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 缓存后端下，不同缓存以同一逻辑键存取互不覆盖：键前缀须带缓存名。
 * 前缀模板来自 main 配置，profile 只替换其根部分（按运行隔离），故此处验证的是生产的键结构。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class RedisCacheNamespaceTest {

    @Inject @CacheName("idempotency-keys") Cache idempotencyKeys;
    @Inject @CacheName(RedisBackedCacheProfile.PROBE_CACHE) Cache probe;

    @Test
    void 两个缓存的同名键互不覆盖() {
        String key = "namespace-" + UUID.randomUUID();

        String first = idempotencyKeys.get(key, k -> "from-idempotency-keys").await().indefinitely();
        String second = probe.get(key, k -> "from-probe").await().indefinitely();

        assertThat(first).isEqualTo("from-idempotency-keys");
        assertThat(second).isEqualTo("from-probe");
    }
}
