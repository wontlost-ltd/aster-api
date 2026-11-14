package io.aster.guard.reconcile;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6.38.0 三张表的往返与约束（ADR 0054 §10）。 */
@QuarkusTest
class GuardReconcilePersistenceTest {
  private static final String TENANT = "tenant-reconcile-persist-" + UUID.randomUUID().toString().substring(0, 8);
  private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

  static GuardSideEffectEntity sideEffect(String tenant, String source, String effectId) {
    GuardSideEffectEntity e = new GuardSideEffectEntity();
    e.tenantId = tenant;
    e.source = source;
    e.effectId = effectId;
    e.occurredAt = T0;
    e.actionName = "refund";
    e.resourceType = "order";
    e.resourceId = "O-1";
    e.decisionId = UUID.randomUUID();
    e.attemptId = UUID.randomUUID().toString();
    e.actionHash = "a".repeat(64);
    e.reportedBy = "user-1";
    e.receivedAt = T0;
    return e;
  }

  static GuardOrphanActionEntity orphan(String tenant, OrphanKind kind, String subject, Long sideEffectId) {
    GuardOrphanActionEntity o = new GuardOrphanActionEntity();
    o.tenantId = tenant;
    o.kind = kind.wire();
    o.severity = kind.severity();
    o.subjectKey = subject;
    o.sideEffectId = sideEffectId;
    o.executorLabel = "other";
    o.detail = "{\"effectId\":\"e-1\"}";
    o.firstRunId = 1L;
    o.detectedAt = T0;
    return o;
  }

  @Test
  void entitiesRoundTrip() {
    String source = "billing-" + UUID.randomUUID().toString().substring(0, 6);
    GuardSideEffectEntity se = sideEffect(TENANT, source, "e-1");
    QuarkusTransaction.requiringNew().run(() -> se.persist());
    GuardOrphanActionEntity o = orphan(TENANT, OrphanKind.SIDE_EFFECT_MISMATCH, "se:" + se.id, se.id);
    GuardReconciliationRunEntity run = new GuardReconciliationRunEntity();
    run.tenantId = TENANT;
    run.windowStart = T0.minus(7, ChronoUnit.DAYS);
    run.windowEnd = T0;
    run.startedAt = T0;
    run.finishedAt = T0;
    run.sideEffectsChecked = 3;
    run.newOrphans = 1;
    run.newByKind = "{\"side_effect_mismatch\":1}";
    QuarkusTransaction.requiringNew().run(() -> {
      o.persist();
      run.persist();
    });

    GuardSideEffectEntity se2 = QuarkusTransaction.requiringNew().call(() -> GuardSideEffectEntity.findById(se.id));
    assertThat(se2.decisionId).isEqualTo(se.decisionId);
    assertThat(se2.occurredAt).isEqualTo(T0);
    assertThat(se2.reconciledAt).isNull();
    GuardOrphanActionEntity o2 = QuarkusTransaction.requiringNew().call(() -> GuardOrphanActionEntity.findById(o.id));
    assertThat(o2.status).isEqualTo("OPEN");
    assertThat(o2.sideEffectId).isEqualTo(se.id);
    assertThat(o2.detail).contains("e-1");
    GuardReconciliationRunEntity run2 = QuarkusTransaction.requiringNew()
        .call(() -> GuardReconciliationRunEntity.latest(TENANT).orElseThrow());
    assertThat(run2.id).isEqualTo(run.id);
    assertThat(run2.newByKind).contains("side_effect_mismatch");
    assertThat(run2.auditId).isNull();
  }

  @Test
  void duplicateSideEffectKeyViolatesUnique() {
    String source = "dup-" + UUID.randomUUID().toString().substring(0, 6);
    QuarkusTransaction.requiringNew().run(() -> sideEffect(TENANT, source, "e-dup").persist());
    assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
        .run(() -> sideEffect(TENANT, source, "e-dup").persistAndFlush()))
        .hasStackTraceContaining("guard_side_effects_uq");
  }

  @Test
  void bogusKindViolatesCheck() {
    GuardOrphanActionEntity o = orphan(TENANT, OrphanKind.ALLOW_UNCONSUMED, UUID.randomUUID().toString(), null);
    o.kind = "bogus";
    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> o.persistAndFlush()))
        .hasStackTraceContaining("guard_orphan_actions_kind_ck");
  }

  @Test
  void orphanReceiptKindAccepted() {
    GuardOrphanActionEntity o = orphan(TENANT, OrphanKind.ORPHAN_RECEIPT, UUID.randomUUID().toString(), null);
    QuarkusTransaction.requiringNew().run(() -> o.persistAndFlush());
    assertThat(o.id).isNotNull();
  }

  @Test
  void deletingSideEffectNullsOrphanReference() {
    String source = "del-" + UUID.randomUUID().toString().substring(0, 6);
    GuardSideEffectEntity se = sideEffect(TENANT, source, "e-del");
    QuarkusTransaction.requiringNew().run(() -> se.persist());
    GuardOrphanActionEntity o = orphan(TENANT, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "se:" + se.id, se.id);
    QuarkusTransaction.requiringNew().run(() -> o.persist());

    QuarkusTransaction.requiringNew().run(() -> GuardSideEffectEntity.deleteById(se.id));

    GuardOrphanActionEntity after = QuarkusTransaction.requiringNew().call(() -> GuardOrphanActionEntity.findById(o.id));
    assertThat(after.sideEffectId).isNull();
    assertThat(after.detail).contains("e-1");
  }
}
