package io.aster.policy.export;

import java.util.List;
import java.util.Map;

/**
 * 导出检查结论（修订 3 P2-R8 / P2-R9）：按 target（{@code jre} / {@code node}）分别给出是否可导出；入口确定不了时
 * {@code defaultEntry} 为 null，{@code entryCandidates} 为可选的 Rule。
 */
public record PackageExportCheck(Map<String, TargetResult> targets, String defaultEntry, List<String> entryCandidates) {
  public PackageExportCheck {
    targets = Map.copyOf(targets);
    entryCandidates = List.copyOf(entryCandidates);
  }

  /** 一个 target 的结论；{@code unavailable} 非 null 时为 503 错误码（如 {@code export_runtime_unavailable}）。 */
  public record TargetResult(boolean exportable, List<ExportDiagnostic> diagnostics, String unavailable) {
    public TargetResult {
      diagnostics = List.copyOf(diagnostics);
    }
  }
}
