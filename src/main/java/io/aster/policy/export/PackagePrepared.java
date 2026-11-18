package io.aster.policy.export;

import java.util.List;

/**
 * {@code prepare} 的结果（修订 3 P2-R8）：在检查之上加平台 Core IR、两种哈希、探针与 runtime 坐标，供 cloud 做 Node 侧比对。
 * {@code exportable=false} 时只有 {@code diagnostics} 有意义。
 */
public record PackagePrepared(boolean exportable, List<ExportDiagnostic> diagnostics, String entry, String coreJson,
                              String coreIrSha256, String positionFreeSha256, ProbeRunner.ProbesFile probes,
                              Runtime runtime) {
  public PackagePrepared {
    diagnostics = List.copyOf(diagnostics);
  }

  /** 锁文件中该 target 的 CLI 坐标与本进程的生态版本。 */
  public record Runtime(String cliName, String cliVersion, String ecosystem) {
  }

  static PackagePrepared rejected(List<ExportDiagnostic> diagnostics) {
    return new PackagePrepared(false, diagnostics, null, null, null, null, null, null);
  }
}
