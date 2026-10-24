package io.aster.policy.compiler;

import java.util.List;

/**
 * 执行门禁拒绝：策略版本在当前工具链下没有有效验证证明（ADR 0050 §2.2）。
 *
 * <p>用类型化异常而不是 WebApplicationException 上抛，是因为评估链路会把异常层层包装
 * （QuarkusTransaction、Mutiny onFailure、"动态策略执行异常"），REST 层的 {@code EvaluationFailure.classify}
 * 沿 cause 链归因到本类即可给出 422，不依赖中间层对特定异常的放行。
 */
public class PolicyVersionInvalidException extends RuntimeException {
    private final List<CompilationResult.Diagnostic> diagnostics;
    private final String toolchainId;
    private final String reason;

    public PolicyVersionInvalidException(List<CompilationResult.Diagnostic> diagnostics, String toolchainId, String reason) {
        super(summarize(diagnostics, toolchainId, reason));
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.toolchainId = toolchainId;
        this.reason = reason;
    }

    public List<CompilationResult.Diagnostic> diagnostics() { return diagnostics; }
    public String toolchainId() { return toolchainId; }
    public String reason() { return reason; }

    private static String summarize(List<CompilationResult.Diagnostic> diagnostics, String toolchainId, String reason) {
        String first = diagnostics == null || diagnostics.isEmpty()
            ? reason
            : diagnostics.get(0).code() + " " + diagnostics.get(0).message();
        return "策略版本未通过当前工具链的类型检查，拒绝执行: " + first + " (toolchain=" + toolchainId + ")";
    }
}
