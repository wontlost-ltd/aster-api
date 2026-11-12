package io.aster.guard.provenance;

import io.aster.guard.GuardActionMapper.FlatField;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把 IR 中的 {@code inputRefs}（引擎产出的源码字段路径）映射为 guard 请求路径（ADR 0046 §11.6 第 3 条，guard 形状）。
 *
 * <p>只适用于 {@code POST /api/v1/guard/decisions}：规则的第一个参数是 {@code GuardActionMapper.flatten} 产出的扁平记录，
 * 所以引擎路径的首段是 flatten 的键。映射表与 flatten 共用 {@link FlatField}：
 * <ul>
 *   <li>标量字段（{@code principal_id → principal.id}、{@code action → action.name} 等）只映射裸键，带子路径 → {@code *}；</li>
 *   <li>{@code principal_roles} 与其任意子路径 → {@code principal.roles}（数组是一个整叶子）；</li>
 *   <li>{@code context} → {@code context}，{@code context.<p>} → {@code context.<p>}；</li>
 *   <li>根 {@code ""}（扁平记录整体被消费）、{@code *}、未知首段 → {@code *}。</li>
 * </ul>
 * 证据形状（from-evidence）的路径在写评估事件时已经映射为 {@code context.<键>.<path>} 或 {@code *}（引擎 1.0.44 之后），
 * 它们是本映射的不动点，所以门对两种入口用同一个映射。注意：from-evidence 从 IR 读到的<b>引擎原样</b>路径不是 guard 形状，
 * 经本映射多半落为 {@code *}（fail-closed）；引擎产出 inputRefs 之后，from-evidence 须改用事件自带的已映射路径，
 * 不得再把 IR 路径交给本映射。
 */
public final class GuardInputRefPaths {
  private static final Map<String, FlatField> BY_KEY = Arrays.stream(FlatField.values())
      .collect(Collectors.toUnmodifiableMap(FlatField::key, Function.identity()));

  private GuardInputRefPaths() {
  }

  /**
   * @param engineRefs IR 的 {@code inputRefs}；null 表示 IR 未提供，原样返回 null
   * @return 去重并按字典序排列的请求路径；无法映射的引用为 {@link VerifiedInputsGate#ALL_INPUTS}，与其他路径并存
   *         （具体路径即使请求里缺失也要计入）
   */
  public static List<String> guardShape(List<String> engineRefs) {
    if (engineRefs == null) {
      return null;
    }
    return List.copyOf(engineRefs.stream().map(GuardInputRefPaths::mapOne)
        .collect(Collectors.toCollection(TreeSet::new)));
  }

  private static String mapOne(String ref) {
    if (ref == null || ref.isEmpty()) {
      return VerifiedInputsGate.ALL_INPUTS;
    }
    int dot = ref.indexOf('.');
    String head = dot < 0 ? ref : ref.substring(0, dot);
    String rest = dot < 0 ? null : ref.substring(dot + 1);
    return Optional.ofNullable(BY_KEY.get(head))
        .map(field -> mapField(field, rest))
        .orElse(VerifiedInputsGate.ALL_INPUTS);
  }

  /** {@code rest} 为首段之后的子路径，null 表示裸键；空子路径（如 {@code "context."}）无法表示，按 {@code *}。 */
  private static String mapField(FlatField field, String rest) {
    if (rest == null) {
      return field.requestPath();
    }
    return switch (field.shape()) {
      case LIST -> field.requestPath();
      case OBJECT -> rest.isEmpty() ? VerifiedInputsGate.ALL_INPUTS : field.requestPath() + "." + rest;
      case SCALAR -> VerifiedInputsGate.ALL_INPUTS;
    };
  }
}
