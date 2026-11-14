package io.aster.guard.reconcile;

import io.aster.guard.reconcile.SideEffectClassifier.Classification;
import io.aster.guard.reconcile.SideEffectClassifier.ReceiptSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 上报分类（ADR 0054 §10.3）：判定顺序固定，第一条命中即返回。 */
class SideEffectClassifierTest {
  private static final String ATTEMPT = "3f1c2a4e-0000-4000-8000-000000000002";
  private static final String HASH = "ab".repeat(32);
  private static final ReceiptSnapshot CONSUMED = new ReceiptSnapshot(true, true, 42L, ATTEMPT, HASH);

  private static GuardSideEffectEntity effect(UUID decisionId, String attemptId, String actionHash) {
    GuardSideEffectEntity e = new GuardSideEffectEntity();
    e.decisionId = decisionId;
    e.attemptId = attemptId;
    e.actionHash = actionHash;
    return e;
  }

  private static void assertKind(Classification c, OrphanKind kind, String reason) {
    assertThat(c.kind()).isEqualTo(Optional.ofNullable(kind));
    assertThat(c.reason()).isEqualTo(reason);
  }

  @Test
  void noDecisionId() {
    assertKind(SideEffectClassifier.classify(effect(null, null, null), ReceiptSnapshot.MISSING, false),
        OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "no_decision_id");
  }

  @Test
  void unknownDecision() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT, HASH), ReceiptSnapshot.MISSING, false),
        OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "unknown_decision");
  }

  @Test
  void decisionWithoutReceiptOrPointerIsUnconsumed() {
    for (ReceiptSnapshot s : new ReceiptSnapshot[] {new ReceiptSnapshot(true, false, null, null, null),
        new ReceiptSnapshot(true, true, null, ATTEMPT, HASH)}) {
      assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT, HASH), s, false),
          OrphanKind.SIDE_EFFECT_UNCONSUMED, "not_consumed");
    }
  }

  @Test
  void attemptMismatch() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), UUID.randomUUID().toString(), HASH), CONSUMED,
        false), OrphanKind.SIDE_EFFECT_MISMATCH, "attempt_mismatch");
  }

  @Test
  void actionMismatch() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT, "cd".repeat(32)), CONSUMED, false),
        OrphanKind.SIDE_EFFECT_MISMATCH, "action_mismatch");
  }

  @Test
  void replayed() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT, HASH), CONSUMED, true),
        OrphanKind.SIDE_EFFECT_MISMATCH, "replayed");
  }

  @Test
  void matched() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT, HASH), CONSUMED, false), null,
        "matched");
  }

  @Test
  void attemptIdOmittedStillMatches() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), null, null), CONSUMED, false), null, "matched");
  }

  @Test
  void attemptComparisonIgnoresUuidCase() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), ATTEMPT.toUpperCase(), HASH), CONSUMED, false),
        null, "matched");
  }

  @Test
  void replayCheckedAfterMismatch() {
    assertKind(SideEffectClassifier.classify(effect(UUID.randomUUID(), UUID.randomUUID().toString(), HASH), CONSUMED,
        true), OrphanKind.SIDE_EFFECT_MISMATCH, "attempt_mismatch");
  }
}
