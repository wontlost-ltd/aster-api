package io.aster.guard;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证据相关键：按租户查回、跨租户隔离、同租户重复键转为竞争异常。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class GuardDecisionStoreEvidenceTest {
  static final String TENANT = "t-evidence-" + UUID.randomUUID();
  @Inject GuardDecisionStore store;

  @BeforeEach void clean() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
    });
  }

  @Test void 按证据相关键查回决策_跨租户不可见_重复键抛竞争异常() {
    GuardDecisionEntity e = minimal(TENANT, "cid-1");
    store.saveDecision(e, null);
    assertThat(store.findByEvidenceCorrelationId(TENANT, "cid-1")).map(d -> d.id).contains(e.id);
    assertThat(store.findByEvidenceCorrelationId("other-" + TENANT, "cid-1")).isEmpty();
    assertThatThrownBy(() -> store.saveDecision(minimal(TENANT, "cid-1"), null))
        .isInstanceOf(GuardIdempotencyRaceException.class);
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
