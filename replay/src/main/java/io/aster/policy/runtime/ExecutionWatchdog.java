package io.aster.policy.runtime;

import org.graalvm.polyglot.Context;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 策略执行的墙钟看门狗：到点对 Polyglot Context 调用 {@code close(true)}，中断仍在执行的 guest 代码。
 *
 * <p>这是 Aster 策略执行**唯一可靠**的时间上界。Aster 允许无界递归（图灵完备），而
 * {@code statementLimit} 在本语言上不触发（AST 无 StatementTag，见 issue #235）；深递归会被
 * JVM 栈溢出拦下，但浅而宽的指数分支递归不会——它可以合法地运行数小时。
 *
 * <p>为什么用 {@code close(true)} 而不是 {@code Thread.interrupt()}：Truffle 不响应 Java 中断，
 * 只有 {@code close(cancelIfExecuting=true)} 能让执行线程在下一个安全点抛出取消异常。
 * 被看门狗关闭过的 Context 不可再用，调用方必须丢弃它。
 *
 * <p>生产 {@code /evaluate}（池化 Context）与 {@code DynamicCnlExecutor}（每次新建 Context，
 * 服务 {@code /evaluate-source}、{@code /evaluate-json} 与 What-If 重跑）共用本类，保证两条路径的
 * 上界语义一致。
 */
public final class ExecutionWatchdog {

    /** 超时配置的系统属性名（毫秒），两条执行路径共用同一口径。 */
    public static final String TIMEOUT_PROPERTY = "aster.policy.execution-timeout-ms";

    /**
     * 缺省上界：合法策略典型 &lt; 50ms，5s 留足冷启动与 GC 抖动余量，
     * 同时把失控执行的影响限制在单个请求。
     */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /**
     * 单线程 daemon 调度器：只负责“到点关闭 Context”，不承载业务。
     * 线程名沿用生产路径既有命名，便于在线程转储中识别。
     */
    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aster-policy-watchdog");
            t.setDaemon(true);
            return t;
        });

    private ExecutionWatchdog() {
    }

    /** 读取当前配置的超时（毫秒）；非正值视为配置错误，回落到缺省值，绝不允许“无上界”。 */
    public static long configuredTimeoutMs() {
        long configured = Long.getLong(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS);
        return configured > 0 ? configured : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 为 ctx 布防：timeoutMs 后若尚未 {@link Guard#disarm()}，即调用 {@code ctx.close(true)}。
     *
     * @return 必须在执行结束后（finally 中）解除的守卫
     */
    public static Guard arm(Context ctx, long timeoutMs) {
        Guard guard = new Guard(timeoutMs);
        guard.task = SCHEDULER.schedule(() -> guard.fire(ctx), timeoutMs, TimeUnit.MILLISECONDS);
        return guard;
    }

    /**
     * 一次执行的看门狗守卫。
     *
     * <p>「触发」与「解除」用一次 CAS 决出唯一胜者，二者互斥：解除成功后看门狗绝不会再关闭 Context；
     * 触发成功后 {@link #disarm()} 必然返回 true。不能用 {@code ScheduledFuture.cancel} 的返回值判定——
     * {@code close(true)} 会阻塞到执行线程退出，期间 future 仍处于未完成状态，cancel 照样返回 true，
     * 调用方便会把已关闭的 Context 误当成正常结束。
     */
    public static final class Guard implements AutoCloseable {
        private static final int ARMED = 0;
        private static final int FIRED = 1;
        private static final int DISARMED = 2;

        private final AtomicInteger state = new AtomicInteger(ARMED);
        private final long timeoutMs;
        private volatile ScheduledFuture<?> task;

        private Guard(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        private void fire(Context ctx) {
            if (!state.compareAndSet(ARMED, FIRED)) {
                return;
            }
            try {
                ctx.close(true);
            } catch (Exception ignored) {
                // 关闭本身失败不再上抛：此时已在超时路径上，执行线程会因 Context 失效而退出
            }
        }

        /**
         * 解除看门狗（幂等）。
         *
         * @return true 表示看门狗已触发（Context 已被或正被关闭，必须丢弃）
         */
        public boolean disarm() {
            state.compareAndSet(ARMED, DISARMED);
            ScheduledFuture<?> scheduled = task;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            return state.get() == FIRED;
        }

        public long timeoutMs() {
            return timeoutMs;
        }

        @Override
        public void close() {
            disarm();
        }
    }
}
