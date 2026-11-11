package io.aster.common.redis;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅就绪的确定性（真 Redis）。
 *
 * <p>回归 nightly fresh-it 的 {@code ApiKeyRevocationInvalidationIT} 间歇超时。Vert.x Redis 客户端的
 * 全部连接都跑在建客户端时的那个事件循环 L 上，而 {@code send()} 的完成回调落在<strong>调用线程</strong>的
 * 粘滞上下文（事件循环 C）。Quarkus {@code ReactivePubSubCommands.subscribe} 要等这个完成回调才给连接
 * 挂消息处理器：C 稍忙时，服务端的 SUBSCRIBE 确认已在 L 上到达并因无处理器被丢弃
 * （{@code No handler waiting for message}），订阅白等满 {@link RedisBroadcastSupervisor#SUBSCRIBE_TIMEOUT}。
 * 启动期事件循环普遍繁忙，于是约半数 nightly 命中。
 *
 * <p>本测试把这个时序做成必然：每轮挑一个粘滞到 C≠L 的新线程调用连接器，并让 C 在建连期间持续繁忙
 * （每次占用 {@link #CALLER_LOOP_STALL}）。旧实现每轮都会超时失败；先挂处理器再订阅的实现每轮都应在
 * 超时内返回，且返回时服务端已登记订阅者（PUBLISH 接收方计数为 1，消息必达）。
 */
@QuarkusTest
@RedisEnabledTest
class RedisBroadcastSupervisorIT {

    private static final int ROUNDS = 3;

    /** 为找到 C≠L 的调用线程最多尝试的新线程数（新线程按轮转分到事件循环，相邻两个必不同）。 */
    private static final int MAX_CALLER_CANDIDATES = 10;

    private static final Duration CALLER_LOOP_STALL = Duration.ofMillis(100);

    @Inject
    RedisBroadcastSupervisor supervisor;

    @Inject
    RedisDataSource redis;

    @Inject
    Vertx vertx;

    @Test
    void connectIsConfirmedByServerEvenWhenCallerLoopIsBusy() throws Exception {
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
                verifyRound(rounds++, caller, callerLoop);
            } finally {
                caller.shutdownNow();
            }
        }
        assertEquals(ROUNDS, rounds, "应找到足够多 C≠L 的调用线程");
    }

    private void verifyRound(int round, ExecutorService caller, Context callerLoop) throws Exception {
        String channel = "aster:test:broadcast-ready:" + UUID.randomUUID();
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        ResilientRedisSubscriber.Subscription sub = caller.submit(() -> connectWhileBusy(callerLoop, channel, received))
            .get(RedisBroadcastSupervisor.SUBSCRIBE_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS);
        try {
            assertEquals(1L, publish(channel, "payload-" + round),
                "第 " + round + " 轮：connect 返回时服务端应已登记订阅者");
            assertEquals("payload-" + round, received.poll(5, TimeUnit.SECONDS),
                "第 " + round + " 轮：订阅回调应收到消息");
        } finally {
            sub.close();
        }
        // 退订与 PUBLISH 走不同连接，服务端处理先后不保证，故等待而非立即断言。
        await().atMost(Duration.ofSeconds(5)).until(() -> publish(channel, "after-close") == 0L);
    }

    /** 在调用线程上（其粘滞上下文即 callerLoop）：让 callerLoop 持续繁忙，再建立订阅；校验未接近超时上限。 */
    private ResilientRedisSubscriber.Subscription connectWhileBusy(Context callerLoop, String channel,
                                                                   LinkedBlockingQueue<String> received)
            throws Exception {
        AtomicBoolean busy = new AtomicBoolean(true);
        CountDownLatch stalled = new CountDownLatch(1);
        keepBusy(callerLoop, busy, stalled);
        assertTrue(stalled.await(5, TimeUnit.SECONDS), "调用方事件循环应已进入繁忙");
        try {
            long start = System.nanoTime();
            ResilientRedisSubscriber.Subscription sub = supervisor.redisConnector(channel, received::add)
                .connect(() -> { });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < RedisBroadcastSupervisor.SUBSCRIBE_TIMEOUT.toMillis(),
                "建连耗时 " + elapsedMillis + "ms，不应接近超时上限");
            return sub;
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

    /** 订阅消息回调运行在连接所在的事件循环 L 上：用一次探测订阅取得 L 的线程名。 */
    private String connectionLoopThreadName() throws Exception {
        String channel = "aster:test:broadcast-probe:" + UUID.randomUUID();
        AtomicReference<String> loop = new AtomicReference<>();
        ResilientRedisSubscriber.Subscription probe = supervisor.redisConnector(channel,
            ignored -> loop.set(Thread.currentThread().getName())).connect(() -> { });
        try {
            publish(channel, "probe");
            await().atMost(Duration.ofSeconds(5)).until(() -> loop.get() != null);
        } finally {
            probe.close();
        }
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

    private long publish(String channel, String message) {
        return redis.execute("PUBLISH", channel, message).toLong();
    }
}
