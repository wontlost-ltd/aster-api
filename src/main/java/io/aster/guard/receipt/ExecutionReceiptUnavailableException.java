package io.aster.guard.receipt;

/** 执行收据签发失败（密钥不可用、签名异常）：决策按 DENY {@code receipt_unavailable}、审批按状态不变处理。 */
public class ExecutionReceiptUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ExecutionReceiptUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
