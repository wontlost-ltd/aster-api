package io.aster.policy.rest.model;

import java.util.List;
import java.util.Map;

/** {@code check} 响应：按 target 的结论与入口选择（修订 3 P2-R8 / P2-R9）。 */
public record PackageExportCheckResponse(Map<String, TargetCheck> targets, String defaultEntry,
                                         List<String> entryCandidates) {

  /** {@code unavailable} 为 null 或 503 错误码（如 {@code export_runtime_unavailable}）。 */
  public record TargetCheck(boolean exportable, List<CompileDiagnostic> diagnostics, String unavailable) {
  }
}
