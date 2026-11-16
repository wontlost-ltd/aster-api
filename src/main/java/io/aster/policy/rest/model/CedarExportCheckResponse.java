package io.aster.policy.rest.model;

import java.util.List;

/**
 * REST API 响应：Cedar 可导出检查（ADR 0047 §6，aster-cloud#786）。
 *
 * <p>{@code exportable} 恒等于 {@code rules} 非空。{@code rules} 为核心翻译成功的规则规范名（声明顺序）；
 * 多于一条时真实导出会要求调用方指定 {@code functionName}。可导出时 {@code diagnostics} 为空；否则为编译错误、
 * E720（没有 guard 形态的规则，或指定的函数不存在），或各候选规则的翻译诊断（E721 等）。
 * {@code sourceHash} / {@code scopesHash} 与导出响应同义。
 *
 * <p>这是必要条件检查：不做 Cedar 严格校验与探针比对，E723/E724 只有真实导出才会给出。
 */
public record CedarExportCheckResponse(
    boolean exportable,
    List<String> rules,
    List<CompileDiagnostic> diagnostics,
    String sourceHash,
    String scopesHash
) {
}
