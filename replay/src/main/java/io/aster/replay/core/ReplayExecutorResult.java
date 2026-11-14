package io.aster.replay.core;

import io.aster.policy.governance.InputClassification;

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
        String profile,
        List<String> inputRefs,
        boolean verifiedInputsRequired,
        String inputKey,
        InputClassification classification) {

    /** 不带输入分类的构造（ADR 0042 §12 之前的调用方与测试桩）。 */
    public ReplayExecutorResult(Object result, String moduleName, String functionName, long executionTimeMs,
                                String ruleId, List<String> controls, String profile, List<String> inputRefs,
                                boolean verifiedInputsRequired, String inputKey) {
        this(result, moduleName, functionName, executionTimeMs, ruleId, controls, profile, inputRefs,
            verifiedInputsRequired, inputKey, null);
    }

    /**
     * 不带输入引用信息的构造。inputRefs / verifiedInputsRequired / inputKey 的语义同
     * {@code DynamicCnlExecutor.ExecutionResult}：引擎源码路径、写事件时的要求项快照、第一个参数实际匹配的输入键。
     */
    public ReplayExecutorResult(Object result, String moduleName, String functionName, long executionTimeMs,
                                String ruleId, List<String> controls, String profile) {
        this(result, moduleName, functionName, executionTimeMs, ruleId, controls, profile, null, false, null, null);
    }

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
