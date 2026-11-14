package io.aster.guard.reconcile;

import io.aster.guard.reconcile.SideEffectReport.ActionRef;
import io.aster.guard.reconcile.SideEffectReport.Effect;
import io.aster.guard.reconcile.SideEffectReport.ResourceRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 上报请求校验（ADR 0054 §10.2）：每条规则一个用例，断言 index 与 field。 */
class SideEffectReportTest {
  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final String DECISION = "3f1c2a4e-0000-4000-8000-000000000001";
  private static final String ATTEMPT = "3f1c2a4e-0000-4000-8000-000000000002";

  static Effect valid(String effectId) {
    return new Effect(effectId, "billing-db", "2026-11-14T09:59:00Z", new ActionRef("refund"),
        new ResourceRef("order", "O-1"), DECISION, ATTEMPT, "ab".repeat(32));
  }

  private static SideEffectReport.InvalidSideEffectException invalid(SideEffectReport r) {
    return catchThrowableOfType(SideEffectReport.InvalidSideEffectException.class,
        () -> SideEffectReport.validate(r, NOW));
  }

  /** 第 2 条（index 1）按给定方式改坏，第 1 条合法。 */
  private static SideEffectReport.InvalidSideEffectException secondBroken(UnaryOperator<Effect> broken) {
    return invalid(new SideEffectReport(List.of(valid("e-0"), broken.apply(valid("e-1")))));
  }

  private static Effect with(Effect e, String effectId, String source, String occurredAt, ActionRef action,
                             ResourceRef resource, String decisionId, String attemptId, String actionHash) {
    return new Effect(effectId, source, occurredAt, action, resource, decisionId, attemptId, actionHash);
  }

  @Test
  void validBatchPasses() {
    assertThatCode(() -> SideEffectReport.validate(new SideEffectReport(List.of(valid("e-0"), valid("e-1"))), NOW))
        .doesNotThrowAnyException();
    // 只有必填项
    Effect minimal = new Effect("e-2", "crm", "2026-11-14T09:00:00+08:00", new ActionRef("x"), null, null, null, null);
    assertThatCode(() -> SideEffectReport.validate(new SideEffectReport(List.of(minimal)), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void emptyOrOversizedBatchIsWhole() {
    for (SideEffectReport r : List.of(new SideEffectReport(List.of()), new SideEffectReport(null),
        new SideEffectReport(Collections.nCopies(501, valid("e"))))) {
      SideEffectReport.InvalidSideEffectException e = invalid(r);
      assertThat(e.index()).isEqualTo(-1);
      assertThat(e.field()).isEqualTo("effects");
    }
    List<Effect> max = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      max.add(valid("e-" + i));
    }
    assertThatCode(() -> SideEffectReport.validate(new SideEffectReport(max), NOW)).doesNotThrowAnyException();
  }

  @Test
  void nullEntryRejected() {
    List<Effect> list = new ArrayList<>();
    list.add(valid("e-0"));
    list.add(null);
    SideEffectReport.InvalidSideEffectException e = invalid(new SideEffectReport(list));
    assertThat(e.index()).isEqualTo(1);
    assertThat(e.field()).isEqualTo("effect");
  }

  @Test
  void effectIdRules() {
    for (String bad : new String[] {null, "", "a\nb", "x".repeat(129)}) {
      SideEffectReport.InvalidSideEffectException e = secondBroken(v -> with(v, bad, v.source(), v.occurredAt(),
          v.action(), v.resource(), v.decisionId(), v.attemptId(), v.actionHash()));
      assertThat(e.index()).isEqualTo(1);
      assertThat(e.field()).isEqualTo("effectId");
    }
  }

  @Test
  void sourceMustBeLowercaseSlug() {
    for (String bad : new String[] {null, "Billing", "-x", "a b", "x".repeat(65)}) {
      SideEffectReport.InvalidSideEffectException e = secondBroken(v -> with(v, v.effectId(), bad, v.occurredAt(),
          v.action(), v.resource(), v.decisionId(), v.attemptId(), v.actionHash()));
      assertThat(e.field()).isEqualTo("source");
    }
    assertThatCode(() -> SideEffectReport.validate(new SideEffectReport(List.of(with(valid("e"), "e", "a.b_c-9",
        "2026-11-14T09:00:00Z", new ActionRef("x"), null, null, null, null))), NOW)).doesNotThrowAnyException();
  }

  @Test
  void occurredAtAtMostFiveMinutesAhead() {
    SideEffectReport.InvalidSideEffectException e = secondBroken(v -> with(v, v.effectId(), v.source(),
        NOW.plusSeconds(360).toString(), v.action(), v.resource(), v.decisionId(), v.attemptId(), v.actionHash()));
    assertThat(e.field()).isEqualTo("occurredAt");
    for (String bad : new String[] {null, "yesterday", "2026-11-14"}) {
      assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), bad, v.action(), v.resource(), v.decisionId(),
          v.attemptId(), v.actionHash())).field()).isEqualTo("occurredAt");
    }
    assertThatCode(() -> SideEffectReport.validate(new SideEffectReport(List.of(with(valid("e"), "e", "s",
        NOW.plusSeconds(300).toString(), new ActionRef("x"), null, null, null, null))), NOW)).doesNotThrowAnyException();
  }

  @Test
  void actionRequired() {
    assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), null, v.resource(),
        v.decisionId(), v.attemptId(), v.actionHash())).field()).isEqualTo("action");
    for (String bad : new String[] {null, "", "x".repeat(256)}) {
      assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), new ActionRef(bad),
          v.resource(), v.decisionId(), v.attemptId(), v.actionHash())).field()).isEqualTo("action.name");
    }
  }

  @Test
  void resourceFieldsBounded() {
    assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), v.action(),
        new ResourceRef("x".repeat(256), "1"), v.decisionId(), v.attemptId(), v.actionHash())).field())
        .isEqualTo("resource.type");
    assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), v.action(),
        new ResourceRef("t", "x".repeat(256)), v.decisionId(), v.attemptId(), v.actionHash())).field())
        .isEqualTo("resource.id");
  }

  @Test
  void decisionAndAttemptAreUuidShaped() {
    assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), v.action(), v.resource(),
        "123", v.attemptId(), v.actionHash())).field()).isEqualTo("decisionId");
    assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), v.action(), v.resource(),
        v.decisionId(), "attempt-1", v.actionHash())).field()).isEqualTo("attemptId");
  }

  @Test
  void actionHashIsLowercaseHex64() {
    for (String bad : new String[] {"AB".repeat(32), "ab".repeat(31), "zz".repeat(32)}) {
      assertThat(secondBroken(v -> with(v, v.effectId(), v.source(), v.occurredAt(), v.action(), v.resource(),
          v.decisionId(), v.attemptId(), bad)).field()).isEqualTo("actionHash");
    }
  }
}
