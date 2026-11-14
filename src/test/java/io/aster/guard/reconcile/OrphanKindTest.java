package io.aster.guard.reconcile;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 孤儿类别字面量是 DB CHECK、指标标签、REST 字段与告警正则共用的契约（ADR 0054 §10.3）。 */
class OrphanKindTest {

  @Test
  void wireValuesMatchSpec() {
    assertThat(Arrays.stream(OrphanKind.values()).map(OrphanKind::wire).toList()).containsExactly(
        "side_effect_without_decision", "side_effect_unconsumed", "side_effect_mismatch", "consumed_without_result",
        "allow_unconsumed", "consume_on_non_allow", "result_without_consume", "orphan_receipt");
  }

  @Test
  void severityFollowsKind() {
    Map<String, String> severity = Arrays.stream(OrphanKind.values())
        .collect(Collectors.toMap(OrphanKind::wire, OrphanKind::severity));
    assertThat(severity).containsExactlyInAnyOrderEntriesOf(Map.of(
        "side_effect_without_decision", "critical",
        "side_effect_unconsumed", "critical",
        "side_effect_mismatch", "critical",
        "consumed_without_result", "warning",
        "allow_unconsumed", "info",
        "consume_on_non_allow", "critical",
        "result_without_consume", "critical",
        "orphan_receipt", "info"));
  }

  @Test
  void onlyLedgerGapsAutoResolve() {
    List<OrphanKind> auto = Arrays.stream(OrphanKind.values()).filter(OrphanKind::autoResolvable).toList();
    assertThat(auto).containsExactlyInAnyOrder(OrphanKind.CONSUMED_WITHOUT_RESULT, OrphanKind.ALLOW_UNCONSUMED,
        OrphanKind.ORPHAN_RECEIPT);
  }

  @Test
  void fromWireRoundTripsAndRejectsUnknown() {
    for (OrphanKind k : OrphanKind.values()) {
      assertThat(OrphanKind.fromWire(k.wire())).contains(k);
    }
    assertThat(OrphanKind.fromWire("nope")).isEmpty();
    assertThat(OrphanKind.fromWire(null)).isEmpty();
    assertThat(OrphanKind.fromWire("SIDE_EFFECT_MISMATCH")).isEmpty();
  }
}
