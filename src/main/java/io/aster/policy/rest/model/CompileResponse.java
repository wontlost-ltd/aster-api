package io.aster.policy.rest.model;

import aster.core.typecheck.governance.ControlRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * REST API 响应：CNL 编译结果，字段与 cloud {@code PolicyCompileResponse} 对齐。
 *
 * {@code success} 为 false 时携带 {@code diagnostics}（含 1-based 行列）+
 * {@code error}（首条消息）；成功时携带 {@code module} 概要。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompileResponse(
    @JsonProperty("success")
    boolean success,

    @JsonProperty("module")
    ModuleInfo module,

    @JsonProperty("diagnostics")
    List<CompileDiagnostic> diagnostics,

    @JsonProperty("error")
    String error,

    /** 本次编译所用控制注册表版本（ADR 0045 §3），供前端解释 W704。 */
    @JsonProperty("controlsRegistryVersion")
    String controlsRegistryVersion,

    // 模块声明的治理档案 id（ADR 0046 §6）；未声明时不输出
    @JsonProperty("profile")
    String profile,

    // 规则输入引用（ADR 0046 §11 D2）：函数名 → 引擎原样路径（作者视角，未映射为请求路径），只列 IR 带 inputRefs 的
    // 函数（声明档案的模块中返回 Verdict 的规则）；没有任何函数带 inputRefs 时不输出，无档案模块的响应逐字节不变
    @JsonProperty("inputRefs")
    Map<String, List<String>> inputRefs
) {
    /** 编译成功的模块概要，对齐 cloud 契约 {module:{name,functions[],types[]}}。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModuleInfo(
        @JsonProperty("name") String name,
        @JsonProperty("functions") List<String> functions,
        @JsonProperty("types") List<String> types
    ) {}

    public static CompileResponse ok(ModuleInfo module) {
        return new CompileResponse(true, module, List.of(), null, registryVersion(), null, null);
    }

    /** 成功但携带诊断（warn-mode 的 W600 稳定性 warning，前端展示黄标；ADR 0031）。 */
    public static CompileResponse ok(ModuleInfo module, List<CompileDiagnostic> diagnostics) {
        return ok(module, diagnostics, null);
    }

    /** 成功且带模块声明的治理档案（ADR 0046 §6）；profile 为 null 时与无档案版本逐字节相同。 */
    public static CompileResponse ok(ModuleInfo module, List<CompileDiagnostic> diagnostics, String profile) {
        return ok(module, diagnostics, profile, null);
    }

    /** 成功且带规则输入引用；inputRefs 为 null 或空时不输出该键。 */
    public static CompileResponse ok(ModuleInfo module, List<CompileDiagnostic> diagnostics, String profile,
                                     Map<String, List<String>> inputRefs) {
        return new CompileResponse(true, module, diagnostics == null ? List.of() : diagnostics, null,
            registryVersion(), profile, inputRefs == null || inputRefs.isEmpty() ? null : inputRefs);
    }

    public static CompileResponse fail(List<CompileDiagnostic> diagnostics, String error) {
        return new CompileResponse(false, null, diagnostics, error, registryVersion(), null, null);
    }

    private static String registryVersion() {
        return ControlRegistry.loadDefault().version();
    }
}
