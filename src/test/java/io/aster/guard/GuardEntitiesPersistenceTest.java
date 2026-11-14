package io.aster.guard;

import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.aster.guard.snapshot.GuardEvaluationClassificationEntity;
import io.aster.guard.snapshot.GuardInputRevealEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** guard_decisions / guard_approvals 迁移与实体的持久化验证（ADR 0040 §3.3 / §4.1）。 */
@QuarkusTest
class GuardEntitiesPersistenceTest {
  private final String tenant = "tenant-guard-" + UUID.randomUUID();
  private final String otherTenant = "tenant-guard-other-" + UUID.randomUUID();

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardInputRevealEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardDecisionInputEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardEvaluationClassificationEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardApprovalEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardDecisionEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardFactIssuerEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
    });
  }

  private GuardDecisionEntity decision(String tenantId, String idempotencyKey) {
    GuardDecisionEntity d = new GuardDecisionEntity();
    d.id = UUID.randomUUID();
    d.tenantId = tenantId;
    d.idempotencyKey = idempotencyKey;
    d.policyModule = "guard.test";
    d.policyFunction = "decide";
    d.principalId = "agent-1";
    d.actionName = "delete_customer_record";
    d.contextHash = "0".repeat(64);
    d.outcome = "REQUIRE_APPROVAL";
    d.requiredRole = "Data Protection Officer";
    d.controls = "[\"audit\"]";
    d.responseJson = "{\"outcome\":\"REQUIRE_APPROVAL\"}";
    return d;
  }

  private GuardApprovalEntity approval(GuardDecisionEntity d, Instant expiresAt) {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = d.tenantId;
    a.decisionId = d.id;
    a.outcome = d.outcome;
    a.requiredRole = d.requiredRole;
    a.principalId = d.principalId;
    a.expiresAt = expiresAt;
    return a;
  }

  @Test
  void findersReturnPersistedRows() {
    GuardDecisionEntity d = decision(tenant, "idem-1");
    GuardApprovalEntity a = approval(d, Instant.now().plus(1, ChronoUnit.HOURS));
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      GuardDecisionEntity found = GuardDecisionEntity.findByIdempotencyKey(tenant, "idem-1").orElseThrow();
      assertThat(found.id).isEqualTo(d.id);
      assertThat(found.resourceType).isEmpty();
      assertThat(found.responseJson).contains("REQUIRE_APPROVAL");
      assertThat(GuardDecisionEntity.findByIdAndTenant(d.id, tenant)).isPresent();
      assertThat(GuardDecisionEntity.findByIdAndTenant(d.id, otherTenant)).isEmpty();

      GuardApprovalEntity foundApproval = GuardApprovalEntity.findByDecision(d.id).orElseThrow();
      assertThat(foundApproval.id).isEqualTo(a.id);
      assertThat(foundApproval.status).isEqualTo(GuardApprovalStatus.PENDING);
      assertThat(GuardApprovalEntity.findByIdAndTenant(a.id, tenant)).isPresent();
      assertThat(GuardApprovalEntity.findByIdAndTenant(a.id, otherTenant)).isEmpty();

      List<GuardApprovalEntity> pending =
          GuardApprovalEntity.listByTenantAndStatus(tenant, GuardApprovalStatus.PENDING, Instant.now(), 0, 10);
      assertThat(pending).extracting(x -> x.id).containsExactly(a.id);
      assertThat(GuardApprovalEntity.listByTenantAndStatus(tenant, GuardApprovalStatus.APPROVED, Instant.now(), 0, 10)).isEmpty();
    });
  }

  @Test
  void duplicateIdempotencyKeyInSameTenantViolatesUniqueIndex() {
    QuarkusTransaction.requiringNew().run(() -> decision(tenant, "idem-dup").persistAndFlush());

    assertThatThrownBy(() ->
        QuarkusTransaction.requiringNew().run(() -> decision(tenant, "idem-dup").persistAndFlush()))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void sameIdempotencyKeyInOtherTenantIsAllowed() {
    QuarkusTransaction.requiringNew().run(() -> {
      decision(tenant, "idem-shared").persistAndFlush();
      decision(otherTenant, "idem-shared").persistAndFlush();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      assertThat(GuardDecisionEntity.findByIdempotencyKey(tenant, "idem-shared")).isPresent();
      assertThat(GuardDecisionEntity.findByIdempotencyKey(otherTenant, "idem-shared")).isPresent();
    });
  }

  @Test
  void nullIdempotencyKeysDoNotCollide() {
    QuarkusTransaction.requiringNew().run(() -> {
      decision(tenant, null).persistAndFlush();
      decision(tenant, null).persistAndFlush();
    });
  }

  @Test
  void isExpiredFollowsExpiresAtForPendingOnly() {
    Instant now = Instant.now();
    GuardDecisionEntity d = decision(tenant, null);
    GuardApprovalEntity past = approval(d, now.minus(1, ChronoUnit.MINUTES));
    GuardApprovalEntity future = approval(d, now.plus(1, ChronoUnit.MINUTES));

    assertThat(past.isExpired(now)).isTrue();
    assertThat(future.isExpired(now)).isFalse();

    past.status = GuardApprovalStatus.APPROVED;
    assertThat(past.isExpired(now)).isFalse();
  }

  /** V6.36.0（ADR 0040 §11 D4）：决策行来源列与审批行过期原因往返；旧行三列为 null。 */
  @Test
  void provenanceColumnsRoundTrip() {
    Instant factExpiresAt = Instant.parse("2026-11-07T01:10:00Z");
    GuardDecisionEntity d = decision(tenant, null);
    d.verifiedFields = "[\"context.amount\",\"principal.id\"]";
    d.factIssuers = "[\"idp.example.com#2026-11\"]";
    d.factExpiresAt = factExpiresAt;
    GuardDecisionEntity legacy = decision(tenant, null);
    GuardApprovalEntity a = approval(d, Instant.now().plus(1, ChronoUnit.HOURS));
    a.expiredReason = "fact_issuer_revoked";
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      legacy.persistAndFlush();
      a.persistAndFlush();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      GuardDecisionEntity found = GuardDecisionEntity.findById(d.id);
      assertThat(found.verifiedFields).isEqualTo("[\"context.amount\", \"principal.id\"]");
      assertThat(found.factIssuers).isEqualTo("[\"idp.example.com#2026-11\"]");
      assertThat(found.factExpiresAt).isEqualTo(factExpiresAt);
      // Task 14 按 jsonb 包含查询定位依赖某签发方的决策
      assertThat(GuardDecisionEntity.getEntityManager().createNativeQuery(
              "select count(*) from guard_decisions where tenant_id = ?1 and fact_issuers @> cast(?2 as jsonb)")
          .setParameter(1, tenant).setParameter(2, "[\"idp.example.com#2026-11\"]").getSingleResult())
          .isEqualTo(1L);

      GuardDecisionEntity old = GuardDecisionEntity.findById(legacy.id);
      assertThat(old.verifiedFields).isNull();
      assertThat(old.factIssuers).isNull();
      assertThat(old.factExpiresAt).isNull();

      assertThat(GuardApprovalEntity.findByDecision(d.id).orElseThrow().expiredReason).isEqualTo("fact_issuer_revoked");
    });
  }

  /** guard_fact_issuers：(tenant_id, issuer, kid) 唯一，跨租户同名可并存。 */
  @Test
  void factIssuerKeyIsUniquePerTenant() {
    QuarkusTransaction.requiringNew().run(() -> {
      issuer(tenant).persistAndFlush();
      issuer(otherTenant).persistAndFlush();
    });

    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> issuer(tenant).persistAndFlush()))
        .isInstanceOf(PersistenceException.class);

    QuarkusTransaction.requiringNew().run(() -> {
      GuardFactIssuerEntity found = GuardFactIssuerEntity.findById(
          new GuardFactIssuerEntity.Key(tenant, "idp.example.com", "2026-11"));
      assertThat(found.alg).isEqualTo("Ed25519");
      assertThat(found.publicKey).isEqualTo("A".repeat(43));
      assertThat(found.enabled).isTrue();
      assertThat(found.notAfter).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
      assertThat(found.createdBy).isEqualTo("u-admin");
    });
  }

  private static GuardFactIssuerEntity issuer(String tenantId) {
    GuardFactIssuerEntity e = new GuardFactIssuerEntity();
    e.tenantId = tenantId;
    e.issuer = "idp.example.com";
    e.kid = "2026-11";
    e.alg = "Ed25519";
    e.publicKey = "A".repeat(43);
    e.enabled = true;
    e.notAfter = Instant.parse("2027-01-01T00:00:00Z");
    e.createdAt = Instant.parse("2026-11-08T00:00:00Z");
    e.createdBy = "u-admin";
    return e;
  }

  // ---- ADR 0042 §12 D1 / D5 / D6：输入快照、评估分类行与解除记录（V6.37.0） ----

  @Test
  void decisionInputRoundTrip() {
    GuardDecisionEntity d = decision(tenant, null);
    GuardApprovalEntity a = approval(d, Instant.now().plus(1, ChronoUnit.HOURS));
    GuardDecisionInputEntity in = new GuardDecisionInputEntity();
    in.decisionId = d.id;
    in.tenantId = tenant;
    in.actionJson = "{\"principal\":{\"id\":\"agent-1\"},\"context\":{\"amount\":1.10,\"email\":\"a@b.cc\"}}";
    in.contextHash = "a".repeat(64);
    in.classification = "{\"declared\":[\"context.amount\"],\"marks\":[]}";
    in.marksSource = GuardDecisionInputEntity.MARKS_VERSION;
    in.inputBinding = GuardDecisionInputEntity.BINDING_DIRECT;
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
      in.persistAndFlush();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      GuardDecisionInputEntity found = GuardDecisionInputEntity.findByDecisionAndTenant(d.id, tenant).orElseThrow();
      assertThat(found.actionJson).contains("a@b.cc");
      assertThat(found.contextHash).isEqualTo("a".repeat(64));
      assertThat(found.classification).contains("context.amount");
      assertThat(found.marksSource).isEqualTo("version");
      assertThat(found.inputBinding).isEqualTo("direct");
      assertThat(GuardDecisionInputEntity.findByDecisionAndTenant(d.id, otherTenant)).isEmpty();
      GuardApprovalEntity foundApproval = GuardApprovalEntity.findByDecision(d.id).orElseThrow();
      assertThat(foundApproval.inputSnapshotHash).isNull();
      assertThat(foundApproval.inputSnapshotMatched).isNull();
      assertThat(foundApproval.inputRevealed).isNull();
      assertThat(foundApproval.inputStatus).isNull();
    });

    GuardDecisionInputEntity bad = new GuardDecisionInputEntity();
    bad.decisionId = UUID.randomUUID();
    bad.tenantId = tenant;
    bad.actionJson = "{}";
    bad.contextHash = "b".repeat(64);
    bad.marksSource = "catalog";
    bad.inputBinding = GuardDecisionInputEntity.BINDING_DIRECT;
    // 外键与 CHECK 约束：未知 decision 或未知取值都拒绝
    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(bad::persistAndFlush))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void approvalSnapshotColumnsRoundTrip() {
    GuardDecisionEntity d = decision(tenant, null);
    GuardApprovalEntity a = approval(d, Instant.now().plus(1, ChronoUnit.HOURS));
    a.inputSnapshotHash = "c".repeat(64);
    a.inputSnapshotMatched = true;
    a.inputRevealed = false;
    a.inputStatus = "available";
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
    });
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity found = GuardApprovalEntity.findByIdAndTenant(a.id, tenant).orElseThrow();
      assertThat(found.inputSnapshotHash).isEqualTo("c".repeat(64));
      assertThat(found.inputSnapshotMatched).isTrue();
      assertThat(found.inputRevealed).isFalse();
      assertThat(found.inputStatus).isEqualTo("available");
    });
  }

  @Test
  void evaluationClassificationRoundTrip() {
    String correlationId = UUID.randomUUID().toString();
    GuardEvaluationClassificationEntity row = new GuardEvaluationClassificationEntity();
    row.tenantId = tenant;
    row.correlationId = correlationId;
    row.inputHash = null;
    row.classification = null;
    QuarkusTransaction.requiringNew().run(row::persistAndFlush);

    QuarkusTransaction.requiringNew().run(() -> {
      GuardEvaluationClassificationEntity found =
          GuardEvaluationClassificationEntity.findByKey(tenant, correlationId).orElseThrow();
      assertThat(found.inputHash).isNull();
      assertThat(found.classification).isNull();
      assertThat(found.createdAt).isNotNull();
      assertThat(GuardEvaluationClassificationEntity.findByKey(otherTenant, correlationId)).isEmpty();
    });
  }

  @Test
  void revealPrimaryKeyRejectsDuplicate() {
    GuardDecisionEntity d = decision(tenant, null);
    GuardApprovalEntity a = approval(d, Instant.now().plus(1, ChronoUnit.HOURS));
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
      reveal(a, "u-dpo", 41L).persistAndFlush();
      reveal(a, "u-other", 42L).persistAndFlush();
    });

    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> reveal(a, "u-dpo", 43L).persistAndFlush()))
        .isInstanceOf(PersistenceException.class);
    QuarkusTransaction.requiringNew().run(() ->
        assertThat(GuardInputRevealEntity.count("approvalId", a.id)).isEqualTo(2));
  }

  private GuardInputRevealEntity reveal(GuardApprovalEntity a, String user, long auditId) {
    GuardInputRevealEntity r = new GuardInputRevealEntity();
    r.approvalId = a.id;
    r.revealedBy = user;
    r.tenantId = a.tenantId;
    r.revealedAt = Instant.now();
    r.auditId = auditId;
    return r;
  }
}
