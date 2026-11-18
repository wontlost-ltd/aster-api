package io.aster.policy.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Semaphore;

/**
 * 策略包 {@code prepare} 与 {@code export/package} <strong>共用</strong>的有界并发闸（ADR 0059 P2 Global Constraints）：
 * 两者都要跑最多 256 次 Truffle 求值，分开计数会让并发上限翻倍。{@code check} 不占许可。不等待：占满即 503
 * {@code package_export_busy} + {@code Retry-After: 1}。
 */
@ApplicationScoped
public class PackageExportGate {
  private final int limit;
  private final Semaphore permits;

  @Inject
  public PackageExportGate(@ConfigProperty(name = "aster.export.package.max-concurrent", defaultValue = "2")
                           int maxConcurrent) {
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
