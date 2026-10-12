package io.aster.policy.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Semaphore;

/**
 * Cedar 导出的有界并发闸（ADR 0047 §6）：一次导出要编译并逐探针跑最多 512 次 Aster 求值，
 * 突发并发会把 worker 池与堆占满。与 /evaluate-source 的许可闸同一思路，但许可数独立配置、
 * 不等待：占满即由调用方返回 503 + Retry-After。
 */
@ApplicationScoped
public class CedarExportGate {

    private final int limit;
    private final Semaphore permits;

    @Inject
    public CedarExportGate(@ConfigProperty(name = "aster.cedar.export.max-concurrent", defaultValue = "2") int maxConcurrent) {
        this.limit = Math.max(1, maxConcurrent);
        this.permits = new Semaphore(limit, true);
    }

    /** 立即尝试取一个许可；取到后调用方必须在 finally 中 {@link #release()}。 */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int limit() {
        return limit;
    }
}
