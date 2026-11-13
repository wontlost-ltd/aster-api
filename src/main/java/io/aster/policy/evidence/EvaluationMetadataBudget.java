package io.aster.policy.evidence;

import io.aster.audit.chain.AuditMetadataSize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POLICY_EVALUATION 事件 metadata 的写前预检（aster-api#493）：按追加器口径（{@link AuditMetadataSize#measure}，含 PII 脱敏
 * 变长与 JSON 转义）测量，超过 {@link AuditMetadataSize#BUDGET} 时按固定顺序确定性降级，绝不交给追加器截断成非法 JSON。
 *
 * <p>步骤（每步做完即重新测量，达标就停）：
 * <ol>
 *   <li>请求侧自由文本封顶：{@code applicantId}（/evaluate 的 context 字段）与 {@code locale}（/evaluate-source 请求体）
 *       超过 {@link #MAX_TEXT} 一律截断——不论是否超预算，这是它们的固定上限；</li>
 *   <li>按 {@link #SHRINK_ORDER} 逐个缩短可选文本：每轮缩到一半，缩空即删键，缩完一个键仍超才处理下一个；</li>
 *   <li>{@code inputRefs} 整体降为 {@code ["*"]}（不删除）：要求 verifiedInputs 的档案下门因此更严而不是放行；</li>
 *   <li>仍超限说明结论键本身（role / ruleId / controls / outcome）超预算，只可能来自病态策略：只留 {@link #OVERSIZE_KEPT}，
 *       去掉 outcome 与 role 使事件不可审批（from-evidence 409 evidence_not_approvable）——绝不留下缺 role 却可审批的事件；</li>
 *   <li>最小键集仍超限（保留键本身都是病态取值）：只留截到列宽的 correlationId 与 inputSource。</li>
 * </ol>
 * 只要发生过降级就写 {@link #KEY_METADATA_DEGRADED}：被动过的键名，按动手顺序排列；最小键集再追加 {@link #OVERSIZE}。
 *
 * <p>永不缩短、永不删除（第 4 步之前）：correlationId、outcome、ruleId、role、controls、profile、policyVersionId、
 * sourceHash、sourceEnvelopeSha256、verifiedInputsRequired、inputSource、agentSource，以及 contextSize 等定长附加键。
 * from-evidence 依赖 outcome / role / reason / ruleId / controls / profile / inputRefs / policyVersionId；其中 reason 只是
 * 决策说明文本，缩短不改变决策，排在最后缩。预算内的事件原样返回（键集与取值不变）。
 */
public final class EvaluationMetadataBudget {
  public static final String KEY_APPLICANT_ID = "applicantId";
  public static final String KEY_LOCALE = "locale";
  public static final String KEY_CORRELATION_ID = "correlationId";
  public static final String KEY_METADATA_DEGRADED = "metadataDegraded";
  /** 降级标记里表示「退到最小键集」的项。 */
  public static final String OVERSIZE = "oversize";
  /** applicantId / locale 的固定上限，与 agent 文本同源。 */
  public static final int MAX_TEXT = AgentIdentity.MAX_TEXT;
  /** 与 audit_logs.correlation_id 列宽、from-evidence 的相关键上限一致。 */
  static final int MAX_CORRELATION_ID = 64;

  /** 可选文本的缩短顺序：先缩对决策无影响、来自请求的键，最后才缩决策说明 reason。 */
  public static final List<String> SHRINK_ORDER = List.of(KEY_APPLICANT_ID, KEY_LOCALE,
      EvaluationEvidence.KEY_AGENT_SESSION, EvaluationEvidence.KEY_AGENT_VERSION, EvaluationEvidence.KEY_AGENT_MODEL,
      EvaluationEvidence.KEY_AGENT_PROVIDER, EvaluationEvidence.KEY_REASON);

  /** 第 4 步保留的键：身份、版本与档案键；inputRefs 此时已是 {@code ["*"]}。 */
  public static final List<String> OVERSIZE_KEPT = List.of(KEY_CORRELATION_ID, "ruleId", EvaluationEvidence.KEY_PROFILE,
      EvaluationEvidence.KEY_POLICY_VERSION_ID, EvaluationEvidence.KEY_SOURCE_HASH,
      EvaluationEvidence.KEY_SOURCE_ENVELOPE_SHA256, EvaluationEvidence.KEY_INPUT_REFS,
      EvaluationEvidence.KEY_VERIFIED_INPUTS_REQUIRED, EvaluationEvidence.KEY_AGENT_SOURCE,
      EvaluationEvidence.KEY_INPUT_SOURCE);

  private static final List<String> ALL_INPUTS = List.of("*");

  private EvaluationMetadataBudget() {
  }

  /**
   * 返回不超过预算的 metadata 副本，不修改入参；null 视为空。
   */
  public static Map<String, Object> fit(Map<String, Object> metadata) {
    Map<String, Object> m = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    List<String> degraded = new ArrayList<>();
    capText(m, KEY_APPLICANT_ID, degraded);
    capText(m, KEY_LOCALE, degraded);
    if (!overBudget(m, degraded)) {
      return withMarker(m, degraded); // 常见路径：只测量一次
    }
    for (String key : SHRINK_ORDER) {
      shrinkUntilFits(m, key, degraded);
    }
    if (overBudget(m, degraded) && m.containsKey(EvaluationEvidence.KEY_INPUT_REFS)
        && !ALL_INPUTS.equals(m.get(EvaluationEvidence.KEY_INPUT_REFS))) {
      m.put(EvaluationEvidence.KEY_INPUT_REFS, ALL_INPUTS);
      degraded.add(EvaluationEvidence.KEY_INPUT_REFS);
    }
    if (overBudget(m, degraded)) {
      m = keepOnly(m, OVERSIZE_KEPT);
      degraded.add(OVERSIZE);
      if (overBudget(m, degraded)) {
        m = minimal(m);
      }
    }
    return withMarker(m, degraded);
  }

  /** 有降级时写入降级标记（键名列表的不可变副本）。 */
  private static Map<String, Object> withMarker(Map<String, Object> m, List<String> degraded) {
    if (!degraded.isEmpty()) {
      m.put(KEY_METADATA_DEGRADED, List.copyOf(degraded));
    }
    return m;
  }

  /** 测量时把降级标记计入：最终写出的 metadata 含该键。 */
  private static boolean overBudget(Map<String, Object> m, List<String> degraded) {
    Map<String, Object> measured = degraded.isEmpty() ? m : withMarker(new LinkedHashMap<>(m), degraded);
    return AuditMetadataSize.measure(measured) > AuditMetadataSize.BUDGET;
  }

  private static void capText(Map<String, Object> m, String key, List<String> degraded) {
    if (m.get(key) instanceof String s && s.length() > MAX_TEXT) {
      m.put(key, clip(s, MAX_TEXT));
      degraded.add(key);
    }
  }

  private static void shrinkUntilFits(Map<String, Object> m, String key, List<String> degraded) {
    while (m.get(key) instanceof String s && overBudget(m, degraded)) {
      if (!degraded.contains(key)) {
        degraded.add(key);
      }
      String half = clip(s, s.length() / 2);
      if (half.isEmpty()) {
        m.remove(key);
      } else {
        m.put(key, half);
      }
    }
  }

  private static Map<String, Object> keepOnly(Map<String, Object> m, List<String> keys) {
    Map<String, Object> kept = new LinkedHashMap<>();
    for (String k : keys) {
      if (m.containsKey(k)) {
        kept.put(k, m.get(k));
      }
    }
    return kept;
  }

  /** 最后一档：只剩相关键（截到列宽，from-evidence 本就拒绝更长的键）与输入来源常量，长度有固定上界。 */
  private static Map<String, Object> minimal(Map<String, Object> m) {
    Map<String, Object> min = new LinkedHashMap<>();
    if (m.get(KEY_CORRELATION_ID) instanceof String cid) {
      min.put(KEY_CORRELATION_ID, clip(cid, MAX_CORRELATION_ID));
    }
    if (m.containsKey(EvaluationEvidence.KEY_INPUT_SOURCE)) {
      min.put(EvaluationEvidence.KEY_INPUT_SOURCE, m.get(EvaluationEvidence.KEY_INPUT_SOURCE));
    }
    return min;
  }

  /** 按 UTF-16 单元截断，不切开代理对。 */
  private static String clip(String s, int max) {
    if (s.length() <= max) {
      return s;
    }
    int end = max > 0 && Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
    return s.substring(0, end);
  }
}
