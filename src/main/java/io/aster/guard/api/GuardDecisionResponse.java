package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.guard.GuardApprovalStatus;

import java.util.List;

/**
 * Action Guard 决策响应体（ADR 0040 §3.1）。字段按字母序输出，null 与空集合不写出。
 *
 * <p>同一 decisionId 的响应会原样存入 {@code guard_decisions.response_json}，供幂等重放与回读，
 * 因此本类型必须能被 Jackson 无损往返（record 组件即 JSON 字段）。
 *
 * @param effectiveOutcome Agent 唯一需要看的字段：{@code ALLOW | DENY | PENDING}
 * @param reasonCode       fail-closed 时的机器可读原因（见 {@link io.aster.guard.GuardReasonCode}），Verdict 正常返回时为 null
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GuardDecisionResponse(
    ApprovalView approval,
    List<String> controls,
    String decisionId,
    String effectiveOutcome,
    String outcome,
    PolicyVersionView policyVersion,
    String reason,
    String reasonCode,
    ReceiptView receipt,
    String role,
    String ruleId) {

  /** controls 归一为不可变列表：空列表不写出，回读得到 null，归一后首次响应与重放响应 equals。 */
  public GuardDecisionResponse {
    controls = controls == null ? List.of() : List.copyOf(controls);
  }

  /**
   * 按审批状态重算：{@code approval.status} 改为 {@code status}，{@code effectiveOutcome} 取该状态对应的结论。
   * 对同一状态重复调用结果不变；只用于带审批视图的（REQUIRE_APPROVAL / ESCALATE）响应。
   */
  public GuardDecisionResponse withApprovalStatus(GuardApprovalStatus status) {
    ApprovalView a = new ApprovalView(approval.expiresAt(), approval.id(), approval.requiredRole(), status.name());
    return new GuardDecisionResponse(a, controls, decisionId, status.effectiveOutcome(), outcome, policyVersion,
        reason, reasonCode, receipt, role, ruleId);
  }

  /** 待审批视图；{@code expiresAt} 为 ISO-8601 字符串，{@code requiredRole} 为 null 表示任意复核人。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ApprovalView(String expiresAt, String id, String requiredRole, String status) {}

  /** 被评估的活跃策略版本：{@code id} 为版本行主键，{@code version} 为版本号。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record PolicyVersionView(Long id, String sourceHash, Long version) {}

  /** 决策收据：决策事件在审计哈希链中的行 id、本行哈希与前一行哈希。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ReceiptView(Long auditId, String hash, String prevHash) {}
}
