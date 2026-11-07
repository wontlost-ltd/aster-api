package io.aster.policy.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次评估的证据键（ADR 0041 §2.2）：进入 POLICY_EVALUATION 事件 metadata，并把 correlationId 回给调用方。
 * profile 为被执行模块声明的治理档案 id（ADR 0046 §6），未声明时为 null 且不写键。
 *
 * <p>版本三键把决策钉到封存版本（aster-api#459）。{@code sourceHash} 一律是源码内容哈希
 * （{@code PolicyVersion#computeSourceHash}），与 guard 收据（ADR 0040 §5.3）、`PolicyVersion.sourceHash`
 * 同口径，三处可直接比较：/evaluate 取被执行活跃版本的 {@code id / sourceHash / sourceEnvelopeSha256}；
 * /evaluate-source 没有已保存版本，只写即时源码的 {@code sourceHash}。{@code sourceEnvelopeSha256} 是完整编译
 * 输入的信封哈希（ADR 0022 §11.5），有则写，旧版本与即时源码为 null。三键由 {@link #withVersion} 在评估
 * 结束后附加，correlationId 不变。
 */
public record EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                                 Long policyVersionId, AgentIdentity agent, String role, String reason,
                                 String profile, String sourceHash, String sourceEnvelopeSha256) {
  public static final int MAX_CONTROLS = 16;
  public static final int MAX_CONTROL_LENGTH = 64;
  public static final String SOURCE_DECLARED = "declared";
  /** 模型身份的五个扁平 metadata 键：评估事件与 guard 收据共用同一组键名，两边不得各写字面量。 */
  public static final String KEY_AGENT_PROVIDER = "agentProvider";
  public static final String KEY_AGENT_MODEL = "agentModel";
  public static final String KEY_AGENT_VERSION = "agentVersion";
  public static final String KEY_AGENT_SESSION = "agentSession";
  public static final String KEY_AGENT_SOURCE = "agentSource";

  public static final String KEY_ROLE = "role";
  public static final String KEY_REASON = "reason";
  public static final String KEY_PROFILE = "profile";
  public static final String KEY_POLICY_VERSION_ID = "policyVersionId";
  public static final String KEY_SOURCE_HASH = "sourceHash";
  public static final String KEY_SOURCE_ENVELOPE_SHA256 = "sourceEnvelopeSha256";

  /** 无版本哈希的构造（版本键经 {@link #withVersion} 附加）。 */
  public EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                            Long policyVersionId, AgentIdentity agent, String role, String reason,
                            String profile) {
    this(correlationId, outcome, ruleId, controls, policyVersionId, agent, role, reason, profile, null, null);
  }

  /** 无治理档案的构造。 */
  public EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                            Long policyVersionId, AgentIdentity agent, String role, String reason) {
    this(correlationId, outcome, ruleId, controls, policyVersionId, agent, role, reason, null);
  }

  /** 生成新的 correlationId（随机 UUID）；无 Verdict 的 role/reason 时使用。 */
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId,
                                      AgentIdentity agent) {
    return of(outcome, ruleId, controls, policyVersionId, agent, null, null);
  }

  /** role/reason 来自 Verdict（ADR 0042 §3.2）：from-evidence 据此建决策，不重复评估。 */
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId,
                                      AgentIdentity agent, String role, String reason) {
    return of(outcome, ruleId, controls, policyVersionId, agent, role, reason, null);
  }

  /** 携带模块声明的治理档案（ADR 0046 §6）。 */
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId,
                                      AgentIdentity agent, String role, String reason, String profile) {
    return new EvaluationEvidence(UUID.randomUUID().toString(), outcome, ruleId, controls, policyVersionId, agent,
        role, reason, profile);
  }

  /**
   * 附加被执行版本的身份：同一 correlationId，只替换版本三键。各自可为 null（null 不写键）：
   * /evaluate 失败路径全为 null，/evaluate-source 只有内容哈希，无 envelope 的旧版本无信封哈希。
   */
  public EvaluationEvidence withVersion(Long policyVersionId, String sourceHash, String sourceEnvelopeSha256) {
    return new EvaluationEvidence(correlationId, outcome, ruleId, controls, policyVersionId, agent, role, reason,
        profile, sourceHash, sourceEnvelopeSha256);
  }

  /** 扁平键而非嵌套对象：追加器的脱敏与 guard 的预算收缩只处理顶层 String/List；null/空不写键。 */
  public Map<String, Object> toMetadata() {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "correlationId", clip(correlationId));
    put(m, "outcome", clip(outcome));
    put(m, "ruleId", clip(ruleId));
    List<String> c = clipControls(controls);
    if (!c.isEmpty()) m.put("controls", c);
    if (policyVersionId != null) m.put(KEY_POLICY_VERSION_ID, policyVersionId);
    // 两个哈希是身份键（AuditChainAppender.IDENTITY_METADATA_KEYS），逐字入链、不脱敏；64 位 hex 远低于裁剪上界
    put(m, KEY_SOURCE_HASH, clip(sourceHash));
    put(m, KEY_SOURCE_ENVELOPE_SHA256, clip(sourceEnvelopeSha256));
    put(m, KEY_ROLE, clip(role));
    put(m, KEY_REASON, clip(reason));
    put(m, KEY_PROFILE, clip(profile));
    if (agent != null) {
      put(m, KEY_AGENT_PROVIDER, clip(agent.provider()));
      put(m, KEY_AGENT_MODEL, clip(agent.model()));
      put(m, KEY_AGENT_VERSION, clip(agent.version()));
      put(m, KEY_AGENT_SESSION, clip(agent.session()));
      m.put(KEY_AGENT_SOURCE, SOURCE_DECLARED);
    }
    return m;
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isEmpty()) m.put(k, v);
  }

  /** 文本上界与 agent 请求校验同源（{@link AgentIdentity#MAX_TEXT}）。 */
  private static String clip(String s) {
    return clip(s, AgentIdentity.MAX_TEXT);
  }

  /** 按 UTF-16 单元裁剪；若截断点落在代理对中间（保留段末位是高位代理），多退一位，避免孤立代理字符。 */
  private static String clip(String s, int max) {
    if (s == null || s.length() <= max) return s;
    int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
    return s.substring(0, end);
  }

  private static List<String> clipControls(List<String> controls) {
    if (controls == null) return List.of();
    return controls.stream().filter(Objects::nonNull).limit(MAX_CONTROLS)
        .map(c -> clip(c, MAX_CONTROL_LENGTH)).toList();
  }
}
