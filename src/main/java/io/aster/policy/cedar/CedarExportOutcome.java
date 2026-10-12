package io.aster.policy.cedar;

import io.aster.policy.rest.model.CompileDiagnostic;
import java.util.List;

/**
 * 一次 Cedar 导出的结果（ADR 0047 §5–§6）。{@code exportable=false} 时 {@code policies} / {@code schemaJson} 为 null，
 * 只有诊断可用；{@code probeCount} 为实际比对的探针条数，{@code probesAgreed} 为满足各自一致性规则的条数。
 * {@code moduleName} 供导出审计填写策略模块；编译失败时为 null。
 */
public record CedarExportOutcome(
    boolean exportable,
    String ruleId,
    String functionName,
    String moduleName,
    String policies,
    String schemaJson,
    String profile,
    List<String> controls,
    int probeCount,
    int probesAgreed,
    String sourceHash,
    List<CompileDiagnostic> diagnostics) {
}
