package io.aster.replay.runner;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.aster.policy.replay.ReplayMetadata;

/**
 * runner 结果 envelope（spec ③/④）。runner 是唯一生产方，向 stdout 输出。
 * ★错误不进 ReplayMetadata——成功承 replayMetadata，错误承 errorCode/message/phase，
 * 二者不共 schema（NON_NULL 保错误 envelope 不含 replayMetadata 字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerEnvelope(
        String outcome,           // "SUCCESS" | "ERROR"
        ReplayMetadata replayMetadata,   // 仅成功
        String errorCode,         // 仅错误：PARSE/EXECUTION/MODULE/INTERNAL
        String message,           // 仅错误
        String phase              // 仅错误：validate|parse|execute|trace|metadata
) {
    public static RunnerEnvelope success(ReplayMetadata rm) {
        return new RunnerEnvelope("SUCCESS", rm, null, null, null);
    }
    public static RunnerEnvelope error(String errorCode, String message, String phase) {
        return new RunnerEnvelope("ERROR", null, errorCode, message, phase);
    }
}
