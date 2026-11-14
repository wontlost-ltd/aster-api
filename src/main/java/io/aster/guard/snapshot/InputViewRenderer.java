package io.aster.guard.snapshot;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardActionMapper;
import io.aster.policy.common.PIIRedactor;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.PiiMark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 审批输入视图渲染（ADR 0042 §11 D2 打码规则、§12 D4 / D6），纯函数。
 *
 * <p>只有 {@code context} 子树按声明树打码，{@code principal / action / resource / agent} 是身份与结构键，原样展示
 * （与收据一致）。{@code context} 内按 JSON 实际路径（对象键 {@code .}、数组元素 {@code []}，不带下标）对声明树做精确
 * 字符串匹配：
 * <ul>
 *   <li>已声明叶子、无 {@code @pii}：明文，其中的 String 再过一遍 {@link PIIRedactor}（规范 UUID 豁免）；</li>
 *   <li>已声明叶子、带 {@code @pii}：替换为 {@code {"$pii":{"level","category"}}}；解除视图还原原值；</li>
 *   <li>未声明（多出的键、与声明形状不符、超过深度 8）：整个子树替换为 {@code {"$pii":{"level":"unknown"}}}，键名也不展示，
 *       任何人都不能解除；</li>
 *   <li>声明为记录（含记录列表）的节点保留结构：对象键与数组长度可见。</li>
 * </ul>
 *
 * <p>快照哈希为 {@code sha256-sorted-json/v1} 作用于
 * {@code {decisionId, contextHash, input, piiMarks, maskRules, revealed, marksSource, inputBinding, inputSources}}。
 */
public final class InputViewRenderer {
  /** 打码规则版本；规则变化须升版，哈希随之不同。 */
  public static final String MASK_RULES = "v1";
  /** 与 guard context 深度上限一致：{@code context} 自身为第 1 层。 */
  static final int MAX_DEPTH = 8;
  static final String PII_KEY = "$pii";

  private InputViewRenderer() {
  }

  /**
   * @param input         渲染后的 action 视图（对象形态，未序列化）
   * @param revealedFields 解除视图中还原的 {@code @pii} 叶子数；打码视图为 0
   * @param hash          视图快照哈希
   * @param sizeBytes     哈希载荷的 UTF-8 字节数（视图上限按它判定）
   */
  public record RenderedView(Map<String, Object> input, List<Map<String, Object>> piiMarks, int revealedFields,
                             String hash, int sizeBytes) {
  }

  public static RenderedView render(UUID decisionId, String contextHash, Map<String, Object> actionJson,
                                    InputClassification classification, String marksSource, String inputBinding,
                                    Map<String, Object> inputSources, boolean revealed, PIIRedactor redactor) {
    InputClassification c = classification == null || GuardDecisionInputEntity.MARKS_NONE.equals(marksSource)
        ? InputClassification.empty() : classification;
    Walker w = new Walker(c, revealed, redactor);
    Map<String, Object> input = new LinkedHashMap<>();
    if (actionJson != null) {
      actionJson.forEach((k, v) -> input.put(k, "context".equals(k) ? w.node(v, "context", 1) : v));
    }
    List<Map<String, Object>> marks = c.marks().stream().map(InputViewRenderer::markView).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decisionId", decisionId == null ? null : decisionId.toString());
    body.put("contextHash", contextHash);
    body.put("input", input);
    body.put("piiMarks", marks);
    body.put("maskRules", MASK_RULES);
    body.put("revealed", revealed);
    body.put("marksSource", marksSource);
    body.put("inputBinding", inputBinding);
    body.put("inputSources", inputSources);
    byte[] bytes = GuardActionMapper.sortedJsonBytes(body);
    return new RenderedView(input, marks, w.revealedFields, GuardActionMapper.sortedJsonSha256(body), bytes.length);
  }

  private static Map<String, Object> markView(PiiMark m) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("path", m.path());
    v.put("level", m.level());
    v.put("category", m.category());
    v.put("source", m.source());
    return v;
  }

  static Map<String, Object> unknown() {
    return Map.of(PII_KEY, Map.of("level", "unknown"));
  }

  static Map<String, Object> masked(PiiMark m) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("level", m.level());
    if (m.category() != null) {
      inner.put("category", m.category());
    }
    return Map.of(PII_KEY, inner);
  }

  /** 一次渲染的遍历状态。 */
  private static final class Walker {
    private final Set<String> declared;
    private final Map<String, PiiMark> marks = new HashMap<>();
    /** 声明树中所有叶子的严格前缀（{@code a.b.c} → {@code a}、{@code a.b}；{@code x[].y} → {@code x[]}、{@code x}）。 */
    private final Set<String> containers = new HashSet<>();
    private final boolean revealed;
    private final PIIRedactor redactor;
    private int revealedFields;

    Walker(InputClassification c, boolean revealed, PIIRedactor redactor) {
      this.declared = new HashSet<>(c.declared());
      c.marks().forEach(m -> marks.put(m.path(), m));
      this.revealed = revealed;
      this.redactor = redactor;
      for (String path : c.declared()) {
        for (int i = 0; i < path.length(); i++) {
          char ch = path.charAt(i);
          if (ch == '.' || ch == '[') {
            containers.add(path.substring(0, i));
          }
          if (ch == ']') {
            containers.add(path.substring(0, i + 1));
          }
        }
      }
    }

    /** {@code path} 处的值；{@code depth} 为对象 / 数组嵌套层数（context 为 1）。 */
    Object node(Object value, String path, int depth) {
      if (depth > MAX_DEPTH) {
        return unknown();
      }
      if (declared.contains(path)) {
        return leaf(value, path);
      }
      if (value instanceof Map<?, ?> map && ("context".equals(path) || containers.contains(path))) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), node(v, path + "." + k, depth + 1)));
        return out;
      }
      if (value instanceof List<?> list && containers.contains(path + "[]")) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
          out.add(item instanceof Map<?, ?> && depth + 1 <= MAX_DEPTH ? node(item, path + "[]", depth + 1) : unknown());
        }
        return out;
      }
      return unknown();
    }

    /**
     * 已声明叶子：标量或标量数组（整体一个叶子）。形状不符（对象、或数组里含对象 / 数组）→ unknown，
     * 解除视图同样不还原——解除只作用于形状与声明一致的值，未声明的结构任何人都看不到。
     * {@code revealedFields} 只计实际还原的非 null 值。
     */
    private Object leaf(Object value, String path) {
      if (!scalarShaped(value)) {
        return unknown();
      }
      PiiMark mark = marks.get(path);
      if (mark != null) {
        if (!revealed) {
          return masked(mark);
        }
        if (value != null) {
          revealedFields++;
        }
        return value;
      }
      return plain(value);
    }

    private static boolean scalarShaped(Object value) {
      if (value instanceof Map<?, ?>) {
        return false;
      }
      return !(value instanceof List<?> list) || list.stream().noneMatch(i -> i instanceof Map<?, ?> || i instanceof List<?>);
    }

    /** 第二层正则：只作用于 String（含标量数组的元素），规范 UUID 豁免，与收据同一判定。 */
    private Object plain(Object value) {
      if (value instanceof String s) {
        return s.isBlank() || AuditChainAppender.isRedactionExempt("", s) ? s : redactor.redact(s);
      }
      if (value instanceof List<?> list) {
        List<Object> out = new ArrayList<>(list.size());
        list.forEach(item -> out.add(plain(item)));
        return out;
      }
      return value;
    }
  }
}
