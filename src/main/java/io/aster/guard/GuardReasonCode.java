package io.aster.guard;

/** Action Guard fail-closed 原因码（ADR 0040 §3.2）。{@link #code()} 是对外 JSON 中的取值。 */
public enum GuardReasonCode {
  NOT_A_VERDICT("not_a_verdict"),
  EVALUATION_FAILED("evaluation_failed"),
  RECEIPT_UNAVAILABLE("receipt_unavailable"),
  POLICY_NOT_FOUND("policy_not_found"),
  INVALID_ACTION("invalid_action");

  private final String code;

  GuardReasonCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
