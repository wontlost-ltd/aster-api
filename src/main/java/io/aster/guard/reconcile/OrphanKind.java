package io.aster.guard.reconcile;

import java.util.Arrays;
import java.util.Optional;

/**
 * 孤儿动作类别（ADR 0054 §10.3）。{@link #wire()} 是 DB CHECK、指标标签 {@code kind}、REST 字段与告警正则共用的字面量，
 * 五处必须一致；{@link #severity()} 只由类别决定，不另作标签。
 */
public enum OrphanKind {
  /** 上报无 decisionId，或该 id 在本租户不存在。 */
  SIDE_EFFECT_WITHOUT_DECISION("side_effect_without_decision", "critical", false),
  /** 上报的决策存在，但无收据行或未有效消耗。 */
  SIDE_EFFECT_UNCONSUMED("side_effect_unconsumed", "critical", false),
  /** 决策已消耗，但上报的 attemptId / actionHash 不符，或同一上报方重复落地。 */
  SIDE_EFFECT_MISMATCH("side_effect_mismatch", "critical", false),
  /** 已有效消耗、过了结果宽限仍无结果（执行侧丢失）；补到结果即解除。 */
  CONSUMED_WITHOUT_RESULT("consumed_without_result", "warning", true),
  /** ALLOW 收据过期未消耗；检出满 allow-unconsumed-ttl 后按 aged_out 解除。 */
  ALLOW_UNCONSUMED("allow_unconsumed", "info", true),
  /** 已有效消耗，但决策既非 ALLOW 也无 APPROVED 审批（aster-api 自身被绕过）。 */
  CONSUME_ON_NON_ALLOW("consume_on_non_allow", "critical", false),
  /** 有结果而无有效消耗。 */
  RESULT_WITHOUT_CONSUME("result_without_consume", "critical", false),
  /**
   * 链上有决策事件而本租户无决策行（#522：并发登记的落败方已先写链；或两步之间崩溃）。不可执行，只是链上噪声，
   * 单列为 info 使其不混入 critical 类别；与 {@link #ALLOW_UNCONSUMED} 同法按 aged_out 解除。
   */
  ORPHAN_RECEIPT("orphan_receipt", "info", true);

  private final String wire;
  private final String severity;
  private final boolean autoResolvable;

  OrphanKind(String wire, String severity, boolean autoResolvable) {
    this.wire = wire;
    this.severity = severity;
    this.autoResolvable = autoResolvable;
  }

  public String wire() {
    return wire;
  }

  /** {@code critical | warning | info}。 */
  public String severity() {
    return severity;
  }

  /** 是否会被对账自动解除（其余类别只能由管理员确认）。 */
  public boolean autoResolvable() {
    return autoResolvable;
  }

  public static Optional<OrphanKind> fromWire(String wire) {
    return Arrays.stream(values()).filter(k -> k.wire.equals(wire)).findFirst();
  }
}
