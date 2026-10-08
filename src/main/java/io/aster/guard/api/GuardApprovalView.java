package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardDecisionEntity;

import java.time.Instant;
import java.util.List;

/**
 * 审批列表项：审批行的对外视图（ADR 0040 §4.2），并合入关联决策的上下文字段（ADR 0042 §4.3），
 * 供收件箱不再逐条回读决策。时间为 ISO-8601 字符串（与 {@link GuardDecisionResponse.ApprovalView} 一致），
 * null 字段不写出；决策行缺失时决策字段全部为 null。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardApprovalView(String actionName, String comment, List<String> controls, String createdAt,
                                String decidedAt, String decidedBy, String decisionId, String evidenceCorrelationId,
                                String expiresAt, String id, String outcome, String policyFunction, String policyModule,
                                String principalId, String reason, String requiredRole, String resourceId,
                                String resourceType, String ruleId, String status) {

  /**
   * @param d        关联决策，可为 null
   * @param controls 已解析的决策控制项（{@code guard_decisions.controls}），null 表示不写出
   */
  public static GuardApprovalView of(GuardApprovalEntity a, GuardDecisionEntity d, List<String> controls) {
    // 缺失的决策按「全空」处理，免去逐字段判空
    GuardDecisionEntity src = d != null ? d : new GuardDecisionEntity();
    return new GuardApprovalView(src.actionName, a.comment, controls, text(a.createdAt), text(a.decidedAt), a.decidedBy,
        a.decisionId.toString(), src.evidenceCorrelationId, text(a.expiresAt), a.id.toString(), a.outcome,
        src.policyFunction, src.policyModule, a.principalId, src.reason, a.requiredRole, emptyToNull(src.resourceId),
        emptyToNull(src.resourceType), src.ruleId, a.status.name());
  }

  private static String text(Instant t) {
    return t == null ? null : t.toString();
  }

  /** 决策行以空串表示「无资源」：对外与其他缺省字段一致，省略而不是写出空串。 */
  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }
}
