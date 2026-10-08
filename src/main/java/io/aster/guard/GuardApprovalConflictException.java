package io.aster.guard;

import java.util.Map;

/**
 * 审批请求被拒绝（ADR 0040 §4.2）。{@link #code()} 是对外的机器可读错误码，REST 层按它映射 HTTP 状态：
 * {@code approval_not_found}(404)、{@code user_required} / {@code invalid_approval} / {@code comment_required}(400)、
 * {@code segregation_of_duties} / {@code role_mismatch}(403)、{@code approval_not_pending}(409)、
 * {@code receipt_unavailable}(503)。
 * {@link #details()} 是并入错误体的附加字段（如 {@code role_mismatch} 的 {@code verifiedRoles}），默认为空。
 */
public class GuardApprovalConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final transient Map<String, Object> details;

  public GuardApprovalConflictException(String code, String message) {
    this(code, message, Map.of());
  }

  public GuardApprovalConflictException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  /** 不可变；没有附加字段时为空 Map。 */
  public Map<String, Object> details() {
    return details;
  }
}
