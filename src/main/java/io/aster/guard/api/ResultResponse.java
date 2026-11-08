package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 结果回写成功响应（ADR 0040 §12 D7）：字段按字母序输出。
 *
 * @param resultAt 加锁写入时刻（ISO-8601 UTC），与 {@code guard_receipts.result_at} 相同
 * @param status   已记录的执行结果（executed / failed / aborted）
 */
@JsonPropertyOrder(alphabetic = true)
public record ResultResponse(String decisionId, String resultAt, String status) {
}
