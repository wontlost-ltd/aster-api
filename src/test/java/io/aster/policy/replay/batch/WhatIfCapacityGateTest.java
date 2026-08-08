package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What-If 独立容量闸门（ADR 0034 §4.8）。
 *
 * <p><b>要守的东西</b>：跨租户的 What-If 批次不得吃满主许可池导致线上求值 503。
 * §7.2 的并发上限只管**同租户**，这道闸门管**全局**。
 *
 * <p>★许可归还必须在 {@code finally}——这条教训来自 #222：
 * 当时把归还挂在异步 hook 上，取消会提前归还而 worker 仍在跑，闸门形同虚设。
 */
class WhatIfCapacityGateTest {

    @Test
    void 池容量严格小于主池且至少为1() {
        WhatIfCapacityGate gate = new WhatIfCapacityGate();
        int cpuBound = Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int heapBound = (int) Math.max(1, maxHeapMb / 64L);
        int mainPool = Math.min(cpuBound, heapBound);

        assertThat(gate.capacity())
            .as("What-If 池必须小于主池——宁可 What-If 慢，不能拖垮线上求值")
            .isLessThan(mainPool);
        assertThat(gate.capacity()).as("下限为 1，否则功能直接不可用").isGreaterThanOrEqualTo(1);
    }

    @Test
    void 正常执行后许可归还() throws Exception {
        WhatIfCapacityGate gate = new WhatIfCapacityGate();
        int before = gate.available();

        String r = gate.withPermit(1000, () -> "ok");

        assertThat(r).isEqualTo("ok");
        assertThat(gate.available()).as("正常返回后必须归还").isEqualTo(before);
    }

    @Test
    void 抛异常后同样归还许可() {
        // ★归还写在 finally：异常路径不归还会让闸门被逐个吃空，
        //   最终 What-If 整体不可用——而且是不可恢复的那种
        WhatIfCapacityGate gate = new WhatIfCapacityGate();
        int before = gate.available();

        assertThatThrownBy(() -> gate.withPermit(1000, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(gate.available()).as("异常路径必须归还").isEqualTo(before);
    }

    @Test
    void 池满时超时抛Throttled而非无限等待() throws Exception {
        WhatIfCapacityGate gate = new WhatIfCapacityGate();
        int capacity = gate.capacity();

        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch allAcquired = new CountDownLatch(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(capacity);
        try {
            for (int i = 0; i < capacity; i++) {
                pool.submit(() -> gate.withPermit(5000, () -> {
                    allAcquired.countDown();
                    hold.await(5, TimeUnit.SECONDS);
                    return null;
                }));
            }
            assertThat(allAcquired.await(5, TimeUnit.SECONDS)).as("应能占满池").isTrue();

            // 池已满，再要许可应在超时后被拒绝——而不是挂死
            assertThatThrownBy(() -> gate.withPermit(100, () -> "never"))
                .as("池满时必须超时拒绝，归类为可重试的 THROTTLED")
                .isInstanceOf(WhatIfCapacityGate.WhatIfThrottledException.class);
        } finally {
            hold.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void 并发压测后许可精确归位() throws Exception {
        // 判据是**守恒**：少了=泄漏（闸门逐渐失效），多了=多还（闸门上限被抬高）
        WhatIfCapacityGate gate = new WhatIfCapacityGate();
        int before = gate.available();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < 400; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        gate.withPermit(2000, () -> {
                            // 一半正常返回、一半抛异常，两条路径都要归还
                            if (n % 2 == 0) {
                                throw new IllegalStateException("boom");
                            }
                            return "ok";
                        });
                        ok.incrementAndGet();
                    } catch (WhatIfCapacityGate.WhatIfThrottledException t) {
                        throttled.incrementAndGet();
                    } catch (Exception ignored) {
                        ok.incrementAndGet();   // 业务异常也算走完了一轮
                    }
                });
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(gate.available())
            .as("★400 次并发后许可必须精确归位（%d 成功 / %d 限流）", ok.get(), throttled.get())
            .isEqualTo(before);
    }
}
