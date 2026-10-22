package io.aster.policy.api.model;

/**
 * 批量执行单次尝试结果，区分成功与失败。
 */
public class EvaluationAttempt {

    private final int index;
    private final String policyModule;
    private final String policyFunction;
    private final PolicyEvaluationResult result;
    private final String error;
    /** 失败条目的机器可读错误码（见 EvaluationFailure）；成功条目为 null。 */
    private final String errorCode;

    public EvaluationAttempt(int index,
                             String policyModule,
                             String policyFunction,
                             PolicyEvaluationResult result,
                             String error) {
        this(index, policyModule, policyFunction, result, error, null);
    }

    public EvaluationAttempt(int index,
                             String policyModule,
                             String policyFunction,
                             PolicyEvaluationResult result,
                             String error,
                             String errorCode) {
        this.index = index;
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.result = result;
        this.error = error;
        this.errorCode = errorCode;
    }

    public int getIndex() {
        return index;
    }

    public String getPolicyModule() {
        return policyModule;
    }

    public String getPolicyFunction() {
        return policyFunction;
    }

    public PolicyEvaluationResult getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
