package io.aster.policy.evidence;

import java.util.*;

/** 一次评估的证据键（ADR 0041 §2.2）：进入 POLICY_EVALUATION 事件 metadata，并把 correlationId 回给调用方。 */
public record EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                                 Long policyVersionId, AgentIdentity agent) {
  public static final int MAX_TEXT = 255;
  public static final int MAX_CONTROLS = 16;
  public static final int MAX_CONTROL_LENGTH = 64;
  public static final String SOURCE_DECLARED = "declared";

  /** 生成新的 correlationId（随机 UUID）。 */
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId, AgentIdentity agent) {
    return new EvaluationEvidence(UUID.randomUUID().toString(), outcome, ruleId, controls, policyVersionId, agent);
  }

  /** 扁平键而非嵌套对象：追加器的脱敏与 guard 的预算收缩只处理顶层 String/List；null/空不写键。 */
  public Map<String, Object> toMetadata() {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "correlationId", correlationId);
    put(m, "outcome", clip(outcome));
    put(m, "ruleId", clip(ruleId));
    List<String> c = clipControls(controls);
    if (!c.isEmpty()) m.put("controls", c);
    if (policyVersionId != null) m.put("policyVersionId", policyVersionId);
    if (agent != null) {
      put(m, "agentProvider", clip(agent.provider()));
      put(m, "agentModel", clip(agent.model()));
      put(m, "agentVersion", clip(agent.version()));
      put(m, "agentSession", clip(agent.session()));
      m.put("agentSource", SOURCE_DECLARED);
    }
    return m;
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isEmpty()) m.put(k, v);
  }

  private static String clip(String s) {
    return s == null || s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
  }

  private static List<String> clipControls(List<String> controls) {
    if (controls == null) return List.of();
    return controls.stream().filter(Objects::nonNull).limit(MAX_CONTROLS)
        .map(c -> c.length() <= MAX_CONTROL_LENGTH ? c : c.substring(0, MAX_CONTROL_LENGTH)).toList();
  }
}
