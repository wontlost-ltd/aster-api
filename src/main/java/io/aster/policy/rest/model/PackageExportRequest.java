package io.aster.policy.rest.model;

import java.util.List;
import java.util.Map;

/**
 * 策略包导出三个端点（{@code check} / {@code prepare} / {@code export/package}）的同一请求体（ADR 0059 修订 3 P2-R8）。
 * {@code claimedRef} 为有类型的来源（P2-R11），{@code nodeProbes} 只在 {@code target=node} 的导出请求中出现。
 */
public record PackageExportRequest(String source, String locale, Map<String, List<String>> aliasSet,
                                   Boolean allowStructural, Map<String, Object> vocabulary, String entry,
                                   String target, String mode, String uiLocale, ClaimedRefBody claimedRef,
                                   NodeProbes nodeProbes) {

  /** manifest {@code source.claimedRef}。 */
  public record ClaimedRefBody(String policyId, Integer version, String status, String approvedAt) {
  }

  /** cloud 在 Worker 内做完 Node 侧探针比对后的结论（P2-R13）。 */
  public record NodeProbes(String probesSha256, Integer count, Integer agreed, String tsVersion) {
  }
}
