package io.aster.policy.governance;

import java.util.Map;
import java.util.Optional;

/** 运行时 Verdict 值的结构化视图（ADR 0039 §5.1）。只认 `__type == "Verdict"` 的 Map，不做任何猜测。 */
public record VerdictView(String outcome, String role, String reason) {
  public static Optional<VerdictView> from(Object result) {
    if (!(result instanceof Map<?, ?> m) || !"Verdict".equals(m.get("__type"))) return Optional.empty();
    Object outcome = m.get("outcome");
    if (!(outcome instanceof String o)) return Optional.empty();
    return Optional.of(new VerdictView(o, m.get("role") instanceof String r ? r : null, m.get("reason") instanceof String s ? s : null));
  }
}
