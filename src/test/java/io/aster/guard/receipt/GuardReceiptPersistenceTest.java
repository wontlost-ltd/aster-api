package io.aster.guard.receipt;

import io.aster.guard.GuardDecisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6.35.0 的 guard_receipts 表、guard_decisions.action_hash 列与实体映射（ADR 0040 §12 D4）。 */
@QuarkusTest
class GuardReceiptPersistenceTest {
  private final String tenant = "tenant-receipt-" + UUID.randomUUID();

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity.delete("tenantId", tenant);
      io.aster.guard.snapshot.GuardDecisionInputEntity.delete("tenantId", tenant);
      GuardDecisionEntity.delete("tenantId", tenant);
    });
  }

  private GuardDecisionEntity decision() {
    GuardDecisionEntity d = new GuardDecisionEntity();
    d.id = UUID.randomUUID();
    d.tenantId = tenant;
    d.policyModule = "guard.test";
    d.policyFunction = "decide";
    d.principalId = "agent-1";
    d.actionName = "refund";
    d.contextHash = "0".repeat(64);
    d.actionHash = "a".repeat(64);
    d.outcome = "ALLOW";
    d.responseJson = "{\"outcome\":\"ALLOW\"}";
    return d;
  }

  private GuardReceiptEntity receipt(UUID decisionId, Instant issuedAt) {
    GuardReceiptEntity r = new GuardReceiptEntity();
    r.decisionId = decisionId;
    r.tenantId = tenant;
    r.actionHash = "a".repeat(64);
    r.kid = "test-a";
    r.issuedAt = issuedAt;
    r.expiresAt = issuedAt.plus(5, ChronoUnit.MINUTES);
    r.signature = "s".repeat(86);
    return r;
  }

  @Test
  void receiptRowRoundTrips() {
    Instant issuedAt = Instant.parse("2026-11-08T10:00:00Z");
    GuardDecisionEntity d = decision();
    GuardReceiptEntity r = receipt(d.id, issuedAt);
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      r.persistAndFlush();
    });

    UUID attemptId = UUID.randomUUID();
    Instant consumedAt = issuedAt.plusSeconds(30);
    Instant resultAt = issuedAt.plusSeconds(60);
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity row = GuardReceiptEntity.<GuardReceiptEntity>findByIdOptional(d.id).orElseThrow();
      row.consumedAt = consumedAt;
      row.executorId = "executor-1";
      row.attemptId = attemptId.toString();
      row.consumeAuditId = 41L;
      row.resultStatus = "executed";
      row.resultAt = resultAt;
      row.resultAuditId = 42L;
    });

    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity row = GuardReceiptEntity.<GuardReceiptEntity>findByIdOptional(d.id).orElseThrow();
      assertThat(row.tenantId).isEqualTo(tenant);
      assertThat(row.actionHash).isEqualTo("a".repeat(64));
      assertThat(row.kid).isEqualTo("test-a");
      assertThat(row.issuedAt).isEqualTo(issuedAt);
      assertThat(row.expiresAt).isEqualTo(issuedAt.plus(5, ChronoUnit.MINUTES));
      assertThat(row.signature).isEqualTo("s".repeat(86));
      assertThat(row.consumedAt).isEqualTo(consumedAt);
      assertThat(row.executorId).isEqualTo("executor-1");
      assertThat(row.attemptId).isEqualTo(attemptId.toString());
      assertThat(row.consumeAuditId).isEqualTo(41L);
      assertThat(row.resultStatus).isEqualTo("executed");
      assertThat(row.resultAt).isEqualTo(resultAt);
      assertThat(row.resultAuditId).isEqualTo(42L);
      assertThat(row.createdAt).isNotNull();
      assertThat(GuardDecisionEntity.findByIdAndTenant(d.id, tenant).orElseThrow().actionHash)
          .isEqualTo("a".repeat(64));
    });
  }

  @Test
  void receiptRowStartsUnconsumed() {
    GuardDecisionEntity d = decision();
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      receipt(d.id, Instant.parse("2026-11-08T10:00:00Z")).persistAndFlush();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity row = GuardReceiptEntity.<GuardReceiptEntity>findByIdOptional(d.id).orElseThrow();
      assertThat(row.consumedAt).isNull();
      assertThat(row.executorId).isNull();
      assertThat(row.attemptId).isNull();
      assertThat(row.consumeAuditId).isNull();
      assertThat(row.resultStatus).isNull();
      assertThat(row.resultAt).isNull();
      assertThat(row.resultAuditId).isNull();
    });
  }

  @Test
  void decisionActionHashIsNullableForLegacyRows() {
    GuardDecisionEntity d = decision();
    d.actionHash = null;
    QuarkusTransaction.requiringNew().run(() -> d.persistAndFlush());

    QuarkusTransaction.requiringNew().run(() ->
        assertThat(GuardDecisionEntity.findByIdAndTenant(d.id, tenant).orElseThrow().actionHash).isNull());
  }

  @Test
  void receiptRequiresExistingDecision() {
    GuardReceiptEntity orphan = receipt(UUID.randomUUID(), Instant.parse("2026-11-08T10:00:00Z"));

    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> orphan.persistAndFlush()))
        .hasStackTraceContaining("guard_receipts_decision_id_fkey");
  }

  @Test
  void resultStatusCheckRejectsUnknown() {
    GuardDecisionEntity d = decision();
    GuardReceiptEntity r = receipt(d.id, Instant.parse("2026-11-08T10:00:00Z"));
    r.resultStatus = "succeeded";

    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      r.persistAndFlush();
    })).hasStackTraceContaining("guard_receipts_result_status_ck");
  }

  @Test
  void consumedLookupIndexExists() {
    Object count = QuarkusTransaction.requiringNew().call(() -> GuardReceiptEntity.getEntityManager()
        .createNativeQuery("SELECT count(*) FROM pg_indexes WHERE tablename = 'guard_receipts'"
            + " AND indexname = 'guard_receipts_tenant_consumed_ix'"
            + " AND indexdef LIKE '%(tenant_id, consumed_at)%'")
        .getSingleResult());

    assertThat(((Number) count).longValue()).isEqualTo(1L);
  }
}
