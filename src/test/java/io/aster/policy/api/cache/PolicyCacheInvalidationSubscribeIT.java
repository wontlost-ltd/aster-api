package io.aster.policy.api.cache;

import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.test.RedisEnabledTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * policy-cache 跨实例失效订阅的确定性（真 Redis）。
 *
 * <p>回归 nightly fresh-it 的 {@code PolicyEvaluationGateIT} 启动日志：
 * {@code No handler waiting for message: [subscribe, policy-cache:invalidate, 1]} 随后
 * {@code 初始化 Redis 缓存失效订阅失败: TimeoutException}。成因与 {@code RedisBroadcastSupervisorIT} 相同：
 * Quarkus {@code PubSubCommands.subscribe} 要等 SUBSCRIBE 写完成回调（落在调用线程粘滞的事件循环 C）
 * 才挂处理器，而确认在连接事件循环 L 上先到达被丢弃；旧实现还只在启动时订阅一次、不跟踪也不重试。
 *
 * <p>第一个用例把时序做成必然：在粘滞到繁忙 C≠L 的线程上（重新）建立订阅，要求在超时内就绪，
 * 且随后经 Redis 直接 PUBLISH 的失效消息真正经消息路径送达（远程失效计数 +1、租户索引清空）。
 * 第二个用例只踢掉本频道的订阅连接，并在断线窗口内由「另一实例」推进租户 epoch 并广播（必然漏收），
 * 要求订阅自动恢复、继续接收失效消息，并补齐漏收：恢复后本实例读到的租户 epoch 就是新值（issue #484：
 * 结果缓存键带 epoch，旧 epoch 的条目随之不可达，见 {@link PolicyResultEpochIT}）。
 * {@link PolicyCacheInvalidationRedisBackedIT} 以 Redis 后端（生产默认）重跑同样的用例。
 *
 * <p>送达断言只认消息路径独有的副作用（远程失效计数），并在注册前排空失效执行器。
 */
@QuarkusTest
@RedisEnabledTest
class PolicyCacheInvalidationSubscribeIT {

    private static final String CHANNEL = "policy-cache:invalidate";

    private static final int ROUNDS = 3;

    /** 为找到 C≠L 的调用线程最多尝试的新线程数（新线程按轮转分到事件循环，相邻两个必不同）。 */
    private static final int MAX_CALLER_CANDIDATES = 10;

    private static final Duration CALLER_LOOP_STALL = Duration.ofMillis(100);

    /** 与 RedisBroadcastSupervisor.SUBSCRIBE_TIMEOUT 一致：单次订阅必须在此之内就绪。 */
    private static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    PolicyCacheManager cacheManager;

    @Inject
    RedisDataSource redis;

    @Inject
    Vertx vertx;

    @Inject
    MeterRegistry meterRegistry;

    private static final Duration QUEUE_IDLE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void subscriptionIsReadyAndDeliversEvenWhenCallerLoopIsBusy() throws Exception {
        String connectionLoop = connectionLoopThreadName();
        int rounds = 0;
        for (int candidate = 0; candidate < MAX_CALLER_CANDIDATES && rounds < ROUNDS; candidate++) {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Context callerLoop = caller.submit(() -> vertx.getOrCreateContext()).get(5, TimeUnit.SECONDS);
                if (connectionLoop.equals(threadNameOf(callerLoop))) {
                    // C=L 时阻塞 C 会连 I/O 一起饿死，构不成竞态，换下一个线程。
                    continue;
                }
                int round = rounds++;
                boolean up = caller.submit(() -> subscribeWhileBusy(callerLoop))
                    .get(SUBSCRIBE_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS);
                assertTrue(up, "第 " + round + " 轮：订阅应在启动等待内就绪");
                assertTrue(cacheManager.isInvalidationChannelUp(), "第 " + round + " 轮：就绪信号应为 up");
                assertRemoteInvalidationDelivered("race-" + round);
            } finally {
                caller.shutdownNow();
            }
        }
        assertEquals(ROUNDS, rounds, "应找到足够多 C≠L 的调用线程");
    }

    @Test
    void missedTenantInvalidationIsRepairedAfterReconnect() throws Exception {
        String connectionId = resubscribeAndFindConnection();
        assertRemoteInvalidationDelivered("before-drop");

        // 本实例此前已取得并缓存该租户的 epoch（订阅在线时取得，可信时长内不回源）。
        String tenant = "tenant-subscribe-it-missed-" + UUID.randomUUID();
        String epochKey = cacheManager.epochRedisKey(tenant);
        try {
            long before = cacheManager.resultEpoch(tenant).await().atMost(Duration.ofSeconds(5)).orElseThrow();
            assertEquals(OptionalLong.of(before), cacheManager.cachedEpoch(tenant));

            assertEquals(1L, redis.execute("CLIENT", "KILL", "ID", connectionId).toLong(), "应踢掉本频道订阅连接");
            // 断线窗口内另一实例推进 epoch 并广播：重订阅至少退避 500ms，此刻本频道无订阅者，消息必然漏收。
            long bumped = redis.value(Long.class).incr(epochKey);
            assertEquals(0L, publishRaw(tenantEpoch(tenant, bumped)), "断线窗口内的失效应无人接收（漏收）");
            assertEquals(OptionalLong.of(before), cacheManager.cachedEpoch(tenant), "漏收：本地 epoch 未被推送更新");

            // 恢复后：订阅重新就绪，本地 epoch 已被丢弃，下一次读取回源拿到漏收的新 epoch。
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> cacheManager.isInvalidationChannelUp() && publishRaw("probe-" + UUID.randomUUID()) >= 1);
            assertEquals(OptionalLong.of(bumped), cacheManager.resultEpoch(tenant).await().atMost(Duration.ofSeconds(5)),
                "漏收的失效应在恢复后被补齐：读到新 epoch");

            assertRemoteInvalidationDelivered("after-drop");
        } finally {
            redis.key().del(epochKey);
        }
    }

    /**
     * 重订阅本频道并找出它的服务端连接 id：对比重订阅前后的 pub/sub 连接（只看 sub=1 的频道订阅），
     * 只踢这一条，不波及同一 Redis 上其他广播订阅。若恰逢其他订阅重连导致新增不唯一，重试。
     */
    private String resubscribeAndFindConnection() {
        Set<String> added = Set.of();
        for (int attempt = 0; attempt < 3 && added.size() != 1; attempt++) {
            Set<String> before = channelSubscriberIds();
            assertTrue(cacheManager.subscribeInvalidationChannel(), "重订阅应在启动等待内就绪");
            added = new HashSet<>(channelSubscriberIds());
            added.removeAll(before);
        }
        assertEquals(1, added.size(), "重订阅应恰好新增一条 pub/sub 连接: " + added);
        return added.iterator().next();
    }

    /**
     * 注册一个租户缓存键，经 Redis 直接 PUBLISH 租户级失效（模拟另一实例），要求消息经本实例的
     * 订阅送达：远程失效计数只由消息处理路径递增，且租户索引被清掉。
     */
    private void assertRemoteInvalidationDelivered(String label) throws Exception {
        cacheManager.awaitInvalidationQueueIdle(QUEUE_IDLE_TIMEOUT);
        String tenant = "tenant-subscribe-it-" + label + "-" + UUID.randomUUID();
        var key = new PolicyCacheKey(tenant, "aster.it.module", "fn", new Object[]{"{}"});
        cacheManager.registerCacheEntry(key, tenant);
        assertTrue(cacheManager.snapshotTenantCacheKeys(tenant).contains(key), label + "：注册后索引应含该键");

        assertTrue(publishRaw(tenantWide(tenant)) >= 1, label + "：PUBLISH 时服务端应已登记失效订阅者");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(1.0, remoteInvalidations(tenant), 0.0001, label + "：远程失效消息应经订阅送达本实例"));
        cacheManager.awaitInvalidationQueueIdle(QUEUE_IDLE_TIMEOUT);
        assertFalse(cacheManager.snapshotTenantCacheKeys(tenant).contains(key), label + "：远程失效消息应清掉本实例租户索引");
    }

    private double remoteInvalidations(String tenant) {
        Counter counter = meterRegistry.find("policy_cache_remote_invalidations_total").tag("tenant", tenant).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String tenantEpoch(String tenant, long epoch) {
        return new JsonObject(tenantWide(tenant)).put("epoch", epoch).encode();
    }

    private static String tenantWide(String tenant) {
        return new JsonObject()
            .put("tenantId", tenant)
            .putNull("policyModule")
            .putNull("policyFunction")
            .encode();
    }

    /** 在调用线程上（其粘滞上下文即 callerLoop）：让 callerLoop 持续繁忙，再（重新）建立订阅；校验未接近超时上限。 */
    private boolean subscribeWhileBusy(Context callerLoop) throws Exception {
        AtomicBoolean busy = new AtomicBoolean(true);
        CountDownLatch stalled = new CountDownLatch(1);
        keepBusy(callerLoop, busy, stalled);
        assertTrue(stalled.await(5, TimeUnit.SECONDS), "调用方事件循环应已进入繁忙");
        try {
            long start = System.nanoTime();
            boolean up = cacheManager.subscribeInvalidationChannel();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < SUBSCRIBE_TIMEOUT.toMillis(),
                "订阅耗时 " + elapsedMillis + "ms，不应接近超时上限");
            return up;
        } finally {
            busy.set(false);
        }
    }

    /** 自我重投的阻塞任务：每次占用事件循环 {@link #CALLER_LOOP_STALL}，其间排队的回调只能等下一个空档。 */
    private static void keepBusy(Context loop, AtomicBoolean busy, CountDownLatch stalled) {
        loop.runOnContext(ignored -> {
            if (!busy.get()) {
                return;
            }
            stalled.countDown();
            try {
                Thread.sleep(CALLER_LOOP_STALL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            keepBusy(loop, busy, stalled);
        });
    }

    /**
     * Redis 客户端全部连接跑在同一事件循环 L 上：用一条先挂处理器再 SUBSCRIBE 的原始连接探测 L 的线程名
     * （探测本身不能走有竞态的 Quarkus subscribe）。
     */
    private String connectionLoopThreadName() throws Exception {
        String channel = "aster:test:policy-cache-probe:" + UUID.randomUUID();
        AtomicReference<String> loop = new AtomicReference<>();
        RedisConnection probe = redis.getReactive().getRedis().connect().await().atMost(SUBSCRIBE_TIMEOUT);
        try {
            probe.handler(reply -> {
                if (reply != null && reply.size() >= 3 && "message".equalsIgnoreCase(reply.get(0).toString())) {
                    loop.set(Thread.currentThread().getName());
                }
            });
            probe.send(Request.cmd(Command.SUBSCRIBE).arg(channel)).await().atMost(SUBSCRIBE_TIMEOUT);
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                redis.execute("PUBLISH", channel, "probe");
                return loop.get() != null;
            });
        } finally {
            probe.closeAndForget();
        }
        assertNotNull(loop.get());
        return loop.get();
    }

    private static String threadNameOf(Context context) throws InterruptedException {
        AtomicReference<String> name = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        context.runOnContext(ignored -> {
            name.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "事件循环应响应");
        assertNotNull(name.get());
        return name.get();
    }

    /** 当前全部「频道订阅数为 1、无模式订阅」的 pub/sub 连接 id（CLIENT LIST 每行形如 {@code id=7 addr=... sub=1 psub=0 ...}）。 */
    private Set<String> channelSubscriberIds() {
        Set<String> ids = new HashSet<>();
        for (String line : redis.execute("CLIENT", "LIST", "TYPE", "pubsub").toString().split("\\R")) {
            if (line.startsWith("id=") && line.contains(" sub=1 ") && line.contains(" psub=0 ")) {
                ids.add(line.substring(3, line.indexOf(' ')));
            }
        }
        return ids;
    }

    private long publishRaw(String payload) {
        return redis.execute("PUBLISH", CHANNEL, payload).toLong();
    }
}
