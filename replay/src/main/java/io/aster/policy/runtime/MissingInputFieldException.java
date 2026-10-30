package io.aster.policy.runtime;

import io.aster.policy.parser.DynamicCnlExecutor.DynamicExecutionException;

/**
 * 输入缺少规则实际读取的字段（或整个参数）。
 *
 * <p>属于调用方可修正的输入错误（400），不是引擎故障：{@link #fieldPath()} 是相对调用方输入的字段路径
 * （如 {@code applicant.creditScore}），消息只含该路径，不含 guest 异常原文或内部实现细节。
 * 继承 {@link DynamicExecutionException}（{@link Kind#INVALID_INPUT}），执行器各层原样上抛，REST 层据 {@link #code()}
 * 回稳定错误码。
 *
 * <p>只有规则真正读到缺失字段时才会产生；输入里缺少规则没读到的字段、带多余字段都照常执行。
 */
public final class MissingInputFieldException extends DynamicExecutionException {

    private static final long serialVersionUID = 1L;

    /** 机器可读错误码，REST 响应的 {@code code} 字段原样使用。 */
    public static final String CODE = "missing_input_field";

    private final String fieldPath;

    private MissingInputFieldException(String fieldPath) {
        super("Input is missing field '" + fieldPath + "' that the policy reads. "
            + "Add it to the input and try again.", Kind.INVALID_INPUT);
        this.fieldPath = fieldPath;
    }

    /** @param fieldPath 相对调用方输入的点分字段路径，非空 */
    public static MissingInputFieldException of(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath 不能为空");
        }
        return new MissingInputFieldException(fieldPath);
    }

    /** 缺失字段相对调用方输入的点分路径。 */
    public String fieldPath() {
        return fieldPath;
    }

    /** 稳定错误码 {@value #CODE}。 */
    public String code() {
        return CODE;
    }
}
