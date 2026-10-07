package io.aster.guard;

/**
 * 同一 {@code (tenant_id, idempotency_key)} 已绑定另一个提议动作（策略地址、主体、动作、资源或 context 摘要不同）。
 * 幂等键只能重放同一动作，不能把一个动作的结论借给另一个动作；REST 层映射为 422 {@code idempotency_mismatch}。
 */
public class GuardIdempotencyMismatchException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GuardIdempotencyMismatchException(String message) {
    super(message);
  }
}
