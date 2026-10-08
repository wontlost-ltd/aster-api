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
    if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " 必填");
    return optional(field, v);
  }

  private static String optional(String field, String v) {
    if (v == null) return null;
    if (v.length() > MAX_TEXT) throw new IllegalArgumentException(field + " 超过 " + MAX_TEXT + " 字符");
    if (v.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " 含控制字符");
    return v;
  }
}
