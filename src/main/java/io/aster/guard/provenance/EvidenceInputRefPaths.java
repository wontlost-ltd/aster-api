package io.aster.guard.provenance;

import io.aster.policy.evidence.EvaluationEvidence;

import java.util.List;
import java.util.TreeSet;

/**
 * 把规则的引擎源码路径映射为<b>证据形状</b>的请求路径（ADR 0046 §11.6 第 3、4 条），在写 {@code POLICY_EVALUATION}
 * 事件时调用：from-evidence 拿不到 IR（evaluate-source 不落版本），也无法事后推断评估输入里哪个键对应规则的第一个参数，
 * 所以映射必须在写事件时完成。
 *
 * <ul>
 *   <li>只有第一个参数由命名输入 Map 中的某个键实际匹配时，{@code p} 映射为 {@code context.<键>.<p>}，
 *       根路径 {@code ""} 映射为 {@code context.<键>}；</li>
 *   <li>位置数组输入、匹配键缺失或无法用点路径无歧义表示（空、含 {@code .}、含控制字符）、或引用含 {@code *} →
 *       {@code ["*"]}（{@code *} 独占）；</li>
 *   <li>{@code []}（规则不读输入）照写，不需要匹配键；{@code null}（IR 未提供）原样返回 null，事件不写该键；</li>
 *   <li>映射后超过 {@link EvaluationEvidence#MAX_INPUT_REFS} 项或 {@link EvaluationEvidence#MAX_INPUT_REFS_BYTES}
 *       字节 → {@code ["*"]}（{@link EvaluationEvidence#capInputRefs}）。</li>
 * </ul>
 * 映射结果是 {@link GuardInputRefPaths#guardShape} 的不动点，门对两种入口用同一个判定。
 */
public final class EvidenceInputRefPaths {
  private static final List<String> STAR = List.of(VerifiedInputsGate.ALL_INPUTS);

  private EvidenceInputRefPaths() {
  }

  /**
   * @param engineRefs 规则的引擎源码路径（{@code RuleMetadata#inputRefs}）；null 表示没有
   * @param inputKey   第一个参数实际匹配到的命名输入键；null 表示位置输入或无法确定
   * @return 字典序去重的证据形状路径；null 当且仅当 engineRefs 为 null
   */
  public static List<String> evidenceShape(List<String> engineRefs, String inputKey) {
    if (engineRefs == null) {
      return null;
    }
    if (engineRefs.isEmpty()) {
      return List.of();
    }
    if (!representableKey(inputKey) || engineRefs.contains(VerifiedInputsGate.ALL_INPUTS)) {
      return STAR;
    }
    String prefix = "context." + inputKey;
    TreeSet<String> mapped = new TreeSet<>();
    engineRefs.forEach(ref -> mapped.add(ref.isEmpty() ? prefix : prefix + "." + ref));
    return EvaluationEvidence.capInputRefs(List.copyOf(mapped));
  }

  /** 与引擎 {@code InputRefsAnalyzer.representableKey} 同一口径：非空、不含点、不含控制字符（U+0000–U+001F）。 */
  private static boolean representableKey(String key) {
    return key != null && !key.isEmpty() && key.chars().noneMatch(c -> c == '.' || c < 0x20);
  }
}
