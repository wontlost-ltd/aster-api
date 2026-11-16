package io.aster.policy.cedar;

import io.aster.policy.rest.model.CompileDiagnostic;
import java.util.List;

/**
 * 一次可导出检查的结果（ADR 0047 §6，aster-cloud#786）。{@code rules} 为核心翻译成功的规则规范名（声明顺序），
 * {@code exportable} 恒等于其非空；可导出时 {@code diagnostics} 为空，否则为编译错误、E720，或各候选的翻译诊断。
 */
public record CedarExportCheck(
    boolean exportable,
    List<String> rules,
    List<CompileDiagnostic> diagnostics,
    String sourceHash) {

    static CedarExportCheck rejected(List<CompileDiagnostic> diagnostics, String sourceHash) {
        return new CedarExportCheck(false, List.of(), diagnostics, sourceHash);
    }
}
