package io.aster.policy.api;

/**
 * 按名评估的已部署策略无法执行：目标策略不存在，或存在但没有「已批准且启用」的版本。
 *
 * <p>两者都是调用方可感知、可处理的业务状态（去部署 / 去激活版本），必须与执行期故障区分开，
 * 否则 REST 层只能统一回「评估失败」，真实原因被吞掉。消息只含模块与函数名，可原样回给调用方。
 */
public final class PolicyNotExecutableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** 该租户下不存在此模块/函数的任何版本。 */
        POLICY_NOT_FOUND,
        /** 存在版本，但没有被批准并激活为默认版本的那一个。 */
        NO_EXECUTABLE_VERSION
    }

    private final Reason reason;

    private PolicyNotExecutableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static PolicyNotExecutableException policyNotFound(String module, String function) {
        return new PolicyNotExecutableException(Reason.POLICY_NOT_FOUND,
            "Policy " + module + "." + function + " not found");
    }

    public static PolicyNotExecutableException noExecutableVersion(String module, String function) {
        return new PolicyNotExecutableException(Reason.NO_EXECUTABLE_VERSION,
            "Policy " + module + "." + function
                + " has no executable version: approve and activate a version before evaluating it");
    }

    public Reason reason() {
        return reason;
    }
}
