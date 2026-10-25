package io.aster.security.apikey;

import io.aster.test.PostgresTestResource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 红队 P1-F：API key 撤销主动失效 + 跨副本广播。
 *
 * <p>撤销窗口 = 缓存 TTL。此前 5min 太长且 DELETE 只清收到请求的单个 pod（其余副本
 * 仍用本地缓存服务被撤销的 key 直到 TTL）。修复：TTL 收紧到 60s + Redis pub/sub 让所有
 * 副本近实时清本地缓存。
 *
 * <p>单 JVM 无法真起 6 pod，故用 Redis channel 模拟"另一副本发来的失效广播"：
 * <ul>
 *   <li>本地 invalidateForUser → 清本副本缓存（直接验证）</li>
 *   <li>向 channel publish userId（模拟远端副本撤销）→ 本 pod 订阅回调清缓存
 *       （证明跨副本广播链路通）</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ApiKeyRevocationInvalidationIT {

    private static final String INVALIDATE_CHANNEL = "aster:apikey:invalidate";

    @Inject
    ApiKeyVerifierService verifier;

    @Inject
    RedisDataSource redis;

    @Test
    void localInvalidateClearsCacheImmediately() {
        String key = "ak_p1f_local";
        String userId = "user-p1f-local";
        verifier.seedCacheForTest(key,
            ApiKeyVerifyResult.valid("ak-id", userId, "tenant-x", "pro", "active"));
        assertNotNull(verifier.tryCacheLookup(key), "预置后应命中缓存");

        int cleared = verifier.invalidateForUser(userId);
        assertNull(verifier.tryCacheLookup(key), "invalidateForUser 后本副本缓存应立即清空");
        // 至少清了这一把 key（userIndex 反向索引生效）。
        org.junit.jupiter.api.Assertions.assertTrue(cleared >= 1, "应至少清除 1 把 key");
    }

    @Test
    void remoteBroadcastClearsThisReplicaCache() {
        String key = "ak_p1f_remote";
        String userId = "user-p1f-remote";
        verifier.seedCacheForTest(key,
            ApiKeyVerifyResult.valid("ak-id-2", userId, "tenant-y", "pro", "active"));
        assertNotNull(verifier.tryCacheLookup(key), "预置后应命中缓存");

        // 订阅在启动后由后台异步建立：先等其就绪，避免广播早于 SUBSCRIBE 到达而丢失。
        await().atMost(Duration.ofSeconds(10)).until(verifier::isBroadcastActive);

        // 模拟"另一副本"撤销后发来的广播：直接向 Redis channel 发 userId。
        // 本 pod 的订阅回调应清掉本地缓存（不经 invalidateForUser 的本地清除路径）。
        redis.pubsub(String.class).publish(INVALIDATE_CHANNEL, userId);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> assertNull(verifier.tryCacheLookup(key),
                "收到跨副本失效广播后本 pod 缓存应被清除"));
    }

    @Test
    void cacheTtlIsBounded() throws Exception {
        // 撤销残留窗口（Redis 广播失效时的兜底）必须 <= 60s。用反射读私有常量，
        // 钉住"别把 TTL 改回 5min"的回归。
        java.lang.reflect.Field f = ApiKeyVerifierService.class.getDeclaredField("CACHE_TTL");
        f.setAccessible(true);
        Duration ttl = (Duration) f.get(null);
        org.junit.jupiter.api.Assertions.assertTrue(ttl.getSeconds() <= 60,
            "API key 缓存 TTL 必须 <= 60s（撤销残留窗口上限），实际=" + ttl);
    }
}
