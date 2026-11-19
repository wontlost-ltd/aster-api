package io.aster.policy.export;

import aster.core.typecheck.ErrorCode;
import io.aster.policy.rest.model.CompileDiagnostic;

import java.util.HashMap;
import java.util.Map;

/**
 * 一条导出诊断（ADR 0059 §8）。{@code line} / {@code column} 为 1-based，无源码位置（如入口类型问题）时为 null，
 * 转为 {@link CompileDiagnostic} 时落在 1:1。
 */
public record ExportDiagnostic(String code, String message, String severity, Integer line, Integer column) {

  /**
   * core 登记的导出码（{@code ErrorCode.EXPORT_*}，ADR 0031 单一来源，与 ts {@code shared/error_codes.json} 同码同文）
   * 的 error 级诊断；消息按命名占位符渲染，缺参数时保留占位符原样。
   */
  public static ExportDiagnostic of(ErrorCode code, Integer line, Integer column, Map<String, String> params) {
    return new ExportDiagnostic(code.code(), code.render(new HashMap<>(params)), "error", line, column);
  }

  /** 转为编译端点同形的诊断，供 check / prepare 响应与 UI 共用渲染。 */
  public CompileDiagnostic toCompileDiagnostic() {
    int l = line == null ? 1 : line;
    int c = column == null ? 1 : column;
    return new CompileDiagnostic(severity, message, l, c, l, c, code, null, true);
  }
}
