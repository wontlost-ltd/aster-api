package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评估热路径的进程内吞吐与延迟测量（不属于常规测试，设置 ASTER_PERF=1 才运行）。
 *
 * <p>冷：每轮先清空结果 / 版本解析 / 编译缓存，再对同一策略并发发起一批请求；
 * 热：版本解析与编译缓存已就绪，每个请求带不同入参以绕过结果缓存，只走解析命中 + 执行。
 * 每轮记录每个请求从订阅到拿到结果的耗时，输出成功 / 失败数、总吞吐与逐轮吞吐中位数（成功次数/秒，后者对宿主机负载抖动不敏感）、
 * p50 与 p95（毫秒）。
 * 同一份源码放到基线 worktree 中运行即可对比。
 */
@QuarkusTest
@RedisEnabledTest
@EnabledIfEnvironmentVariable(named = "ASTER_PERF", matches = "1")
class EvaluateHotPathPerfTest {

    private static final String TENANT = "tenant-batch-volume";
    private static final String MODULE = "aster.test.batch";
    private static final String FUNCTION = "slowPolicy";
    private static final int COLD_ROUNDS = 15;
    private static final int COLD_BATCH = 120;
    private static final int WARM_ROUNDS = 30;
    private static final int WARM_BATCH = 200;

    @Inject PolicyEvaluationService service;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @Inject CompiledPolicyCache compiledPolicyCache;

    private final AtomicInteger input = new AtomicInteger();

    @Test
    void 冷与热路径吞吐和延迟() {
        // 预热：先单个请求填好解析与编译缓存，再成批预热 JIT 与 Truffle 引擎
        batchOf(1, new long[1]).await().atMost(Duration.ofSeconds(60));
        runRounds(5, WARM_BATCH, false);
        report("cold", runRounds(COLD_ROUNDS, COLD_BATCH, true));
        runRounds(5, WARM_BATCH, false);
        report("warm", runRounds(WARM_ROUNDS, WARM_BATCH, false));
    }

    private Sample runRounds(int rounds, int batch, boolean cold) {
        List<Long> latencies = new ArrayList<>();
        List<Double> roundThroughputs = new ArrayList<>();
        long wall = 0;
        for (int r = 0; r < rounds; r++) {
            if (cold) {
                coldCaches();
            }
            long[] batchLatencies = new long[batch];
            long start = System.nanoTime();
            batchOf(batch, batchLatencies).await().atMost(Duration.ofSeconds(120));
            long elapsed = System.nanoTime() - start;
            wall += elapsed;
            long ok = Arrays.stream(batchLatencies).filter(l -> l > 0).peek(latencies::add).count();
            roundThroughputs.add(ok / (elapsed / 1e9));
        }
        return new Sample(latencies, wall, rounds * batch - latencies.size(), median(roundThroughputs));
    }

    private Uni<List<PolicyEvaluationResult>> batchOf(int batch, long[] latencies) {
        List<Uni<PolicyEvaluationResult>> unis = new ArrayList<>(batch);
        for (int i = 0; i < batch; i++) {
            int slot = i;
            int value = input.incrementAndGet();
            unis.add(Uni.createFrom().deferred(() -> {
                long t0 = System.nanoTime();
                // 失败的请求记为 0（不计入延迟），以失败数单独报告，不中断测量
                return service.evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{value})
                    .invoke(ignored -> latencies[slot] = System.nanoTime() - t0)
                    .onFailure().recoverWithNull();
            }));
        }
        return Uni.join().all(unis).andFailFast();
    }

    private void coldCaches() {
        service.clearAllCache().await().indefinitely();
        versionResolutionCache.clear();
        compiledPolicyCache.clear();
    }

    private static void report(String path, Sample s) {
        long[] sorted = s.latencies().stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            System.out.printf("PERF %s ok=0 failed=%d%n", path, s.failed());
            return;
        }
        double throughput = sorted.length / (s.wallNanos() / 1e9);
        System.out.printf("PERF %s ok=%d failed=%d throughput=%.0f/s roundMedian=%.0f/s p50=%.2fms p95=%.2fms%n",
            path, sorted.length, s.failed(), throughput, s.roundMedianThroughput(),
            percentile(sorted, 50), percentile(sorted, 95));
    }

    private static double percentile(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)] / 1e6;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private record Sample(List<Long> latencies, long wallNanos, int failed, double roundMedianThroughput) {}
}
