package io.aster.guard.provenance;

import io.aster.guard.GuardReasonCode;
import io.aster.guard.api.GuardActionRequest;
import io.aster.policy.governance.RuleMetadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 评估前的档案来源门（ADR 0040 §11 D5，ADR 0046 §11 D2）：模块档案要求 {@code verifiedInputs} 时，
 * 规则静态引用的输入路径必须全部 verified，否则不评估、直接 DENY。门只看规则元数据与本次请求的来源，不执行规则。
 *
 * <p>判定（按序）：
 * <ol>
 *   <li>档案不要求 → 放行（与引入本门之前逐字节相同）；</li>
 *   <li>IR 没有 {@code inputRefs}（旧引擎编译）→ {@code input_refs_unavailable}：基础设施缺位不读成策略违规，但也不放行；</li>
 *   <li>{@code inputRefs} 含 {@code "*"}（引用无法静态定位）→ 视为引用请求中出现的全部叶子，任一为 declared →
 *       {@code unverified_input}，{@code unverifiedFields = ["*"]}；</li>
 *   <li>其余引用路径中 declared 的（字典序）非空 → {@code unverified_input}。</li>
 * </ol>
 * verified 判定用 {@link InputSources#isVerified} 的祖先语义：覆盖 {@code context.customer} 的事实同时证实
 * {@code context.customer.tier}；反之子路径 verified 不能证实被整体引用的父对象。
 */
public final class VerifiedInputsGate {
  /** 引用无法静态定位的顶元（ADR 0046 §11 D2）。 */
  public static final String ALL_INPUTS = "*";

  private VerifiedInputsGate() {
  }

  /**
   * 门的拒绝结论。
   *
   * @param code             {@code unverified_input} 或 {@code input_refs_unavailable}
   * @param unverifiedFields 被引用却为 declared 的路径（字典序），写入收据 {@code unverifiedFields}；
   *                         {@code input_refs_unavailable} 时为空
   */
  public record GateDenial(GuardReasonCode code, List<String> unverifiedFields) {
    public GateDenial {
      unverifiedFields = List.copyOf(unverifiedFields);
    }
  }

  /**
   * @param presentLeafPaths 本次请求中出现的输入叶子路径（{@link #presentLeafPaths}），只在 {@code "*"} 时使用
   */
  public static Optional<GateDenial> check(RuleMetadata meta, InputSources sources, Set<String> presentLeafPaths) {
    Objects.requireNonNull(meta, "meta");
    if (!meta.verifiedInputsRequired()) {
      return Optional.empty();
    }
    List<String> refs = meta.inputRefs();
    if (refs == null) {
      return Optional.of(new GateDenial(GuardReasonCode.INPUT_REFS_UNAVAILABLE, List.of()));
    }
    if (refs.contains(ALL_INPUTS) && presentLeafPaths.stream().anyMatch(p -> !sources.isVerified(p))) {
      return Optional.of(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of(ALL_INPUTS)));
    }
    SortedSet<String> declared = new TreeSet<>();
    refs.stream().filter(r -> !ALL_INPUTS.equals(r) && !sources.isVerified(r)).forEach(declared::add);
    return declared.isEmpty()
        ? Optional.empty()
        : Optional.of(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.copyOf(declared)));
  }

  /**
   * 请求中出现的、规则可读到的输入叶子路径（字典序）：与 {@code GuardActionMapper.flatten} 交给规则的键一一对应——
   * {@code principal.id / type / roles}、{@code action.name}、{@code resource.type / id}、{@code context} 下的叶子、
   * {@code agent.model}。请求未给出的字段（规则读到的是缺省值）不算出现；数组整体是一个叶子（与 ADR 0040 §11 D1 一致），
   * 空对象自身算一个叶子（它被引用时同样需要证实）。{@code facts} 不是规则输入。
   */
  public static SortedSet<String> presentLeafPaths(GuardActionRequest.Action a) {
    SortedSet<String> leaves = new TreeSet<>();
    if (a == null) {
      return leaves;
    }
    if (a.principal() != null) {
      addIfPresent(leaves, "principal.id", a.principal().id());
      addIfPresent(leaves, "principal.type", a.principal().type());
      addIfPresent(leaves, "principal.roles", a.principal().roles());
    }
    addIfPresent(leaves, "action.name", a.action() == null ? null : a.action().name());
    if (a.resource() != null) {
      addIfPresent(leaves, "resource.type", a.resource().type());
      addIfPresent(leaves, "resource.id", a.resource().id());
    }
    addIfPresent(leaves, "agent.model", a.agent() == null ? null : a.agent().model());
    if (a.context() != null) {
      addObjectLeaves(leaves, "context", a.context());
    }
    return leaves;
  }

  private static void addIfPresent(Set<String> leaves, String path, Object value) {
    if (value != null) {
      leaves.add(path);
    }
  }

  /** context 自身（第 1 层）为空时不产出叶子：缺省 context 与 {@code {}} 对规则相同。null 叶子是出现的叶子。 */
  private static void addObjectLeaves(Set<String> leaves, String prefix, Map<?, ?> object) {
    object.forEach((key, value) -> {
      String path = prefix + "." + key;
      if (value instanceof Map<?, ?> child && !child.isEmpty()) {
        addObjectLeaves(leaves, path, child);
      } else {
        leaves.add(path);
      }
    });
  }
}
