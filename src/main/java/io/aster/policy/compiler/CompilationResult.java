package io.aster.policy.compiler;

import java.util.Collections;
import java.util.List;

/**
 * 表示编译流程的结果，封装 Core JSON、元信息以及错误列表。
 */
public final class CompilationResult {

    /**
     * 结构化诊断（含 1-based 行列 + 消息），供编译端点透传给前端精确标错。
     * 语法错误路径由 CnlErrorListener 填充；语义/其它失败可能只有 errors 字符串
     * 而无结构化 diagnostics（此时端点回退用 errors 生成行列=1 的兜底诊断）。
     *
     * <p>P0-C 稳定性诊断（W600）走同一 diagnostics（ADR 0031，非平行字段）：
     * {@code code="W600"}、{@code severity="warning"}（strict 拒绝也不升 error）、
     * {@code featureId/nodeKind} 机器可读、{@code blocking} 标记 strict surface 是否拒。
     * 语法/语义错误：{@code code=null, severity="error", blocking=true, featureId/nodeKind=null}。
     * 治理诊断（ADR 0045）同样走本通道且恒为 {@code severity="warning"}；例外是档案码 E705/E706
     * （ADR 0046 §4）保持 {@code severity="error"}，供 cloud 保存门拒绝，{@code blocking} 仍为 false。
     */
    public record Diagnostic(
        int line, int column, int endLine, int endColumn,
        String message, String code, String severity, String featureId, String nodeKind, boolean blocking) {

        /** 语法/语义错误的兼容工厂（无 code/featureId，severity=error，end=start）。 */
        public static Diagnostic error(int line, int column, String message) {
            return new Diagnostic(line, column, line, column, message, null, "error", null, null, true);
        }
    }

    private final boolean success;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final List<String> errors;
    private final List<Diagnostic> diagnostics;
    private final boolean stabilityBlocked;

    private CompilationResult(boolean success, String coreJson, CompilationMetadata metadata,
                             List<String> errors, List<Diagnostic> diagnostics, boolean stabilityBlocked) {
        this.success = success;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.errors = errors;
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        this.stabilityBlocked = stabilityBlocked;
    }

    /**
     * 创建成功结果，默认携带空错误列表。
     */
    public static CompilationResult success(String coreJson, CompilationMetadata metadata) {
        return success(coreJson, metadata, List.of());
    }

    /**
     * 创建成功结果，携带诊断（warn-mode 的 W600 warning 随成功返回，前端可展示黄标）。
     */
    public static CompilationResult success(String coreJson, CompilationMetadata metadata, List<Diagnostic> diagnostics) {
        CompilationMetadata resolvedMetadata = metadata != null ? metadata : CompilationMetadata.empty();
        return new CompilationResult(true, coreJson, resolvedMetadata, List.of(),
            diagnostics == null ? List.of() : List.copyOf(diagnostics), false);
    }

    /**
     * strict surface 因 Experimental（W600）被拒：success=false、coreJson=null、diagnostics 含 W600。
     */
    public static CompilationResult stabilityBlocked(List<Diagnostic> diagnostics) {
        return new CompilationResult(false, null, null,
            List.of("Experimental features are not allowed on this surface"),
            diagnostics == null ? List.of() : List.copyOf(diagnostics), true);
    }

    /**
     * 创建失败结果（单条错误信息）。
     */
    public static CompilationResult failure(String error) {
        String message = (error == null || error.isBlank()) ? "编译失败" : error;
        return failure(Collections.singletonList(message));
    }

    /**
     * 创建失败结果（多条错误信息）。
     */
    public static CompilationResult failure(List<String> errors) {
        return failure(errors, List.of());
    }

    /**
     * 创建失败结果（多条错误信息 + 结构化诊断）。
     */
    public static CompilationResult failure(List<String> errors, List<Diagnostic> diagnostics) {
        List<String> safeErrors = errors == null || errors.isEmpty()
            ? List.of("编译失败但未提供错误详情")
            : List.copyOf(errors);
        return new CompilationResult(false, null, null, safeErrors,
            diagnostics == null ? List.of() : List.copyOf(diagnostics), false);
    }

    public boolean isSuccess() {
        return success;
    }

    /** strict surface 因 Experimental（W600）被拒。 */
    public boolean isStabilityBlocked() {
        return stabilityBlocked;
    }

    public String getCoreJson() {
        return coreJson;
    }

    public CompilationMetadata getMetadata() {
        return metadata;
    }

    public List<String> getErrors() {
        return errors;
    }

    /** 结构化诊断（含 1-based 行列）；可能为空（如无位置信息的语义失败）。 */
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "CompilationResult{" +
            "success=" + success +
            ", coreJsonLength=" + (coreJson == null ? 0 : coreJson.length()) +
            ", metadata=" + metadata +
            ", errors=" + errors +
            '}';
    }
}
