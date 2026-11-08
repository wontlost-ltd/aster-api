package io.aster.guard.api;

/**
 * consume 请求体（ADR 0040 §12 D5）。{@code actionHash} 为执行方对待执行动作自算的 jcs-sha256/v1（64 位小写 hex）；
 * {@code attemptId}（UUID 形态）与 {@code executorId}（≤ 255 字符、无控制字符）是执行方自报的关联字段，
 * 不承担身份认证，也不参与放行判定。
 */
public record ConsumeRequest(String actionHash, String attemptId, String executorId) {
}
