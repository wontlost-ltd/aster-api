package io.aster.policy.rest.model;

import aster.core.typecheck.governance.ControlRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
    String controlsRegistryVersion
) {
    /** 编译成功的模块概要，对齐 cloud 契约 {module:{name,functions[],types[]}}。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModuleInfo(
        @JsonProperty("name") String name,
        @JsonProperty("functions") List<String> functions,
        @JsonProperty("types") List<String> types
    ) {}

    public static CompileResponse ok(ModuleInfo module) {
        return new CompileResponse(true, module, List.of(), null, registryVersion());
    }

    /** 成功但携带诊断（warn-mode 的 W600 稳定性 warning，前端展示黄标；ADR 0031）。 */
    public static CompileResponse ok(ModuleInfo module, List<CompileDiagnostic> diagnostics) {
        return new CompileResponse(true, module, diagnostics == null ? List.of() : diagnostics, null, registryVersion());
    }

    public static CompileResponse fail(List<CompileDiagnostic> diagnostics, String error) {
        return new CompileResponse(false, null, diagnostics, error, registryVersion());
    }

    private static String registryVersion() {
        return ControlRegistry.loadDefault().version();
    }
}
