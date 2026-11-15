package io.aster.guard;

import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #522：{@link GuardDecisionStore#register} 的三条路径——正常注册时事件与决策行同一事务提交；锁内复查到同键先到者时
 * 什么都不写；事件追加失败时整体回滚、以「链不可用」重新构造的行单独写入，链上不留事件。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class GuardDecisionStoreRegisterTest {
  static final String TENANT = "t-register-" + UUID.randomUUID();
  @Inject GuardDecisionStore store;

  @BeforeEach void clean() {
    QuarkusTransaction.requiringNew().run(() -> GuardDecisionEntity.delete("tenantId", TENANT));
  }

  /** 记录每次构造行时收到的链记录（null 表示链不可用）。 */
  private final List<AppendedRecord> seen = new ArrayList<>();

  private Function<AppendedRecord, GuardDecisionRegistration.Rows> rows(GuardDecisionEntity decision) {
    return chain -> {
      seen.add(chain);
      decision.receiptAuditId = chain == null ? null : chain.auditId();
      decision.outcome = chain == null ? "DENY" : decision.outcome;
      GuardDecisionResponse response = new GuardDecisionResponse(null, List.of(), decision.id.toString(),
          decision.outcome, null, decision.outcome, null, null, null, null, null, null);
      return new GuardDecisionRegistration.Rows(decision, null, null, null, response);
    };
  }

  private static AuditEvent event(String tenant, UUID decisionId) {
    return new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), tenant, "m", "f", null, null, null,
        "u", true, null, null, Map.of("decisionId", decisionId.toString()), null, null, null, null);
  }

  private static long events(UUID decisionId) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.count("decisionId", decisionId.toString()));
  }

  @Test void 正常注册_事件与决策行一起提交且行带链记录() {
    GuardDecisionEntity d = GuardDecisionStoreEvidenceTest.minimal(TENANT, null);
    d.idempotencyKey = "idem-" + UUID.randomUUID();

    GuardDecisionRegistration.Result r = store.register(TENANT, GuardDecisionRegistration.Key.idempotency(d.idempotencyKey),
        event(TENANT, d.id), rows(d));

    assertThat(r).isInstanceOf(GuardDecisionRegistration.Registered.class);
    assertThat(seen).hasSize(1).doesNotContainNull();
    assertThat(events(d.id)).isEqualTo(1);
    assertThat(store.findDecision(TENANT, d.id)).map(e -> e.receiptAuditId).contains(seen.get(0).auditId());
  }

  @Test void 同键已有决策_返回先到者且不追加事件不写行() {
    GuardDecisionEntity winner = GuardDecisionStoreEvidenceTest.minimal(TENANT, "cid-" + UUID.randomUUID());
    store.saveDecision(winner, null, null);
    GuardDecisionEntity loser = GuardDecisionStoreEvidenceTest.minimal(TENANT, winner.evidenceCorrelationId);

    GuardDecisionRegistration.Result r = store.register(TENANT,
        GuardDecisionRegistration.Key.evidence(winner.evidenceCorrelationId), event(TENANT, loser.id), rows(loser));

    assertThat(r).isInstanceOfSatisfying(GuardDecisionRegistration.Existing.class,
        e -> assertThat(e.decision().id).isEqualTo(winner.id));
    assertThat(seen).isEmpty();
    assertThat(events(loser.id)).isZero();
    assertThat(store.findDecision(TENANT, loser.id)).isEmpty();
  }

  /** 事件追加失败（此处以超列宽的事件租户触发追加器拒绝）：链上无事件，行以 null 链记录重新构造后写入。 */
  @Test void 事件追加失败_整体回滚后以链不可用写行_链上无事件() {
    GuardDecisionEntity d = GuardDecisionStoreEvidenceTest.minimal(TENANT, null);
    d.idempotencyKey = "idem-" + UUID.randomUUID();

    GuardDecisionRegistration.Result r = store.register(TENANT, GuardDecisionRegistration.Key.idempotency(d.idempotencyKey),
        event("x".repeat(101), d.id), rows(d));

    assertThat(r).isInstanceOf(GuardDecisionRegistration.Registered.class);
    assertThat(seen).containsExactly((AppendedRecord) null);
    assertThat(events(d.id)).isZero();
    assertThat(store.findDecision(TENANT, d.id)).map(e -> e.outcome).contains("DENY");
  }
}
