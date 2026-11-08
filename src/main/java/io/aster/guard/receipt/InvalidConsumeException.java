package io.aster.guard.receipt;

/** consume 入参不合法（400 {@code invalid_consume}）；{@link #field()} 为首个不合法的字段名，解析失败时为 null。 */
public class InvalidConsumeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String field;

  public InvalidConsumeException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
