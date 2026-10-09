package io.aster.policy.replay.batch.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** 回放逐项查询响应（ADR 0044 §4）：只含结论码，不含结论体。 */
@JsonPropertyOrder(alphabetic = true)
public record ReplayItemLookupResponse(List<Item> items) {
    @JsonPropertyOrder(alphabetic = true)
    public record Item(String executionId, String batchId, String baseOutcome, String targetOutcome, boolean baseLegacy) {}
}
