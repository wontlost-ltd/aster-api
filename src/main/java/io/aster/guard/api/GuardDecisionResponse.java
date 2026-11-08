package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.receipt.ExecutionReceipt;

import java.util.List;

/**
 * Action Guard 决策响应体（ADR 0040 §3.1）。字段按字母序输出，null 与空集合不写出。
 *
 * <p>同一 decisionId 的当前响应存于 {@code guard_decisions.response_json}（审批迁移时改写），供幂等重放与回读，
 * 因此本类型必须能被 Jackson 无损往返（record 组件即 JSON 字段）。
 *
 * @param effectiveOutcome Agent 唯一需要看的字段：{@code ALLOW | DENY | PENDING}
 * @param executionReceipt 执行收据（ADR 0040 §12 D1）：仅 {@code effectiveOutcome == ALLOW} 时存在；随 response_json 存储，
 *                         重放与回读返回同一份，不重签。V6.35.0 之前的 ALLOW 决策没有
 * @param inputSources     逐字段来源（ADR 0040 §11 D4）：verified 为空时为 null（不写出），与引入来源标记前逐字节相同
 * @param reasonCode       fail-closed 时的机器可读原因（见 {@link io.aster.guard.GuardReasonCode}），Verdict 正常返回时为 null
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GuardDecisionResponse(
    ApprovalView approval,
    List<String> controls,
    String decisionId,
    String effectiveOutcome,
    ExecutionReceipt executionReceipt,
    InputSourcesView inputSources,
    String outcome,
    PolicyVersionView policyVersion,
    String reason,
    String reasonCode,
    ReceiptView receipt,
    String role,
    String ruleId) {

  /** controls 归一为不可变列表：空列表不写出，回读得到 null，归一后写入前与回读后的响应 equals。 */
  public GuardDecisionResponse {
    controls = controls == null ? List.of() : List.copyOf(controls);
  }

  /** 无来源标记的响应（全部 declared）。 */
  public GuardDecisionResponse(ApprovalView approval, List<String> controls, String decisionId, String effectiveOutcome,
                               ExecutionReceipt executionReceipt, String outcome, PolicyVersionView policyVersion,
                               String reason, String reasonCode, ReceiptView receipt, String role, String ruleId) {
    this(approval, controls, decisionId, effectiveOutcome, executionReceipt, null, outcome, policyVersion, reason,
        reasonCode, receipt, role, ruleId);
  }

  /**
   * 按审批状态重算：{@code approval.status} 改为 {@code status}，{@code effectiveOutcome} 取该状态对应的结论。
   * 对同一状态重复调用结果不变；只用于带审批视图的（REQUIRE_APPROVAL / ESCALATE）响应。
   */
  public GuardDecisionResponse withApprovalStatus(GuardApprovalStatus status) {
    ApprovalView a = new ApprovalView(approval.expiresAt(), approval.id(), approval.requiredRole(), status.name());
    return new GuardDecisionResponse(a, controls, decisionId, status.effectiveOutcome(), executionReceipt, inputSources,
        outcome, policyVersion, reason, reasonCode, receipt, role, ruleId);
  }

  /** 附上审批转 ALLOW 时签发的执行收据。 */
  public GuardDecisionResponse withExecutionReceipt(ExecutionReceipt newReceipt) {
    return new GuardDecisionResponse(approval, controls, decisionId, effectiveOutcome, newReceipt, inputSources, outcome,
        policyVersion, reason, reasonCode, receipt, role, ruleId);
  }

  /**
   * 重放 / 回读时首次决策的事实已失效（ADR 0040 §11 D4）：只追加 {@code inputSources.factsStale = true}，
   * 其余字段与 effectiveOutcome 不变。只在读取时调用，结果不写回 response_json；无来源标记时原样返回。
   */
  public GuardDecisionResponse withFactsStale() {
    if (inputSources == null) {
      return this;
    }
    InputSourcesView stale = new InputSourcesView(inputSources.factIssuers(), Boolean.TRUE, inputSources.verified());
    return new GuardDecisionResponse(approval, controls, decisionId, effectiveOutcome, executionReceipt, stale, outcome,
        policyVersion, reason, reasonCode, receipt, role, ruleId);
  }

  /** 待审批视图；{@code expiresAt} 为 ISO-8601 字符串，{@code requiredRole} 为 null 表示任意复核人。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ApprovalView(String expiresAt, String id, String requiredRole, String status) {}

  /**
   * 逐字段来源视图：{@code verified} 为 verified 的点路径、{@code factIssuers} 为验签通过的 {@code issuer#kid}（均字典序）；
   * {@code factsStale} 只出现在重放 / 回读响应里（首次的事实已过期或签发方已失效），从不存储。
   * 列表归一为不可变（空列表不写出，回读得到 null，归一后写入前与回读后 equals）。
   */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record InputSourcesView(List<String> factIssuers, Boolean factsStale, List<String> verified) {
    public InputSourcesView {
      factIssuers = factIssuers == null ? List.of() : List.copyOf(factIssuers);
      verified = verified == null ? List.of() : List.copyOf(verified);
    }
  }

  /** 被评估的活跃策略版本：{@code id} 为版本行主键，{@code version} 为版本号。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record PolicyVersionView(Long id, String sourceHash, Long version) {}

  /** 决策收据：决策事件在审计哈希链中的行 id、本行哈希与前一行哈希。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ReceiptView(Long auditId, String hash, String prevHash) {}
}
