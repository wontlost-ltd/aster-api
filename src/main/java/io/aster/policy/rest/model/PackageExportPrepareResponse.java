package io.aster.policy.rest.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code prepare} 响应（修订 3 P2-R8）：平台 Core IR 与两种哈希、探针（{@code tests/probes.json} 的内容）及其规范字节的
 * SHA-256，供 cloud 做 Node 侧重编译比对（E739）与探针比对（E735）。不可导出时只有 {@code diagnostics}。
 */
public record PackageExportPrepareResponse(boolean exportable, List<CompileDiagnostic> diagnostics, String entry,
                                           String coreIr, String coreIrSha256, String positionFreeSha256,
                                           JsonNode probes, String probesSha256, int probeCount, Runtime runtime) {

  /** 锁文件中该 target 的 CLI 坐标与 aster-api 的生态版本。 */
  public record Runtime(String cliName, String cliVersion, String ecosystem) {
  }
}
