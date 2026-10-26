package io.aster.policy.replay.batch;

import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.replay.ReplayExecutorAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What-If 重跑的执行上界：失控策略必须被**真正中止**（执行线程释放），而不只是让调用者停止等待。
 *
 * <p>走与生产相同的链路：ReplayBatchService.runWithTimeout（共享有界线程池 + 外层等待上限）
 * → ReplayExecutorAdapter → DynamicCnlExecutor（墙钟看门狗）。断言三点：
 * 远早于外层 60s 等待上限返回；失败归类为 TIMEOUT；重跑线程池里没有残留的在跑线程。
 */
class ReplayWatchdogTimeoutTest {

    private static final String EXPONENTIAL_SOURCE = """
        Module watchdog.whatif.

        Rule boom given n as Int, produce Int:
          If n equals to 0:
            Return 1.
          Return boom(n minus 1) plus boom(n minus 1).
        """;

    /** 看门狗缺省上限 5s + 余量，远小于外层 60s 等待上限。 */
    private static final long MAX_ELAPSED_MS = 5_000 + 7_000;

    @Test
    void 失控重跑被看门狗中止_归为TIMEOUT_且不残留执行线程() throws Exception {
        ReplayExecutorAdapter adapter = newAdapter();
        Callable<Object> work = () -> adapter.execute("tenant-whatif", EXPONENTIAL_SOURCE,
            Map.of("n", 40), "boom", "en-US", null, false, Map.of(), true);

        long start = System.currentTimeMillis();
        Exception failure = runWithTimeout(work);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(failure).as("失控重跑必须以异常结束").isNotNull();
        assertThat(elapsed).as("应由看门狗在上限附近中止，而非等外层 60s").isLessThan(MAX_ELAPSED_MS);
        assertThat(classify(failure)).isEqualTo(ReplayFailureKind.TIMEOUT);
        assertThat(awaitIdlePool()).as("执行线程必须随中止释放，不能在后台继续占用 CPU").isZero();
    }

    @Test
    void 看门狗超时异常归为TIMEOUT而非输入不兼容() throws Exception {
        Exception wrapped = new RuntimeException("包装",
            DynamicCnlExecutor.DynamicExecutionException.timeout(5_000, null));
        assertThat(classify(wrapped)).isEqualTo(ReplayFailureKind.TIMEOUT);
    }

    private static ReplayExecutorAdapter newAdapter() throws Exception {
        ReplayExecutorAdapter adapter = new ReplayExecutorAdapter();
        Field f = ReplayExecutorAdapter.class.getDeclaredField("dynamicCnlExecutor");
        f.setAccessible(true);
        f.set(adapter, new DynamicCnlExecutor());
        return adapter;
    }

    private static Exception runWithTimeout(Callable<Object> work) throws Exception {
        Method m = ReplayBatchService.class.getDeclaredMethod("runWithTimeout", Callable.class);
        m.setAccessible(true);
        try {
            m.invoke(null, work);
            return null;
        } catch (InvocationTargetException e) {
            return (Exception) e.getCause();
        }
    }

    private static ReplayFailureKind classify(Exception ex) throws Exception {
        Method m = ReplayBatchService.class.getDeclaredMethod("classify", Exception.class);
        m.setAccessible(true);
        return (ReplayFailureKind) m.invoke(null, ex);
    }

    /** 重跑线程池的在跑任务数；Context 关闭到线程退出之间有短暂窗口，轮询等待。 */
    private static int awaitIdlePool() throws Exception {
        Field f = ReplayBatchService.class.getDeclaredField("REPLAY_POOL");
        f.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) f.get(null);
        long deadline = System.currentTimeMillis() + 3_000;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return pool.getActiveCount();
    }
}
