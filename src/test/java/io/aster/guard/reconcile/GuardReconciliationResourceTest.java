package io.aster.guard.reconcile;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.aster.guard.reconcile.ReconcileFixture.chainEvents;
import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.orphans;
import static io.aster.guard.reconcile.ReconcileFixture.report;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 对账摘要 / 孤儿列表 / 确认端点（ADR 0054 §10.2）。测试配置下 rbac 关闭，ADMIN 限制由 {@code GuardReconciliationAuthBypassTest} 覆盖。 */
@QuarkusTest
class GuardReconciliationResourceTest {
  private static final String BASE = "/api/v1/guard/reconciliation";

  @Inject GuardReconciliationService service;
  @Inject GuardOrphanMetrics metrics;
  @Inject MeterRegistry registry;

  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;

  private static Response get(String tenant, String path) {
    return given().header("X-Tenant-Id", tenant).when().get(BASE + path);
  }

  private static Response ack(String tenant, Object id, Object body) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", "admin-reconcile").contentType(ContentType.JSON)
        .body(body).when().post(BASE + "/orphans/" + id + "/ack");
  }

  /** 上报 n 条无 decisionId 的副作用并对账一次，产出 n 条 OPEN 孤儿。 */
  private void orphansFor(String tenant, int n) {
    String[] effects = new String[n];
    for (int i = 0; i < n; i++) {
      effects[i] = effect("e-" + i, "crm", null, null, null);
    }
    report(tenant, effects);
    service.reconcileTenant(tenant, Instant.now().plus(grace).plusSeconds(60));
  }

  @Test
  void summaryBeforeAnyRunIsEmpty() {
    Response r = get(ReconcileFixture.tenant("res-empty"), "");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.asString()).isEqualTo(
        "{\"lastRun\":null,\"open\":{},\"openTotal\":0,\"resultGrace\":\"PT15M\",\"lookback\":\"P7D\"}");
  }

  @Test
  void summaryAfterRun() {
    String t = ReconcileFixture.tenant("res-summary");
    orphansFor(t, 1);

    Response r = get(t, "");

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getInt("lastRun.newOrphans")).isEqualTo(1);
    assertThat(r.jsonPath().getInt("lastRun.openOrphans")).isEqualTo(1);
    assertThat(r.jsonPath().getInt("lastRun.resolvedOrphans")).isZero();
    assertThat(r.jsonPath().getLong("lastRun.runId")).isPositive();
    assertThat(r.jsonPath().getString("lastRun.windowEnd")).isNotBlank();
    assertThat(r.jsonPath().getMap("open")).isEqualTo(Map.of("side_effect_without_decision", 1));
    assertThat(r.jsonPath().getInt("openTotal")).isEqualTo(1);
  }

  @Test
  void listFiltersAndPages() {
    String t = ReconcileFixture.tenant("res-list");
    orphansFor(t, 3);

    Response first = get(t, "/orphans?limit=2");
    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    List<Long> ids = first.jsonPath().getList("items.id", Long.class);
    assertThat(ids).hasSize(2).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    long next = first.jsonPath().getLong("next");
    assertThat(next).isEqualTo(ids.get(1));
    Map<String, Object> item = first.jsonPath().getMap("items[0]");
    assertThat(item).containsEntry("kind", "side_effect_without_decision").containsEntry("severity", "critical")
        .containsEntry("status", "OPEN").containsEntry("executor", "other").containsKeys("sideEffectId", "detectedAt")
        .doesNotContainKeys("decisionId", "resolvedAt", "resolution", "acknowledgedBy", "executorId");
    assertThat(first.jsonPath().getString("items[0].detail.reason")).isEqualTo("no_decision_id");
    assertThat(first.jsonPath().getString("items[0].detail.source")).isEqualTo("crm");

    Response second = get(t, "/orphans?limit=2&before=" + next);
    assertThat(second.jsonPath().getList("items.id", Long.class)).hasSize(1);
    assertThat(second.asString()).doesNotContain("\"next\"");

    assertThat(get(t, "/orphans?kind=side_effect_without_decision").jsonPath().getList("items")).hasSize(3);
    assertThat(get(t, "/orphans?kind=allow_unconsumed").jsonPath().getList("items")).isEmpty();
    assertThat(get(t, "/orphans?status=RESOLVED").jsonPath().getList("items")).isEmpty();
    for (String bad : List.of("?kind=bogus", "?status=open", "?limit=0", "?limit=201", "?limit=x", "?before=-1",
        "?before=0")) {
      Response r = get(t, "/orphans" + bad);
      assertThat(r.statusCode()).as(bad).isEqualTo(400);
      assertThat(r.asString()).as(bad).isEqualTo("{\"error\":\"invalid_query\"}");
    }
  }

  @Test
  void ackIsOnceAndChained() {
    String t = ReconcileFixture.tenant("res-ack");
    orphansFor(t, 1);
    long id = orphans(t).get(0).id;
    metrics.refresh();
    assertThat(registry.find("guard.orphan.actions").tag("tenant", t).gauges()).hasSize(1);

    Response r = ack(t, id, Map.of("comment", "已核实：人工补录"));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("status")).isEqualTo("ACKNOWLEDGED");
    assertThat(r.jsonPath().getString("resolution")).isEqualTo("acknowledged");
    assertThat(r.jsonPath().getString("acknowledgedBy")).isEqualTo("admin-reconcile");
    assertThat(r.jsonPath().getString("resolvedAt")).isNotBlank();
    var events = chainEvents(t, "GUARD_ORPHAN_ACKNOWLEDGED");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).performedBy).isEqualTo("admin-reconcile");
    assertThat(events.get(0).metadata).contains("\"orphanId\":" + id)
        .contains("\"kind\":\"side_effect_without_decision\"").contains("\"comment\":\"已核实：人工补录\"");
    GuardOrphanActionEntity row = orphans(t).get(0);
    assertThat(row.ackAuditId).isEqualTo(events.get(0).id);
    assertThat(row.ackComment).isEqualTo("已核实：人工补录");

    Response again = ack(t, id, Map.of("comment", "再来"));
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.asString()).isEqualTo("{\"error\":\"not_open\",\"status\":\"ACKNOWLEDGED\"}");
    assertThat(chainEvents(t, "GUARD_ORPHAN_ACKNOWLEDGED")).hasSize(1);

    metrics.refresh();
    assertThat(registry.find("guard.orphan.actions").tag("tenant", t).gauges()).isEmpty();
    assertThat(get(t, "").jsonPath().getInt("openTotal")).isZero();
  }

  @Test
  void ackValidatesComment() {
    String t = ReconcileFixture.tenant("res-ack-bad");
    orphansFor(t, 1);
    long id = orphans(t).get(0).id;
    for (Object body : List.of(Map.of(), Map.of("comment", ""), Map.of("comment", "   "),
        Map.of("comment", "x".repeat(1001)))) {
      Response r = ack(t, id, body);
      assertThat(r.statusCode()).as(String.valueOf(body)).isEqualTo(400);
      assertThat(r.asString()).isEqualTo("{\"error\":\"invalid_ack\",\"field\":\"comment\"}");
    }
    Response dup = ack(t, id, "{\"comment\":\"a\",\"comment\":\"b\"}");
    assertThat(dup.asString()).isEqualTo("{\"error\":\"invalid_ack\"}");
    assertThat(ack(t, id, Map.of("comment", "x".repeat(1000))).statusCode()).isEqualTo(200);
  }

  @Test
  void crossTenantIs404() {
    String a = ReconcileFixture.tenant("res-xt-a");
    String b = ReconcileFixture.tenant("res-xt-b");
    orphansFor(a, 1);
    long id = orphans(a).get(0).id;

    Response r = ack(b, id, Map.of("comment", "不是我的"));
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.asString()).isEmpty();
    assertThat(get(b, "/orphans").jsonPath().getList("items")).isEmpty();
    for (String bad : List.of("abc", "0", "-1", "99999999999999999999")) {
      Response n = ack(a, bad, Map.of("comment", "x"));
      assertThat(n.statusCode()).as(bad).isEqualTo(404);
      assertThat(n.asString()).isEmpty();
    }
    assertThat(ack(a, Long.MAX_VALUE / 2, Map.of("comment", "x")).statusCode()).isEqualTo(404);
    assertThat(orphans(a).get(0).status).isEqualTo("OPEN");
  }
}
