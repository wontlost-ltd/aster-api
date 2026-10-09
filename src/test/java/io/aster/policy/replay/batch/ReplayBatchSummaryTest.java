package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 批次摘要 JSON 的键契约（ADR 0043 §5）：旧五键保留在前，四态键追加在后。 */
class ReplayBatchSummaryTest {

    @Test
    @SuppressWarnings("unchecked")
    void 摘要含四态键且旧键在前() {
        var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(3, List.of(
            ReplayBatchRunner.ItemResult.ok("e1", ReplayOutcome.ALLOW, true, ReplayOutcome.DENY, null),
            ReplayBatchRunner.ItemResult.ok("e2", ReplayOutcome.DENY, false, ReplayOutcome.INDETERMINATE, null),
            ReplayBatchRunner.ItemResult.ok("e3", ReplayOutcome.ALLOW, false, ReplayOutcome.ALLOW, null)));

        Map<String, Object> m = ReplayBatchService.summaryOf(c);

        assertThat(m.keySet()).containsExactly("changed", "newlyApproved", "newlyRejected", "totalSampled",
            "estimatedValueDelta", "transitions", "byTargetOutcome", "incomparable", "legacyBase");
        assertThat((Map<String, Integer>) m.get("transitions"))
            .containsOnly(Map.entry("ALLOW->DENY", 1), Map.entry("DENY->INDETERMINATE", 1),
                Map.entry("ALLOW->ALLOW", 1));
        assertThat((Map<String, Integer>) m.get("byTargetOutcome"))
            .containsOnlyKeys("ALLOW", "DENY", "REQUIRE_APPROVAL", "ESCALATE", "INDETERMINATE", "ERROR")
            .containsEntry("DENY", 1).containsEntry("ERROR", 0);
        assertThat(m).containsEntry("changed", 1).containsEntry("incomparable", 1).containsEntry("legacyBase", 1);
    }
}
