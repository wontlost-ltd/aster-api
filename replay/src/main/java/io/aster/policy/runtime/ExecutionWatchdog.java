package io.aster.policy.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.jboss.logging.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
 * <p>线程分工：调度线程只负责计时与状态翻转（瞬时完成）；真正的 {@code close(true)} 会阻塞到
 * 执行线程退出，交给独立的关闭线程执行——一次慢关闭不会推迟其他到期的超时。
 *
 * <p>生产 {@code /evaluate}（池化 Context）与 {@code DynamicCnlExecutor}（每次新建 Context，
 * 服务 {@code /evaluate-source}、{@code /evaluate-json} 与 What-If 重跑）共用本类，保证两条路径的
 * 上界语义一致。
 */
public final class ExecutionWatchdog {

    private static final Logger LOG = Logger.getLogger(ExecutionWatchdog.class);

    /** 超时配置的系统属性名（毫秒），两条执行路径共用同一口径。 */
    public static final String TIMEOUT_PROPERTY = "aster.policy.execution-timeout-ms";

    /**
     * 缺省上界：合法策略典型 &lt; 50ms，5s 留足冷启动与 GC 抖动余量，
     * 同时把失控执行的影响限制在单个请求。
     */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /** 单线程 daemon 调度器：只计时、翻转状态、把关闭动作派发出去，从不在此线程上阻塞。 */
    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aster-policy-watchdog");
            t.setDaemon(true);
            return t;
        });

    /**
     * 关闭执行器：按需扩容的 daemon 平台线程池（空闲 30s 回收）。不设固定上限是刻意的——
     * 固定 N 个线程时，N 次慢关闭就会让第 N+1 次排队；而并发关闭数天然受「同时在跑的 Context 数」
     * 约束（各执行入口的许可 / 池容量），线程数因此有界。
     * 不用虚拟线程：GraalVM 在 HotSpot 上对虚拟线程操作 Context 仍标记为实验性。
     */
    private static final Executor CLOSER = new ThreadPoolExecutor(
        0, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "aster-policy-watchdog-close");
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
     * 为 ctx 布防：timeoutMs 后若尚未 {@link Guard#disarm()}，即异步调用 {@code ctx.close(true)}。
     *
     * @return 必须在执行结束后（finally 中）解除的守卫
     */
    public static Guard arm(Context ctx, long timeoutMs) {
        return arm(() -> ctx.close(true), timeoutMs);
    }

    /** 以任意取消动作布防；生产只经 {@link #arm(Context, long)} 使用，包级可见供单测注入。 */
    static Guard arm(Runnable cancel, long timeoutMs) {
        Guard guard = new Guard(cancel, timeoutMs);
        guard.task = SCHEDULER.schedule(guard::fire, timeoutMs, TimeUnit.MILLISECONDS);
        return guard;
    }

    /**
     * 一次执行的看门狗守卫。
     *
     * <p>期限边界的语义（「触发」与「解除」用一次 CAS 决出唯一胜者）：
     * <ul>
     *   <li>解除先赢：看门狗绝不会再关闭 Context，执行按其真实结果返回；</li>
     *   <li>触发先赢：FIRED 不可逆，{@link #disarm()} 恒返回 true，Context 必须丢弃。
     *       若 guest 恰好已正常完成，结果照常返回（结果在关闭前已转成宿主值），只是 Context 不得复用；</li>
     *   <li>失败归因：仅当已触发**且**失败确由取消引起（{@link #isCancellation}）才算超时，
     *       其余异常（含关闭本身失败后 guest 自己抛出的错误）按真实错误上报。</li>
     * </ul>
     */
    public static final class Guard implements AutoCloseable {
        private static final int ARMED = 0;
        private static final int FIRED = 1;
        private static final int DISARMED = 2;

        private final AtomicInteger state = new AtomicInteger(ARMED);
        private final Runnable cancel;
        private final long timeoutMs;
        private volatile ScheduledFuture<?> task;

        private Guard(Runnable cancel, long timeoutMs) {
            this.cancel = cancel;
            this.timeoutMs = timeoutMs;
        }

        private void fire() {
            if (state.compareAndSet(ARMED, FIRED)) {
                CLOSER.execute(this::runCancel);
            }
        }

        private void runCancel() {
            try {
                cancel.run();
            } catch (Exception e) {
                // 关闭失败意味着 guest 可能仍在运行：记录以便观测，执行方会按其真实结果/错误收尾
                LOG.warnf(e, "看门狗关闭 Context 失败（上限 %dms）", timeoutMs);
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

        /** 该失败是否应归为超时：看门狗已触发，且失败由取消引起。会顺带解除看门狗。 */
        public boolean timedOut(Throwable failure) {
            return disarm() && isCancellation(failure);
        }

        public long timeoutMs() {
            return timeoutMs;
        }

        @Override
        public void close() {
            disarm();
        }
    }

    /**
     * 失败是否由 Context 被关闭/取消引起：沿 cause 链找已取消或被中断的 {@link PolyglotException}，
     * 或 Context 已关闭时 polyglot API 抛出的 {@link IllegalStateException}
     * （关闭发生在两次 guest 调用之间时，下一次调用拿到的就是它）。
     */
    static boolean isCancellation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PolyglotException pe && (pe.isCancelled() || pe.isInterrupted())) {
                return true;
            }
            if (t instanceof IllegalStateException) {
                return true;
            }
        }
        return false;
    }
}
