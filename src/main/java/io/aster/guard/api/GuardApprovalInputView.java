package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * 审批输入视图（ADR 0042 §12 D8）：{@code GET /api/v1/guard/approvals/{id}/input} 与
 * {@code POST …/input/reveal} 的响应体。字母序、null 不写；{@code status ≠ available} 时没有
 * {@code input / piiMarks / inputSnapshotHash}。以对象实体返回（不预序列化为 String），全局 PII 响应过滤器只改写 String 实体。
 *
 * @param status       available / unavailable / purged / integrity_failed / too_large
 * @param marksSource  version / evaluation / none；无输入行时为 null
 * @param inputBinding direct / evaluated / unbound；无输入行时为 null
 * @param revealed     本响应是否为解除视图
 * @param revealedByMe 请求者本人是否已解除过（解除留痕按人记录）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public record GuardApprovalInputView(String approvalId, String contextHash, String decisionId, Boolean factsStale,
                                     Map<String, Object> input, String inputBinding, String inputSnapshotHash,
                                     Map<String, Object> inputSources, String marksSource, String maskRules,
                                     List<Map<String, Object>> piiMarks, boolean revealed, boolean revealedByMe,
                                     String status) {
}
