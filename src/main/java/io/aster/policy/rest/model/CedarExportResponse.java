package io.aster.policy.rest.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * REST API 响应：Cedar 导出结果（ADR 0047 §6）。
 *
 * <p>不可导出时仍为 HTTP 200：{@code exportable=false}，{@code policies} 与 {@code schema} 为 null，
 * 原因见 {@code diagnostics}。{@code probes} 为逐探针等价性比对的条数与一致条数。
 *
 * <p>{@code moduleName} 为编译器给出的模块名（供下载文件命名，编译失败时为 null）；
 * {@code schemaText} 为经严格校验并计入审计 {@code schemaHash} 的 schema 原文，调用方应原样保存，
 * 不要用 {@code schema} 重新序列化（键序与转义可能不同，哈希即不再相等）。{@code schema} 为同一内容的解析形态，保留兼容。
 *
 * <p>{@code scopesHash} 为编译所用领域词汇的 sha256(canonicalJson)，无词汇为空串（ADR 0051）：同一源码在不同词汇下
 * 导出的策略不同，溯源须同时看 {@code sourceHash} 与 {@code scopesHash}。
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
    String sourceHash,
    String moduleName,
    String schemaText,
    String scopesHash
) {
    public record Probes(int count, int agreed) {
    }
}
