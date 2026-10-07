package io.aster.guard;

/** 策略不存在或无活跃版本，REST 层映射为 404 {@code policy_not_found}。此时不评估、不写收据。 */
public class GuardPolicyNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GuardPolicyNotFoundException(String message) {
    super(message);
  }

  public GuardReasonCode reasonCode() {
    return GuardReasonCode.POLICY_NOT_FOUND;
  }
}
