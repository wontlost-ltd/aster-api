package io.aster.guard;

/**
 * 同一 {@code (tenant_id, idempotency_key)} 已被并发请求先一步写入。
 * 调用方应回读先到者的决策并按重放语义返回（指纹不符时报 {@link GuardIdempotencyMismatchException}），
 * 而不是报错（ADR 0040 §3.1 幂等语义）。
 */
public class GuardIdempotencyRaceException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GuardIdempotencyRaceException(Throwable cause) {
    super("幂等键并发冲突", cause);
  }
}
