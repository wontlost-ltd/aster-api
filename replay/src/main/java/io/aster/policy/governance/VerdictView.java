package io.aster.policy.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import java.util.Optional;

/** 运行时 Verdict 值的结构化视图（ADR 0039 §5.1）。只认 `__type == "Verdict"` 的 Map，不做任何猜测。 */
// ADR 0039 §2.3：缺省的 role / reason 不写出键；字段按字母序，与 EvaluationResponse 的序列化约定一致。
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public record VerdictView(String outcome, String role, String reason) {
  public static Optional<VerdictView> from(Object result) {
    if (!(result instanceof Map<?, ?> m) || !"Verdict".equals(m.get("__type"))) return Optional.empty();
    Object outcome = m.get("outcome");
    if (!(outcome instanceof String o)) return Optional.empty();
    return Optional.of(new VerdictView(o, m.get("role") instanceof String r ? r : null, m.get("reason") instanceof String s ? s : null));
  }
}
