package io.aster.guard.api;

/**
 * 执行方回写的执行结果（ADR 0040 §12 D3）。status 取值 executed / failed / aborted；
 * finishedAt、resultHash、errorCode 可选，均为执行方自报，不参与放行判定。
 */
public record ResultRequest(String attemptId, String executorId, String status, String finishedAt,
                            String resultHash, String errorCode) {
}
