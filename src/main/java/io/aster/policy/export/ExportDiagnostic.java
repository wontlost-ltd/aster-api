package io.aster.policy.export;

import io.aster.policy.rest.model.CompileDiagnostic;

/**
 * 一条导出诊断（ADR 0059 §8）。{@code line} / {@code column} 为 1-based，无源码位置（如入口类型问题）时为 null，
 * 转为 {@link CompileDiagnostic} 时落在 1:1。
 */
public record ExportDiagnostic(String code, String message, String severity, Integer line, Integer column) {

  /** 转为编译端点同形的诊断，供 check / prepare 响应与 UI 共用渲染。 */
  public CompileDiagnostic toCompileDiagnostic() {
    int l = line == null ? 1 : line;
    int c = column == null ? 1 : column;
    return new CompileDiagnostic(severity, message, l, c, l, c, code, null, true);
  }
}
