package io.aster.common.redis;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * 单个 Redis pub/sub 频道的自愈订阅。
 *
 * <p>订阅失败（如冷启动时 SUBSCRIBE 超时）或已建立的连接断开后，按有界指数退避（带抖动）
 * 持续重试，直到成功或被 {@link #close()}。恢复时打一条 INFO 日志；当前状态由 {@link #isUp()}
 * 暴露，供指标 / 健康检查读取。订阅 down 期间调用方的降级兜底（TTL / 定时对账）照常生效。
 *
 * <p>线程模型：建立订阅与断线处理都串行在注入的<strong>单线程</strong>调度器上执行，
 * 因此 {@code failures} / {@code downSinceMillis} 无需加锁；{@link #close()} 可从任意线程调用，
 * 与安装订阅之间用对象锁互斥，保证关闭后不会再遗留活跃订阅或待执行的重试。
 */
public final class ResilientRedisSubscriber {

    private static final Logger LOG = Logger.getLogger(ResilientRedisSubscriber.class);

    /** 建立一次订阅：阻塞直到 SUBSCRIBE 被确认，失败抛异常；连接结束/异常时回调 {@code onDrop}。 */
    @FunctionalInterface
    public interface Connector {
        Subscription connect(Runnable onDrop) throws Exception;
    }

    /** 已建立订阅的关闭句柄。 */
    @FunctionalInterface
    public interface Subscription {
        void close() throws Exception;
    }

    /**
     * 有界指数退避：第 n 次失败的上限为 {@code min(initial * 2^(n-1), max)}，实际延迟在
     * [上限/2, 上限] 内随机，避免多副本在 Redis 恢复瞬间同频重连。
     */
    public record Backoff(Duration initial, Duration max, DoubleSupplier random) {

        public long delayMillis(int failures) {
            int shift = Math.min(Math.max(failures - 1, 0), 20);
            long capped = Math.min(initial.toMillis() << shift, max.toMillis());
            long half = capped / 2;
            return half + (long) (random.getAsDouble() * (capped - half));
        }
    }

    private final String name;
    private final String channel;
    private final Connector connector;
    private final ScheduledExecutorService scheduler;
    private final Backoff backoff;
    private final Runnable onUp;

    /** 每次尝试递增；断线回调携带所属代次，过期代次的回调直接忽略。 */
    private final AtomicLong generation = new AtomicLong();

    private volatile boolean up;
    private volatile boolean closed;
    private Subscription current;
    private ScheduledFuture<?> pending;

    /** 仅在调度线程读写；downSinceMillis 只用于恢复日志里的 down 时长。 */
    private int failures;
    private long downSinceMillis;

    /**
     * @param name      日志标记名（如 {@code apikey_invalidate_broadcast}），输出为 {@code [name=up|down]}
     * @param channel   订阅的频道
     * @param connector 建立一次订阅的动作
     * @param scheduler 单线程调度器（由调用方管理生命周期）
     * @param backoff   重试退避策略
     * @param onUp      每次（重新）订阅成功后在调度线程执行，可为 null；用于补齐 down 期间漏收的消息
     */
    public ResilientRedisSubscriber(String name, String channel, Connector connector,
                                    ScheduledExecutorService scheduler, Backoff backoff, Runnable onUp) {
        this.name = name;
        this.channel = channel;
        this.connector = connector;
        this.scheduler = scheduler;
        this.backoff = backoff;
        this.onUp = onUp;
    }

    /** 异步发起首次订阅，立即返回，不阻塞调用方（启动路径）。 */
    public void start() {
        schedule(0L);
    }

    /** 订阅当前是否已建立。 */
    public boolean isUp() {
        return up;
    }

    public String channel() {
        return channel;
    }

    /** 停止：取消待执行的重试并退订。幂等，可从任意线程调用。 */
    public void close() {
        Subscription toClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            up = false;
            if (pending != null) {
                pending.cancel(false);
            }
            toClose = current;
            current = null;
        }
        closeQuietly(toClose);
    }

    private synchronized void schedule(long delayMillis) {
        if (closed) {
            return;
        }
        try {
            pending = scheduler.schedule(this::attempt, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debugf("调度器已关闭，放弃重试 channel=%s", channel);
        }
    }

    private void attempt() {
        if (closed) {
            return;
        }
        long gen = generation.incrementAndGet();
        Subscription sub;
        try {
            sub = connector.connect(() -> dropped(gen));
        } catch (Exception e) {
            onFailure(e);
            return;
        }
        onConnected(sub);
    }

    private void onConnected(Subscription sub) {
        if (!install(sub)) {
            closeQuietly(sub);
            return;
        }
        logUp();
        failures = 0;
        downSinceMillis = 0L;
        runOnUp();
    }

    private synchronized boolean install(Subscription sub) {
        if (closed) {
            return false;
        }
        current = sub;
        up = true;
        return true;
    }

    private void onFailure(Exception e) {
        failures++;
        if (downSinceMillis == 0L) {
            downSinceMillis = System.currentTimeMillis();
        }
        long delay = backoff.delayMillis(failures);
        if (failures == 1) {
            LOG.warnf("Redis 广播订阅失败，%dms 后重试（期间走降级兜底） channel=%s [%s=down]: %s",
                delay, channel, name, describe(e));
        } else {
            LOG.debugf("Redis 广播订阅重试失败 #%d，%dms 后再试 channel=%s: %s",
                failures, delay, channel, describe(e));
        }
        schedule(delay);
    }

    /** 连接结束/异常回调（来自 Vert.x 事件循环），转交调度线程串行处理。 */
    private void dropped(long gen) {
        try {
            scheduler.execute(() -> handleDrop(gen));
        } catch (RejectedExecutionException e) {
            LOG.debugf("调度器已关闭，忽略断线事件 channel=%s", channel);
        }
    }

    private void handleDrop(long gen) {
        Subscription stale = detach(gen);
        if (stale == null) {
            return;
        }
        closeQuietly(stale);
        failures = 1;
        downSinceMillis = System.currentTimeMillis();
        long delay = backoff.delayMillis(failures);
        LOG.warnf("Redis 广播订阅连接断开，%dms 后重订阅（期间走降级兜底） channel=%s [%s=down]",
            delay, channel, name);
        schedule(delay);
    }

    /** 若断线事件属于当前活跃订阅则摘下并返回它；过期 / 已关闭 / 本就 down 时返回 null。 */
    private synchronized Subscription detach(long gen) {
        if (closed || !up || gen != generation.get()) {
            return null;
        }
        Subscription stale = current;
        current = null;
        up = false;
        return stale;
    }

    private void logUp() {
        if (downSinceMillis == 0L) {
            LOG.infof("Redis 广播订阅已建立: %s [%s=up]", channel, name);
            return;
        }
        long downMillis = System.currentTimeMillis() - downSinceMillis;
        LOG.infof("Redis 广播订阅已恢复: %s（down %dms，失败 %d 次） [%s=up]",
            channel, downMillis, failures, name);
    }

    private void runOnUp() {
        if (onUp == null) {
            return;
        }
        try {
            onUp.run();
        } catch (RuntimeException e) {
            LOG.warnf("Redis 广播订阅恢复回调失败 channel=%s: %s", channel, describe(e));
        }
    }

    private void closeQuietly(Subscription sub) {
        if (sub == null) {
            return;
        }
        try {
            sub.close();
        } catch (Exception e) {
            LOG.debugf("Redis 广播退订异常（忽略） channel=%s: %s", channel, describe(e));
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
