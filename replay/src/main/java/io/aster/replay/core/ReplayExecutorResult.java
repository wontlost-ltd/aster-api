package io.aster.replay.core;

import java.util.List;

/**
 * replay 执行结果的 core 拥有值类型。
 *
 * <p>★core public API 不得暴露 aster-api 的 {@code DynamicCnlExecutor.ExecutionResult}——
 * 否则 core 为声明签名会反依赖 aster-api，形成模块环。aster-api adapter 负责机械映射。
 */
public record ReplayExecutorResult(
        Object result,
        String moduleName,
        String functionName,
        long executionTimeMs,
        String ruleId,
        List<String> controls,
        String profile) {

    /** ruleId / controls（ADR 0039 §3）为空的便捷构造：无规则元数据的执行器（如测试桩）使用。 */
    public ReplayExecutorResult(Object result, String moduleName, String functionName, long executionTimeMs) {
        this(result, moduleName, functionName, executionTimeMs, null, List.of(), null);
    }

    /** 无治理档案（ADR 0046 §6）的便捷构造。 */
    public ReplayExecutorResult(Object result, String moduleName, String functionName, long executionTimeMs,
                                String ruleId, List<String> controls) {
        this(result, moduleName, functionName, executionTimeMs, ruleId, controls, null);
    }
}
