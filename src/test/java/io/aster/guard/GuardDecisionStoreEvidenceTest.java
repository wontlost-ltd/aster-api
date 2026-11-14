package io.aster.guard;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证据相关键：按租户查回、跨租户隔离、同租户重复键转为竞争异常；批量取决策同样按租户隔离。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class GuardDecisionStoreEvidenceTest {
  static final String TENANT = "t-evidence-" + UUID.randomUUID();
  static final String OTHER = "other-" + TENANT;
  @Inject GuardDecisionStore store;

  @BeforeEach void clean() {
    QuarkusTransaction.requiringNew().run(() -> {
      io.aster.guard.snapshot.GuardDecisionInputEntity.delete("tenantId in ?1", List.of(TENANT, OTHER));
      GuardApprovalEntity.delete("tenantId in ?1", List.of(TENANT, OTHER));
      GuardDecisionEntity.delete("tenantId in ?1", List.of(TENANT, OTHER));
    });
  }

  @Test void 按证据相关键查回决策_跨租户不可见_重复键抛竞争异常() {
    GuardDecisionEntity e = minimal(TENANT, "cid-1");
    store.saveDecision(e, null, null);
    assertThat(store.findByEvidenceCorrelationId(TENANT, "cid-1")).map(d -> d.id).contains(e.id);
    assertThat(store.findByEvidenceCorrelationId("other-" + TENANT, "cid-1")).isEmpty();
    assertThatThrownBy(() -> store.saveDecision(minimal(TENANT, "cid-1"), null, null))
        .isInstanceOf(GuardIdempotencyRaceException.class);
  }

  @Test void 批量取决策只返回本租户命中行_空输入返回空() {
    GuardDecisionEntity a = minimal(TENANT, null);
    GuardDecisionEntity b = minimal(TENANT, null);
    GuardDecisionEntity foreign = minimal(OTHER, null);
    store.saveDecision(a, null, null);
    store.saveDecision(b, null, null);
    store.saveDecision(foreign, null, null);

    List<GuardDecisionEntity> found = store.findDecisions(TENANT, Set.of(a.id, b.id, foreign.id, UUID.randomUUID()));

    assertThat(found).extracting(d -> d.id).containsExactlyInAnyOrder(a.id, b.id);
    assertThat(store.findDecisions(TENANT, Set.of())).isEmpty();
  }

  /** ADR 0042 §12 D1：输入行与决策行、审批行同一事务——审批行插入失败时输入行同样不存在。 */
  @Test void inputRowRollsBackWithDecision() {
    GuardDecisionEntity ok = minimal(TENANT, null);
    store.saveDecision(ok, approval(ok, "p"), null, input(ok));
    assertThat(QuarkusTransaction.requiringNew().call(() ->
        io.aster.guard.snapshot.GuardDecisionInputEntity.findByDecisionAndTenant(ok.id, TENANT))).isPresent();

    GuardDecisionEntity bad = minimal(TENANT, null);
    // principal_id NOT NULL：审批行插入失败，整个事务回滚
    assertThatThrownBy(() -> store.saveDecision(bad, approval(bad, null), null, input(bad)))
        .isInstanceOf(RuntimeException.class);
    QuarkusTransaction.requiringNew().run(() -> {
      assertThat(io.aster.guard.snapshot.GuardDecisionInputEntity.findByDecisionAndTenant(bad.id, TENANT)).isEmpty();
      assertThat(GuardDecisionEntity.findByIdAndTenant(bad.id, TENANT)).isEmpty();
    });
  }

  private static GuardApprovalEntity approval(GuardDecisionEntity d, String principalId) {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID(); a.tenantId = d.tenantId; a.decisionId = d.id; a.outcome = d.outcome;
    a.principalId = principalId; a.expiresAt = java.time.Instant.now().plusSeconds(3600);
    return a;
  }

  private static io.aster.guard.snapshot.GuardDecisionInputEntity input(GuardDecisionEntity d) {
    io.aster.guard.snapshot.GuardDecisionInputEntity in = new io.aster.guard.snapshot.GuardDecisionInputEntity();
    in.decisionId = d.id; in.tenantId = d.tenantId; in.actionJson = "{\"context\":{}}"; in.contextHash = d.contextHash;
    in.marksSource = "none"; in.inputBinding = "direct";
    return in;
  }

  /** 最小合法决策行：response_json 等 NOT NULL 列给占位值。 */
  static GuardDecisionEntity minimal(String tenant, String cid) {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = UUID.randomUUID(); e.tenantId = tenant; e.policyModule = "m"; e.policyFunction = "f";
    e.principalId = "p"; e.actionName = "a"; e.contextHash = "0".repeat(64); e.outcome = "REQUIRE_APPROVAL";
    e.responseJson = "{}"; e.evidenceCorrelationId = cid;
    return e;
  }
}
