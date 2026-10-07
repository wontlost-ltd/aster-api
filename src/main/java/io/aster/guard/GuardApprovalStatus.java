package io.aster.guard;

/**
 * Action Guard 审批状态（ADR 0040 §4.1），每个状态对应 Agent 视角的 effectiveOutcome：
 * 批准放行，拒绝与过期一律拒绝，未决仍为 PENDING。
 */
public enum GuardApprovalStatus {
  PENDING("PENDING"),
  APPROVED("ALLOW"),
  REJECTED("DENY"),
  EXPIRED("DENY");

  private final String effectiveOutcome;

  GuardApprovalStatus(String effectiveOutcome) {
    this.effectiveOutcome = effectiveOutcome;
  }

  public String effectiveOutcome() {
    return effectiveOutcome;
  }
}
