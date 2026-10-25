package io.aster.common.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 自愈订阅单测：用假 Connector 模拟 SUBSCRIBE 失败 / 成功 / 断线，验证退避重试、恢复、
 * 指标翻转以及关闭后不再重试。退避缩到毫秒级以保证测试快速。
 */
@DisplayName("ResilientRedisSubscriber / RedisBroadcastSupervisor 自愈订阅")
class ResilientRedisSubscriberTest {

    private static final ResilientRedisSubscriber.Backoff FAST =
        new ResilientRedisSubscriber.Backoff(Duration.ofMillis(5), Duration.ofMillis(20), () -> 1.0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /** 前 failTimes 次抛异常，之后成功；记录尝试次数、关闭次数与最近一次的断线回调。 */
    static final class FlakyConnector implements ResilientRedisSubscriber.Connector {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<Runnable> lastOnDrop = new AtomicReference<>();
        volatile int failTimes;

        FlakyConnector(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public ResilientRedisSubscriber.Subscription connect(Runnable onDrop) {
            if (attempts.incrementAndGet() <= failTimes) {
                throw new IllegalStateException("模拟 SUBSCRIBE 超时");
            }
            lastOnDrop.set(onDrop);
            return closes::incrementAndGet;
        }
    }

    private ResilientRedisSubscriber newSubscriber(FlakyConnector connector, Runnable onUp) {
        return new ResilientRedisSubscriber("test_broadcast", "ch", connector, scheduler, FAST, onUp);
    }

    @Test
    @DisplayName("失败 N 次后成功 → up，onUp 执行一次")
    void retriesUntilSubscribed() {
        FlakyConnector connector = new FlakyConnector(3);
        AtomicInteger ups = new AtomicInteger();
        ResilientRedisSubscriber sub = newSubscriber(connector, ups::incrementAndGet);

        sub.start();

        await().atMost(Duration.ofSeconds(5)).until(sub::isUp);
        assertThat(connector.attempts.get()).isEqualTo(4);
        assertThat(ups.get()).isEqualTo(1);
        sub.close();
    }

    @Test
    @DisplayName("start 不阻塞调用方")
    void startDoesNotBlock() {
        ResilientRedisSubscriber.Connector slow = onDrop -> {
            Thread.sleep(2_000);
            return () -> { };
        };
        ResilientRedisSubscriber sub =
            new ResilientRedisSubscriber("test_broadcast", "ch", slow, scheduler, FAST, null);

        long t0 = System.nanoTime();
        sub.start();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(500));
        assertThat(sub.isUp()).isFalse();
        sub.close();
    }

    @Test
    @DisplayName("断线后自动重订阅，onUp 再次执行")
    void resubscribesAfterDrop() {
        FlakyConnector connector = new FlakyConnector(0);
        AtomicInteger ups = new AtomicInteger();
        ResilientRedisSubscriber sub = newSubscriber(connector, ups::incrementAndGet);
        sub.start();
        await().atMost(Duration.ofSeconds(5)).until(sub::isUp);

        // 断线期间再失败 2 次，验证断线后也走退避重试。
        connector.failTimes = connector.attempts.get() + 2;
        connector.lastOnDrop.get().run();

        await().atMost(Duration.ofSeconds(5)).until(() -> ups.get() == 2);
        assertThat(sub.isUp()).isTrue();
        assertThat(connector.attempts.get()).isEqualTo(4);
        assertThat(connector.closes.get()).as("断开的旧订阅应被关闭").isEqualTo(1);
        sub.close();
    }

    @Test
    @DisplayName("过期代次的断线回调被忽略")
    void staleDropIgnored() {
        FlakyConnector connector = new FlakyConnector(0);
        ResilientRedisSubscriber sub = newSubscriber(connector, null);
        sub.start();
        await().atMost(Duration.ofSeconds(5)).until(sub::isUp);
        Runnable firstDrop = connector.lastOnDrop.get();
        firstDrop.run();
        await().atMost(Duration.ofSeconds(5)).until(() -> connector.attempts.get() == 2 && sub.isUp());

        firstDrop.run();

        await().pollDelay(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(sub.isUp()).isTrue();
        assertThat(connector.attempts.get()).isEqualTo(2);
        sub.close();
    }

    @Test
    @DisplayName("close 后取消待执行重试，不再尝试")
    void closeStopsRetries() {
        FlakyConnector connector = new FlakyConnector(Integer.MAX_VALUE);
        ResilientRedisSubscriber sub = newSubscriber(connector, null);
        sub.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> connector.attempts.get() >= 2);

        sub.close();
        int afterClose = connector.attempts.get();

        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(connector.attempts.get()).isLessThanOrEqualTo(afterClose + 1);
        assertThat(sub.isUp()).isFalse();
    }

    @Test
    @DisplayName("close 退订已建立的订阅，之后的断线回调不触发重订阅")
    void closeUnsubscribes() {
        FlakyConnector connector = new FlakyConnector(0);
        ResilientRedisSubscriber sub = newSubscriber(connector, null);
        sub.start();
        await().atMost(Duration.ofSeconds(5)).until(sub::isUp);

        sub.close();
        connector.lastOnDrop.get().run();

        await().pollDelay(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(connector.closes.get()).isEqualTo(1);
        assertThat(connector.attempts.get()).isEqualTo(1);
        assertThat(sub.isUp()).isFalse();
    }

    @Test
    @DisplayName("退避：指数增长、封顶、抖动落在 [上限/2, 上限]")
    void backoffBounded() {
        ResilientRedisSubscriber.Backoff max = new ResilientRedisSubscriber.Backoff(
            Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.999_999);
        ResilientRedisSubscriber.Backoff min = new ResilientRedisSubscriber.Backoff(
            Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.0);

        assertThat(max.delayMillis(1)).isBetween(999L, 1000L);
        assertThat(max.delayMillis(3)).isBetween(3999L, 4000L);
        assertThat(max.delayMillis(50)).isBetween(59_999L, 60_000L);
        assertThat(min.delayMillis(1)).isEqualTo(500L);
        assertThat(min.delayMillis(50)).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("supervisor：gauge aster_redis_broadcast_up 随订阅状态 0→1→0，shutdown 停止重试")
    void supervisorGaugeAndShutdown() {
        FlakyConnector connector = new FlakyConnector(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisBroadcastSupervisor supervisor = new RedisBroadcastSupervisor();
        supervisor.registry = registry;
        supervisor.backoff = FAST;
        supervisor.connectorFactory = (channel, onMessage) -> connector;

        supervisor.subscribe("test_broadcast", "aster:test:ch", msg -> { }, null);
        double initial = registry.get(RedisBroadcastSupervisor.METRIC_NAME)
            .tag("channel", "aster:test:ch").gauge().value();
        assertThat(initial).isZero();

        await().atMost(Duration.ofSeconds(5)).until(() -> gauge(registry) == 1.0);
        assertThat(supervisor.isUp("aster:test:ch")).isTrue();

        connector.failTimes = Integer.MAX_VALUE;
        connector.lastOnDrop.get().run();
        await().atMost(Duration.ofSeconds(5)).until(() -> gauge(registry) == 0.0);

        supervisor.shutdown();
        int afterShutdown = connector.attempts.get();
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(connector.attempts.get()).isLessThanOrEqualTo(afterShutdown + 1);
        assertThat(connector.closes.get()).isEqualTo(1);
    }

    private static double gauge(SimpleMeterRegistry registry) {
        return registry.get(RedisBroadcastSupervisor.METRIC_NAME).tag("channel", "aster:test:ch").gauge().value();
    }
}
