package io.aster.policy.evidence;

/** 行动中的模型身份（ADR 0041 §3）：调用方自报，收据标注 source=declared；provider/model 必填。 */
public record AgentIdentity(String provider, String model, String version, String session) {
  public static final int MAX_TEXT = 255;
  public static final String UNKNOWN_PROVIDER = "unknown";

  /** 校验并归一：provider/model 必填（有 agent 时），四字段 ≤255 且无控制字符；raw 为 null 返回 null。 */
  public static AgentIdentity validated(AgentIdentity raw) {
    if (raw == null) return null;
    return new AgentIdentity(
        required("agent.provider", raw.provider()),
        required("agent.model", raw.model()),
        optional("agent.version", raw.version()),
        optional("agent.session", raw.session()));
  }

  /** 仅有 model（guard 旧请求 Agent(model, session)）：provider 取 "unknown"；model 空白返回 null。 */
  public static AgentIdentity ofModel(String model, String session) {
    if (model == null || model.isBlank()) return null;
    return validated(new AgentIdentity(UNKNOWN_PROVIDER, model, null, session));
  }

  private static String required(String field, String v) {
    if (v == null || v.isBlank()) throw new InvalidAgentException(field, InvalidAgentException.Reason.REQUIRED, field + " 必填");
    return optional(field, v);
  }

  private static String optional(String field, String v) {
    if (v == null) return null;
    if (v.length() > MAX_TEXT) {
      throw new InvalidAgentException(field, InvalidAgentException.Reason.TOO_LONG, field + " 超过 " + MAX_TEXT + " 字符");
    }
    if (v.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidAgentException(field, InvalidAgentException.Reason.CONTROL_CHAR, field + " 含控制字符");
    }
    return v;
  }

  /**
   * agent 校验失败：带出错字段与原因，供 REST 层按请求语言重新组织文案（aster-api#445）。
   * 仍是 {@link IllegalArgumentException}，既有按该类型捕获的调用方（guard）行为不变。
   */
  public static final class InvalidAgentException extends IllegalArgumentException {
    /** 失败原因，对应文案目录里的 {@code agent.<reason>} 条目。 */
    public enum Reason { REQUIRED, TOO_LONG, CONTROL_CHAR }

    private final String field;
    private final Reason reason;

    InvalidAgentException(String field, Reason reason, String message) {
      super(message);
      this.field = field;
      this.reason = reason;
    }

    public String field() {
      return field;
    }

    public Reason reason() {
      return reason;
    }
  }
}
