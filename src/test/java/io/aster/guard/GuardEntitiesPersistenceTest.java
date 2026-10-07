package io.aster.guard;

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
      GuardApprovalEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
      GuardDecisionEntity.delete("tenantId in (?1, ?2)", tenant, otherTenant);
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
}
