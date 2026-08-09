package io.aster.policy.replay.batch;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * What-If 重跑的**独立容量闸门**（ADR 0034 §4.8）。
 *
 * <p><b>为什么不复用 {@code EVAL_SOURCE_PERMITS}</b>：
 * §7.2 的并发上限是**同租户 1 个批次**，但**跨租户没有全局上限**——
 * 10 个 enterprise 租户同时跑万条批次，主许可池会被 What-If 吃满，
 * 正常的 {@code /evaluate} 请求开始 503。
 *
 * <p>这不是网络压力，是**容量挤占**。故 What-If 用一个更小的独立池：
 * <b>宁可 What-If 慢，不能让它拖垮正常业务。</b>
 *
 * <p>★<b>独立池是隔离，不是豁免</b>：批次 worker 仍然要过这道闸门，
 * 只是过的是自己那道。任何「批次太慢就绕过闸门」的想法都要被拒绝——
 * 那等于把隔离墙拆了。
 *
 * <p><b>池大小</b>：主池的 1/4，下限 1。主池按 {@code min(2×核数, 堆/64MB)} 算，
 * 典型 4 核 1G 环境下主池 8、What-If 池 2。批次内部本就是串行重跑，
 * 2 个并发意味着最多两个租户的批次同时推进——这是有意的保守值：
 * What-If 是**后台批处理**，延迟不敏感；线上求值才是。
 */
@ApplicationScoped
public class WhatIfCapacityGate {

    /** 主池的几分之一。改这个数前先想清楚 §4.8 的取舍。 */
    private static final int FRACTION_OF_MAIN_POOL = 4;

    private static final int PERMITS;

    /**
     * 许可总数——供重跑执行池按同一上界封顶（ADR 0034 §12.4）。
     *
     * <p>★超时后被弃的 Truffle 线程仍在跑，而许可已在 {@code finally} 释放，
     * 所以「被弃线程由许可数封顶」**不成立**——必须由执行池自身有界来兜底。
     */
    public static int permitCount() {
        return PERMITS;
    }

    static {
        // 与 PolicyEvaluationResource 同源的估算方式，避免两处口径漂移
        int cpuBound = Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int heapBound = (int) Math.max(1, maxHeapMb / 64L);
        int mainPool = Math.min(cpuBound, heapBound);
        PERMITS = Math.max(1, mainPool / FRACTION_OF_MAIN_POOL);
        Log.infof("What-If 容量闸门：%d 个许可（主池约 %d 的 1/%d）——"
            + "宁可 What-If 慢，不能拖垮线上求值",
            PERMITS, mainPool, FRACTION_OF_MAIN_POOL);
    }

    private final Semaphore permits = new Semaphore(PERMITS, true);

    /** 当前池容量，供监控与测试。 */
    public int capacity() {
        return PERMITS;
    }

    /** 当前可用许可，供监控与测试。 */
    public int available() {
        return permits.availablePermits();
    }

    /**
     * 在闸门保护下执行 {@code work}。
     *
     * <p>★许可归还写在 {@code finally}：无论正常返回还是抛异常都归还。
     * 这条教训来自 #222——当时把归还挂在异步的 {@code onTermination} 上，
     * HTTP 取消会提前归还而 worker 仍在跑，闸门形同虚设。
     * 这里是同步调用，{@code finally} 就够，**不要**引入异步 hook。
     *
     * @param timeoutMs 等待许可的上限；超时抛 {@link WhatIfThrottledException}，
     *                  由调用方归类为 {@link ReplayFailureKind#THROTTLED}
     */
    public <T> T withPermit(long timeoutMs, ThrowingSupplier<T> work) throws Exception {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new WhatIfThrottledException("等待 What-If 许可时被中断");
        }
        if (!acquired) {
            throw new WhatIfThrottledException(
                "What-If 容量闸门繁忙：" + PERMITS + " 个许可全部占用");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** 闸门拒绝——归类为可重试失败（服务端繁忙，不是用户数据的问题）。 */
    public static class WhatIfThrottledException extends RuntimeException {
        public WhatIfThrottledException(String message) {
            super(message);
        }
    }
}
