package io.aster.guard.reconcile;

import io.aster.guard.reconcile.GuardOrphanMetrics.OpenCount;
import io.aster.guard.reconcile.GuardReconciliationService.TenantOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 对账计数器、最近成功 Gauge 与 tenant 标签上限（纯单测，ADR 0054 §10.4）。 */
class GuardOrphanMetricsCountersTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final GuardOrphanMetrics metrics = new GuardOrphanMetrics(registry, null, 100);

  @Test
  void countersAndLastSuccess() {
    metrics.recordDetected(OrphanKind.SIDE_EFFECT_MISMATCH);
    metrics.recordDetected(OrphanKind.SIDE_EFFECT_MISMATCH);
    metrics.recordRun(TenantOutcome.SKIPPED_LOCKED);
    metrics.markLoopSucceeded(Instant.ofEpochSecond(1_700_000_000));

    assertThat(registry.get("guard.orphan.actions.detected").tag("kind", "side_effect_mismatch").counter().count())
        .isEqualTo(2.0);
    assertThat(registry.get("guard.reconciliation.runs").tag("outcome", "skipped_locked").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("guard.reconciliation.last.success").gauge().value()).isEqualTo(1.7e9);
    assertThat(registry.get("guard.reconciliation.last.success").gauge().getId().getBaseUnit()).isEqualTo("seconds");
  }

  @Test
  void outcomeWireValues() {
    assertThat(List.of(TenantOutcome.values()).stream().map(TenantOutcome::wire))
        .containsExactly("ok", "failed", "skipped_locked", "skipped_recent");
  }

  @Test
  void labelsStayBounded() {
    List<OpenCount> rows = List.of(
        new OpenCount("a", "other", "side_effect_mismatch", 1),
        new OpenCount("b", "other", "side_effect_mismatch", 2),
        new OpenCount("c", "other", "side_effect_mismatch", 3));

    List<OpenCount> bounded = GuardOrphanMetrics.bound(rows, 2);

    assertThat(bounded).containsExactlyInAnyOrder(
        new OpenCount("c", "other", "side_effect_mismatch", 3),
        new OpenCount("b", "other", "side_effect_mismatch", 2),
        new OpenCount("_other", "other", "side_effect_mismatch", 1));
  }

  @Test
  void mergedTenantsSumPerSeries() {
    List<OpenCount> rows = List.of(
        new OpenCount("big", "aster-guard-mcp", "consumed_without_result", 10),
        new OpenCount("x", "other", "side_effect_without_decision", 2),
        new OpenCount("y", "other", "side_effect_without_decision", 3),
        new OpenCount("y", "unknown", "allow_unconsumed", 1));

    List<OpenCount> bounded = GuardOrphanMetrics.bound(rows, 1);

    assertThat(bounded).containsExactlyInAnyOrder(
        new OpenCount("big", "aster-guard-mcp", "consumed_without_result", 10),
        new OpenCount("_other", "other", "side_effect_without_decision", 5),
        new OpenCount("_other", "unknown", "allow_unconsumed", 1));
  }

  @Test
  void tieBrokenByTenantName() {
    List<OpenCount> rows = List.of(new OpenCount("b", "other", "allow_unconsumed", 1),
        new OpenCount("a", "other", "allow_unconsumed", 1));
    assertThat(GuardOrphanMetrics.bound(rows, 1)).containsExactlyInAnyOrder(
        new OpenCount("a", "other", "allow_unconsumed", 1), new OpenCount("_other", "other", "allow_unconsumed", 1));
  }
}
