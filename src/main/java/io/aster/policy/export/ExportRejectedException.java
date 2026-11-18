package io.aster.policy.export;

import java.util.List;

/** 策略不可导出（导出诊断或编译错误）；端点以 200 {@code {"exportable":false,"diagnostics":[…]}} 返回。 */
public final class ExportRejectedException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final transient List<ExportDiagnostic> diagnostics;

  public ExportRejectedException(List<ExportDiagnostic> diagnostics) {
    super("not exportable");
    this.diagnostics = List.copyOf(diagnostics);
  }

  public List<ExportDiagnostic> diagnostics() {
    return diagnostics;
  }
}
