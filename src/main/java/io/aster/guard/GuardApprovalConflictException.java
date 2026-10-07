package io.aster.guard;

/**
 * 审批请求被拒绝（ADR 0040 §4.2）。{@link #code()} 是对外的机器可读错误码，REST 层按它映射 HTTP 状态：
 * {@code approval_not_found}(404)、{@code invalid_approval} / {@code comment_required}(400)、
 * {@code segregation_of_duties} / {@code role_mismatch}(403)、{@code approval_not_pending}(409)、
 * {@code receipt_unavailable}(503)。
 */
public class GuardApprovalConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public GuardApprovalConflictException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
