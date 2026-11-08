package io.aster.guard.api;

/**
 * 结果回写请求体（ADR 0040 §12 D7）。{@code attemptId / executorId} 须与 consume 时的消耗者逐字相同；
 * {@code status} 取 executed / failed / aborted。可选字段均为执行方自报，不参与任何判定：
 * {@code finishedAt} 为带偏移的 ISO-8601，{@code resultHash} 为执行方对工具返回值自算的 jcs-sha256/v1
 * （64 位小写 hex，不传结果明文），{@code errorCode} 为 1–64 字符、不含控制字符。
 */
public record ResultRequest(String attemptId, String executorId, String status, String finishedAt,
                            String resultHash, String errorCode) {
}
