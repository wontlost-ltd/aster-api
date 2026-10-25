package io.aster.common.redis;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands.ReactiveRedisSubscriber;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Redis pub/sub 广播订阅的统一监护者。
 *
 * <p>各服务（apikey 失效、BYOK allowlist、lexicon 可用性、ui-messages 热刷新）不再在
 * {@code StartupEvent} 里一次性 SUBSCRIBE，而是交给本组件：订阅在后台单线程上异步建立，
 * 失败或断线后按 1s → 60s 有界指数退避（带抖动）持续重试，不阻塞启动；停机时取消全部
 * 待执行重试并退订。
 *
 * <p>每个频道暴露一个 gauge {@code aster_redis_broadcast_up{channel=...}}：1 = 订阅已建立，
 * 0 = down（调用方正走降级兜底），可据此告警。
 *
 * <p>订阅使用 reactive API 并自带 {@link #SUBSCRIBE_TIMEOUT}，而非阻塞 API 继承的
 * {@code quarkus.redis.timeout}（默认 100ms）——后者对冷启动建连 + SUBSCRIBE 太短。
 */
@ApplicationScoped
public class RedisBroadcastSupervisor {

    static final String METRIC_NAME = "aster_redis_broadcast_up";

    /** 单次建连 + SUBSCRIBE 的等待上限。 */
    static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);

    /** 退订等待上限：停机路径上逐频道同步退订，取短值以免拖慢停机。 */
    static final Duration UNSUBSCRIBE_TIMEOUT = Duration.ofSeconds(2);

    /** Redis 瞬时故障的统一重试退避（1s → 60s，带抖动）；订阅之外的 Redis 读重试（如 ui-messages 覆盖层）也复用它。 */
    public static final ResilientRedisSubscriber.Backoff DEFAULT_BACKOFF = new ResilientRedisSubscriber.Backoff(
        Duration.ofSeconds(1), Duration.ofSeconds(60), () -> ThreadLocalRandom.current().nextDouble());

    /** 为频道构造一次性订阅动作；测试可替换为假实现。 */
    @FunctionalInterface
    interface ConnectorFactory {
        ResilientRedisSubscriber.Connector create(String channel, Consumer<String> onMessage);
    }

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    MeterRegistry registry;

    ConnectorFactory connectorFactory = this::redisConnector;
    ResilientRedisSubscriber.Backoff backoff = DEFAULT_BACKOFF;

    private final Map<String, ResilientRedisSubscriber> subscribers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean shutdown;

    /**
     * 为频道启动自愈订阅，立即返回（首次订阅在后台进行）。同一频道重复调用会替换并关闭旧订阅。
     *
     * @param name      日志标记名，输出为 {@code [name=up|down]}
     * @param channel   Redis 频道
     * @param onMessage 消息回调（运行在 Vert.x 事件循环上，耗时逻辑须自行转交线程池）
     * @param onUp      每次（重新）订阅成功后执行，可为 null；用于补齐 down 期间漏收的消息
     * @return 订阅句柄；调用方停机时应 {@link ResilientRedisSubscriber#close()}
     */
    public synchronized ResilientRedisSubscriber subscribe(String name, String channel,
                                                           Consumer<String> onMessage, Runnable onUp) {
        ResilientRedisSubscriber sub = new ResilientRedisSubscriber(name, channel,
            connectorFactory.create(channel, onMessage), scheduler(), backoff, onUp);
        ResilientRedisSubscriber previous = subscribers.put(channel, sub);
        if (previous != null) {
            previous.close();
        }
        registerGauge(channel);
        if (!shutdown) {
            sub.start();
        }
        return sub;
    }

    /** 某频道的订阅当前是否已建立（未登记的频道视为 down）。 */
    public boolean isUp(String channel) {
        ResilientRedisSubscriber sub = subscribers.get(channel);
        return sub != null && sub.isUp();
    }

    /**
     * 停机尽早关闭：ShutdownEvent 先于 Redis 客户端关闭触发，先摘掉全部订阅，
     * 避免随后的连接关闭被误判为断线而打 WARN 并安排重试。
     */
    void onShutdown(@Observes ShutdownEvent event) {
        shutdown();
    }

    @PreDestroy
    synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        subscribers.values().forEach(ResilientRedisSubscriber::close);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-broadcast-supervisor");
                t.setDaemon(true);
                return t;
            });
        }
        return scheduler;
    }

    /** gauge 按频道名回查当前订阅，替换订阅后无需重新注册。 */
    private void registerGauge(String channel) {
        if (registry == null) {
            return;
        }
        Gauge.builder(METRIC_NAME, this, s -> s.isUp(channel) ? 1.0 : 0.0)
            .tag("channel", channel)
            .description("Redis pub/sub 广播订阅是否已建立（1=up, 0=down）")
            .register(registry);
    }

    private ResilientRedisSubscriber.Connector redisConnector(String channel, Consumer<String> onMessage) {
        return onDrop -> {
            ReactiveRedisSubscriber sub = redisDataSource.get().getReactive().pubsub(String.class)
                .subscribe(channel, onMessage, onDrop, error -> onDrop.run())
                .await().atMost(SUBSCRIBE_TIMEOUT);
            return () -> sub.unsubscribe().await().atMost(UNSUBSCRIBE_TIMEOUT);
        };
    }
}
