package io.aster.policy.rest.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * REST API 响应：Cedar 导出结果（ADR 0047 §6）。
 *
 * <p>不可导出时仍为 HTTP 200：{@code exportable=false}，{@code policies} 与 {@code schema} 为 null，
 * 原因见 {@code diagnostics}。{@code probes} 为逐探针等价性比对的条数与一致条数。
 */
public record CedarExportResponse(
    boolean exportable,
    String policies,
    JsonNode schema,
    String ruleId,
    String profile,
    List<String> controls,
    Probes probes,
    List<CompileDiagnostic> diagnostics,
    String sourceHash
) {
    public record Probes(int count, int agreed) {
    }
}
