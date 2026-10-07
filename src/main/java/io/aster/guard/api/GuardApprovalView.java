package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.aster.guard.GuardApprovalEntity;

import java.time.Instant;

/**
 * 审批列表项：审批行的对外视图（ADR 0040 §4.2）。时间为 ISO-8601 字符串（与
 * {@link GuardDecisionResponse.ApprovalView} 一致），null 字段不写出。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardApprovalView(String comment, String createdAt, String decidedAt, String decidedBy, String decisionId,
                                String expiresAt, String id, String outcome, String principalId, String requiredRole,
                                String status) {

  static GuardApprovalView of(GuardApprovalEntity a) {
    return new GuardApprovalView(a.comment, text(a.createdAt), text(a.decidedAt), a.decidedBy, a.decisionId.toString(),
        text(a.expiresAt), a.id.toString(), a.outcome, a.principalId, a.requiredRole, a.status.name());
  }

  private static String text(Instant t) {
    return t == null ? null : t.toString();
  }
}
