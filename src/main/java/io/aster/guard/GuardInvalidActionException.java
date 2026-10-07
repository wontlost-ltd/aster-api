package io.aster.guard;

/** 请求体校验失败（缺必填项、超长、context 超限等），REST 层映射为 400 {@code invalid_action}。 */
public class GuardInvalidActionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GuardInvalidActionException(String message) {
    super(message);
  }

  public GuardReasonCode reasonCode() {
    return GuardReasonCode.INVALID_ACTION;
  }
}
