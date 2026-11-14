package io.aster.guard.reconcile;

import java.util.Optional;

/**
 * 把一条副作用上报对到其收据快照（ADR 0054 §10.3），纯函数。判定顺序固定，第一条命中即返回：
 * 无 decisionId → 决策在本租户不存在 → 未有效消耗 → attemptId 不符 → actionHash 不符 → 同一上报方已有匹配（重放）→ 匹配。
 * 「有效消耗」只看 {@code consume_audit_id}（ADR 0041 §12 D1），不看 {@code consumed_at}。
 */
public final class SideEffectClassifier {
  public static final String MATCHED = "matched";

  /**
   * 上报引用的决策在本租户的收据快照。{@code decisionExists=false} 时其余字段无意义。
   *
   * @param consumeAuditId 权威消耗指针；null 即未有效消耗
   */
  public record ReceiptSnapshot(boolean decisionExists, boolean hasReceipt, Long consumeAuditId, String attemptId,
                                String actionHash) {
    public static final ReceiptSnapshot MISSING = new ReceiptSnapshot(false, false, null, null, null);
  }

  /** {@code reason ∈ no_decision_id | unknown_decision | not_consumed | attempt_mismatch | action_mismatch | replayed | matched}。 */
  public record Classification(Optional<OrphanKind> kind, String reason) {
    static Classification orphan(OrphanKind kind, String reason) {
      return new Classification(Optional.of(kind), reason);
    }
  }

  private SideEffectClassifier() {
  }

  /**
   * @param earlierMatchedSameSource 同租户、同 decisionId、同 source 已有更早的已匹配上报（含同批内先处理的那条）
   */
  public static Classification classify(GuardSideEffectEntity e, ReceiptSnapshot s, boolean earlierMatchedSameSource) {
    if (e.decisionId == null) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "no_decision_id");
    }
    if (!s.decisionExists()) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "unknown_decision");
    }
    if (!s.hasReceipt() || s.consumeAuditId() == null) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_UNCONSUMED, "not_consumed");
    }
    // attemptId 是 UUID 形态（两端都校验过），大小写不同仍是同一次尝试
    if (e.attemptId != null && !e.attemptId.equalsIgnoreCase(s.attemptId())) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_MISMATCH, "attempt_mismatch");
    }
    if (e.actionHash != null && !e.actionHash.equals(s.actionHash())) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_MISMATCH, "action_mismatch");
    }
    if (earlierMatchedSameSource) {
      return Classification.orphan(OrphanKind.SIDE_EFFECT_MISMATCH, "replayed");
    }
    return new Classification(Optional.empty(), MATCHED);
  }
}
