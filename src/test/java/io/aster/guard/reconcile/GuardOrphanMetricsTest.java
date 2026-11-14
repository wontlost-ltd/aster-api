package io.aster.guard.reconcile;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.report;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code guard_orphan_actions} MultiGauge 从库重读（ADR 0054 §10.4）。共享测试库里有其它用例的 OPEN 行，断言只按本用例租户过滤。
 */
@QuarkusTest
class GuardOrphanMetricsTest {
  @Inject GuardOrphanMetrics metrics;
  @Inject GuardReconciliationService service;
  @Inject MeterRegistry registry;

  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;

  private Gauge series(String tenant, String executor, String kind) {
    return registry.find("guard.orphan.actions").tags("tenant", tenant, "executor", executor, "kind", kind).gauge();
  }

  private static void insert(String tenant, OrphanKind kind, String status) {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardOrphanActionEntity o = GuardReconcilePersistenceTest.orphan(tenant, kind, UUID.randomUUID().toString(), null);
      o.status = status;
      o.persist();
    });
  }

  @Test
  void gaugeReflectsOpenRowsOnly() {
    String t = ReconcileFixture.tenant("metrics-open");
    insert(t, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "OPEN");
    insert(t, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "OPEN");
    insert(t, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "RESOLVED");

    metrics.refresh();
    assertThat(series(t, "other", "side_effect_without_decision").value()).isEqualTo(2.0);

    QuarkusTransaction.requiringNew().run(() -> GuardOrphanActionEntity.update(
        "status = 'ACKNOWLEDGED' where tenantId = ?1 and status = 'OPEN'", t));
    metrics.refresh();
    assertThat(series(t, "other", "side_effect_without_decision")).isNull();
  }

  @Test
  void labelsStayBoundedFromDatabase() {
    String t = ReconcileFixture.tenant("metrics-labels");
    ReconcileFixture.deploy(t);
    ReconcileFixture.Decision d = ReconcileFixture.allow(t, 1);
    ReconcileFixture.consume(t, d, "my-bot@pod-7f9c:3");
    report(t, effect("e-1", "tenant-crm", null, null, null));
    service.reconcileTenant(t, Instant.now().plus(grace).plusSeconds(60));

    metrics.refresh();

    assertThat(registry.find("guard.orphan.actions").tag("tenant", t).gauges())
        .isNotEmpty()
        .allSatisfy(g -> assertThat(g.getId().getTag("executor")).isEqualTo("other"));
  }

  @Test
  void prometheusNames() {
    String t = ReconcileFixture.tenant("metrics-prom");
    insert(t, OrphanKind.CONSUMED_WITHOUT_RESULT, "OPEN");
    metrics.recordDetected(OrphanKind.CONSUMED_WITHOUT_RESULT);
    metrics.recordRun(GuardReconciliationService.TenantOutcome.OK);
    metrics.markLoopSucceeded(Instant.now());
    metrics.refresh();

    String body = given().when().get("/q/metrics").then().statusCode(200).extract().asString();

    assertThat(body).contains("guard_orphan_actions{", "guard_orphan_actions_detected_total{",
        "guard_reconciliation_runs_total{", "guard_reconciliation_last_success_seconds");
    assertThat(body).contains("guard_orphan_actions{executor=\"other\",kind=\"consumed_without_result\",tenant=\"" + t
        + "\"} 1.0");
  }
}
