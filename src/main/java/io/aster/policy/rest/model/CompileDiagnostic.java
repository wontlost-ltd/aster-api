package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 编译诊断项，字段与前端 Monaco 契约对齐（1-based 行列）。
 *
 * 对应 cloud {@code PolicyDiagnostic}：severity 为小写字符串枚举
 * （error/warning/info/hint），行列 1-based。ANTLR 只给起点，故 end == start。
 */
public record CompileDiagnostic(
    @JsonProperty("severity")
    String severity,

    @JsonProperty("message")
    String message,

    @JsonProperty("startLine")
    int startLine,

    @JsonProperty("startColumn")
    int startColumn,

    @JsonProperty("endLine")
    int endLine,

    @JsonProperty("endColumn")
    int endColumn,

    @JsonProperty("code")
    String code,

    /** P0-C 稳定性诊断的机器可读特性标识（如 workflow/pii；非稳定诊断为 null）。 */
    @JsonProperty("featureId")
    String featureId,

    /** P0-C：strict surface 是否因此诊断阻断（severity 恒 warning，blocking 表达是否拒）。 */
    @JsonProperty("blocking")
    boolean blocking,

    /**
     * 命名参数（ADR 0051 §16 R56）：语法诊断 E050–E069 的 {@code kw.*} / {@code token.*} / 引号字面片段，
     * 供按码渲染；其它诊断为空，空时不序列化（响应体与改码前一致）。
     */
    @JsonProperty("params")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, String> params
) {
    public CompileDiagnostic {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 无参数诊断。 */
    public CompileDiagnostic(String severity, String message, int startLine, int startColumn,
                             int endLine, int endColumn, String code, String featureId, boolean blocking) {
        this(severity, message, startLine, startColumn, endLine, endColumn, code, featureId, blocking, Map.of());
    }

    /**
     * 编译端点内部故障码（ADR 0046 §4）：编译没做成，不是源码错误。core 码表没有通用的
     * 编译器内部故障码，故由 api 定义；cloud 保存门把它当作「编译不可用」处理。
     */
    public static final String INTERNAL_FAILURE_CODE = "COMPILE_INTERNAL_ERROR";

    /** 由 1-based 行列构造 error 级诊断（end==start，ANTLR 无终点信息）。 */
    public static CompileDiagnostic error(int line, int column, String message) {
        return new CompileDiagnostic("error", message, line, column, line, column, null, null, false);
    }

    /** 编译端点内部故障：1:1 error，带 {@link #INTERNAL_FAILURE_CODE}。 */
    public static CompileDiagnostic internalFailure(String message) {
        return new CompileDiagnostic("error", message, 1, 1, 1, 1, INTERNAL_FAILURE_CODE, null, false);
    }

    /**
     * 从编译层诊断映射（保留 severity/code/end/featureId/blocking——W600 稳定性 warning 须带
     * code=W600 + featureId + blocking，前端 Monaco 才能显黄标 + 表达 strict 是否拒，ADR 0031）。
     */
    public static CompileDiagnostic from(io.aster.policy.compiler.CompilationResult.Diagnostic d) {
        return new CompileDiagnostic(
            d.severity() == null ? "error" : d.severity(),
            d.message(),
            d.line(), d.column(), d.endLine(), d.endColumn(),
            d.code(), d.featureId(), d.blocking(), d.params());
    }
}
