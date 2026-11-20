package io.aster.policy.api.cache;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.cache.Cache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #484：策略结果缓存键带租户 epoch 后，丢失的失效不再能返回陈旧结果（真 Redis，Caffeine 后端；
 * {@link PolicyResultEpochRedisBackedIT} 以生产默认的 Redis 共享后端重跑）。
 *
 * <p>每个用例先走生产评估路径写入一条结果，再把该缓存键的值换成可辨认的陈旧结果（{@link #STALE}），
 * 确认未失效时评估确实读到它；随后制造一种「失效丢失」，断言评估不再返回陈旧结果。
 * 「其他副本的失效」用直接改 Redis 里的 epoch 键模拟（与 {@code RedisEpochStore.bump} 相同的 INCR），
 * 按场景决定是否 PUBLISH。
 */
@QuarkusTest
@RedisEnabledTest
class PolicyResultEpochIT {

    private static final String MODULE = DeployedMapParamProbeTest.MODULE;
    private static final String FUNCTION = DeployedMapParamProbeTest.FUNCTION;
    private static final String CHANNEL = "policy-cache:invalidate";
    private static final String STALE = "STALE";
    private static final Map<String, Object> REQ = Map.of(
        "principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
        "action", "read_customer_record", "resource_type", "customer", "resource_id", "C-1001",
        "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");

    @Inject PolicyEvaluationService evaluationService;
    @Inject PolicyCacheManager cacheManager;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @Inject RedisDataSource redis;

    private String tenant;

    @AfterEach
    void clean() {
        String t = tenant;
        if (t == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", t);
            PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", t);
            PolicyVersion.delete("tenantId = ?1", t);
            PolicyCatalog.delete("tenantId = ?1", t);
        });
        redis.key().del(cacheManager.epochRedisKey(t));
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
    }

    /** 其他副本的失效：推进共享 epoch，但本副本没有收到推送（订阅断开 / 消息丢失）。 */
    @Test
    void 漏收推送的失效最迟在epoch可信时长后生效() {
        PolicyCacheKey poisoned = deployEvaluateAndPoison("epoch-missed-");

        long bumped = redis.value(Long.class).incr(cacheManager.epochRedisKey(tenant));
        assertEquals(poisoned.getEpoch() + 1, bumped);

        long start = System.nanoTime();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> !STALE.equals(evaluate().getResult()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 5_000 + 2_000, "陈旧窗口应受 aster.policy.cache.epoch.ttl（5s）约束，实际 " + elapsedMs + "ms");
        assertEquals("ALLOW", evaluate().getDecision().outcome());
        assertEquals(OptionalLong.of(bumped), cacheManager.cachedEpoch(tenant));
    }

    /**
     * 重启副本遗留：陈旧条目仍在缓存里（Redis 后端下是共享 Redis，旧实现里没有任何副本的索引追踪它），
     * 失效发生在本副本「停机」期间。重启后的空状态（本地 epoch、租户索引都为空）下第一次评估就不能读到它。
     */
    @Test
    void 重启后遗留的旧epoch条目读不到() {
        PolicyCacheKey poisoned = deployEvaluateAndPoison("epoch-restart-");

        redis.value(Long.class).incr(cacheManager.epochRedisKey(tenant));
        cacheManager.clearAllCache();
        cacheManager.forgetLocalEpochs();

        PolicyEvaluationResult after = evaluate();
        assertNotEquals(STALE, after.getResult(), "失效之后不得返回旧 epoch 键下的结果");
        assertEquals("ALLOW", after.getDecision().outcome());
        assertEquals(STALE, read(poisoned), "旧条目本身仍在缓存里（没有被删除），是 epoch 让它读不到");
    }

    /** 推送送达时立即生效，不等本地 epoch 过期。 */
    @Test
    void 其他副本广播的新epoch经订阅立即生效() {
        PolicyCacheKey poisoned = deployEvaluateAndPoison("epoch-push-");
        await().atMost(Duration.ofSeconds(15)).until(cacheManager::isInvalidationChannelUp);

        long bumped = redis.value(Long.class).incr(cacheManager.epochRedisKey(tenant));
        String message = new JsonObject().put("tenantId", tenant).putNull("policyModule").putNull("policyFunction")
            .put("epoch", bumped).encode();
        assertTrue(redis.execute("PUBLISH", CHANNEL, message).toLong() >= 1);

        // 只看本地缓存的 epoch（不触发回源）：变化只可能来自推送。
        await().atMost(Duration.ofSeconds(3))
            .until(() -> cacheManager.cachedEpoch(tenant).equals(OptionalLong.of(bumped)));
        assertNotEquals(STALE, evaluate().getResult());
        assertTrue(poisoned.getEpoch() < bumped);
    }

    /** 本副本发起的失效（生产入口）：返回后立即不再读到旧结果，且共享 epoch 恰好推进一次。 */
    @Test
    void 本副本失效后立即不再读到旧结果() {
        PolicyCacheKey poisoned = deployEvaluateAndPoison("epoch-local-");

        evaluationService.invalidateCache(tenant, MODULE, FUNCTION).await().atMost(Duration.ofSeconds(5));

        assertNotEquals(STALE, evaluate().getResult());
        assertEquals(poisoned.getEpoch() + 1, redis.value(Long.class).get(cacheManager.epochRedisKey(tenant)));
    }

    /**
     * 在途评估回填：失效之前开始、失效之后才完成的评估会把结果写回缓存。它写入的是开始时的 epoch 键，
     * 失效之后的读取用新 epoch，读不到这份回填。缓存读写与键的构造方式与 PolicyEvaluationService 一致。
     */
    @Test
    void 失效之后完成的在途评估回填读不到() throws Exception {
        tenant = "tenant-epoch-inflight-" + UUID.randomUUID().toString().substring(0, 8);
        Cache cache = cacheManager.getPolicyResultCache();
        long before = cacheManager.resultEpoch(tenant).await().atMost(Duration.ofSeconds(5)).orElseThrow();
        PolicyCacheKey inflightKey = keyAt(before);

        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<PolicyEvaluationResult> inflight = cache
            .getAsync(inflightKey, k -> Uni.createFrom().item(() -> {
                    loaderStarted.countDown();
                    awaitLatch(release);
                    return new PolicyEvaluationResult(STALE, 1.0, false);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
            .subscribeAsCompletionStage();
        assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));

        cacheManager.invalidateTenantResults(tenant).await().atMost(Duration.ofSeconds(5));
        release.countDown();
        assertEquals(STALE, inflight.get(5, TimeUnit.SECONDS).getResult());
        assertEquals(STALE, read(inflightKey), "在途评估确实把结果回填到了它开始时的键");

        long after = cacheManager.resultEpoch(tenant).await().atMost(Duration.ofSeconds(5)).orElseThrow();
        assertEquals(before + 1, after);
        PolicyEvaluationResult fresh = cache
            .<PolicyCacheKey, PolicyEvaluationResult>get(keyAt(after), k -> new PolicyEvaluationResult("FRESH", 1.0, false))
            .await().atMost(Duration.ofSeconds(5));
        assertEquals("FRESH", fresh.getResult(), "失效之后的读取不得命中在途评估的回填");
    }

    private PolicyCacheKey keyAt(long epoch) {
        return new PolicyCacheKey(tenant, MODULE, FUNCTION, "1", epoch, new Object[]{"{}"});
    }

    /**
     * 部署策略并评估一次（写入结果缓存），把这条缓存的值换成 {@link #STALE}，并确认未失效时评估读到它：
     * 证明后续断言的「不是 STALE」来自失效生效，而不是根本没走缓存。
     */
    private PolicyCacheKey deployEvaluateAndPoison(String prefix) {
        tenant = "tenant-" + prefix + UUID.randomUUID().toString().substring(0, 8);
        DeployedMapParamProbeTest.deploy(tenant, MODULE, FUNCTION, DeployedMapParamProbeTest.SOURCE);
        assertEquals("ALLOW", evaluate().getDecision().outcome());

        Set<PolicyCacheKey> keys = cacheManager.snapshotTenantCacheKeys(tenant);
        assertEquals(1, keys.size(), "首次评估应登记一条结果缓存键");
        PolicyCacheKey key = keys.iterator().next();
        assertEquals(OptionalLong.of(key.getEpoch()), cacheManager.cachedEpoch(tenant), "结果键应带租户当前 epoch");

        Cache cache = cacheManager.getPolicyResultCache();
        cache.invalidate(key).await().atMost(Duration.ofSeconds(5));
        assertEquals(STALE, read(key));
        assertEquals(STALE, evaluate().getResult(), "未失效时评估应读到缓存里的（陈旧）结果");
        return key;
    }

    /** 经主缓存读取 key：命中返回已缓存值，未命中时写入 STALE。 */
    private String read(PolicyCacheKey key) {
        PolicyEvaluationResult result = cacheManager.getPolicyResultCache()
            .<PolicyCacheKey, PolicyEvaluationResult>get(key, k -> new PolicyEvaluationResult(STALE, 1.0, false))
            .await().atMost(Duration.ofSeconds(5));
        return String.valueOf(result.getResult());
    }

    private PolicyEvaluationResult evaluate() {
        return evaluationService.evaluatePolicy(tenant, MODULE, FUNCTION, new Object[]{REQ})
            .await().atMost(Duration.ofSeconds(30));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待放行超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
