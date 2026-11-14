package io.aster.policy.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 一次评估输入的分类（ADR 0042 §12 D2）：{@code declared} 为全部已声明叶子路径，{@code marks} 为其中带 {@code @pii}
 * 的路径及标记，两者均按路径字典序，{@code declared ⊇ marks.path}。声明树让「已声明且无 PII」与「未声明」可以区分：
 * 前者原样展示，后者按 {@code unknown} 整体隐藏。
 *
 * <p>存于 {@code guard_evaluation_classifications.classification} 与 {@code guard_decision_inputs.classification}
 * （JSONB），形状 {@code {"declared": [...], "marks": [{path, level, category, source}...]}}。
 */
public record InputClassification(List<String> declared, List<PiiMark> marks) {
  /** 序列化键序固定（字母序），同一分类恒得同一字节，便于比对与测试。 */
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
      .build();

  public InputClassification {
    declared = declared == null ? List.of() : List.copyOf(declared);
    marks = marks == null ? List.of() : List.copyOf(marks);
  }

  /** 空分类：没有任何已声明路径。 */
  public static InputClassification empty() {
    return new InputClassification(List.of(), List.of());
  }

  public boolean isDeclared(String path) {
    return declared.contains(path);
  }

  public String toJson() {
    try {
      return JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("输入分类序列化失败", e);
    }
  }

  /** null 原样返回 null；格式错误抛 {@link IllegalArgumentException}。 */
  public static InputClassification fromJson(String json) {
    if (json == null) {
      return null;
    }
    try {
      return JSON.readValue(json, InputClassification.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("输入分类 JSON 不合法", e);
    }
  }
}
