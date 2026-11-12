package io.aster.guard;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Action Guard 的指标（名称在 Prometheus 中加 {@code _total} 后缀）。 */
final class GuardMetrics {
  static final String RULE_METADATA_UNAVAILABLE = "aster.guard.rule.metadata.unavailable";

  private GuardMetrics() {
  }

  /**
   * 档案来源门读不到规则元数据的次数（Task 16 carry-over 6）。
   *
   * @param path {@code decisions}（{@code POST /decisions}）或 {@code from_evidence}
   */
  static Counter ruleMetadataUnavailable(MeterRegistry registry, String path) {
    return Counter.builder(RULE_METADATA_UNAVAILABLE)
        .description("Action Guard 档案来源门读不到规则元数据的次数")
        .tag("path", path)
        .register(registry);
  }
}
