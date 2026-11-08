package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.guard.receipt.ExecutionReceipt;

/**
 * consume 成功响应（ADR 0040 §12 D5）：字段按字母序输出。
 *
 * @param consumedAt       加锁判定时刻（ISO-8601 UTC），与 {@code guard_receipts.consumed_at} 相同
 * @param executionReceipt 与决策响应中的执行收据为同一份（取自 response_json，不重签）
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumeResponse(String consumedAt, String decisionId, ExecutionReceipt executionReceipt) {
}
