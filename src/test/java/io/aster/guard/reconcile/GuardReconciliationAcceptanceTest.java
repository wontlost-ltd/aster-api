package io.aster.guard.reconcile;

import io.aster.guard.reconcile.ReconcileFixture.Decision;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.aster.guard.reconcile.ReconcileFixture.chainEvents;
import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.report;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #465 验收：只经 REST 与对账入口（{@code reconcileAll}、指标刷新、{@code /q/metrics}）。
 *
 * <p>效力边界：本测试在 aster-api 内以 REST 扮演「执行方」与「副作用落地方」，证明的是「上报到达 → 对账 → 孤儿 / 指标 /
 * 链事件」这一段；「真实工具在被绕过时确实会上报」属落地方接入，由 #466 部署层（ADR 0054 §10.5 I4）断言，不在此声称。
 */
@QuarkusTest
class GuardReconciliationAcceptanceTest {
  private static final String MCP = "aster-guard-mcp@e2e";

  @Inject GuardReconciliationService service;
  @Inject GuardOrphanMetrics metrics;

  @ConfigProperty(name = "aster.guard.result-grace") Duration grace;

  private static String tenant(String scenario) {
    String t = ReconcileFixture.tenant(scenario);
    ReconcileFixture.deploy(t);
    return t;
  }

  private Instant future() {
    return Instant.now().plus(grace).plusSeconds(60);
  }

  /** 正常路径：决策 → consume → result → 带 decisionId / attemptId / actionHash 上报。 */
  private static void honest(String t, Decision d, String effectId) {
    String attempt = ReconcileFixture.consume(t, d, MCP);
    ReconcileFixture.result(t, d.id(), attempt, MCP);
    report(t, effect(effectId, "aster-guard-mcp", d.id(), attempt, d.actionHash()));
  }

  private static Response orphans(String t, String query) {
    return given().header("X-Tenant-Id", t).when().get("/api/v1/guard/reconciliation/orphans" + query);
  }

  private static String scrape() {
    return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
  }

  @Test
  void injectedBypassProducesOrphanAndMetric() {
    String t = tenant("accept-bypass");
    honest(t, ReconcileFixture.allow(t, 1), "honest-1");
    // 注入绕过：工具在未经 Guard 的情况下落地副作用，照常上报但没有 decisionId
    report(t, effect("bypass-1", "aster-guard-mcp", null, null, null));

    service.reconcileAll(future());

    Response r = orphans(t, "");
    assertThat(r.jsonPath().getList("items.kind")).containsExactly("side_effect_without_decision");
    assertThat(r.jsonPath().getString("items[0].detail.effectId")).isEqualTo("bypass-1");
    metrics.refresh();
    assertThat(scrape()).contains("guard_orphan_actions{executor=\"aster-guard-mcp\","
        + "kind=\"side_effect_without_decision\",tenant=\"" + t + "\"} 1.0");
    assertThat(chainEvents(t, "GUARD_RECONCILIATION")).hasSize(1);
  }

  @Test
  void normalPathReconcilesToZero() {
    String u = tenant("accept-normal");
    honest(u, ReconcileFixture.allow(u, 1), "e-1");
    Decision pending = ReconcileFixture.pending(u);
    ReconcileFixture.approve(u, pending);
    honest(u, pending, "e-2");

    service.reconcileAll(future());
    metrics.refresh();

    Response summary = given().header("X-Tenant-Id", u).when().get("/api/v1/guard/reconciliation");
    assertThat(summary.jsonPath().getInt("openTotal")).isZero();
    assertThat(summary.jsonPath().getInt("lastRun.newOrphans")).isZero();
    // 其它指标（如策略缓存）也带 tenant 标签，只看 guard_orphan_actions 序列
    assertThat(scrape().lines().filter(l -> l.startsWith("guard_orphan_actions{")))
        .noneMatch(l -> l.contains("tenant=\"" + u + "\""));
    assertThat(chainEvents(u, "GUARD_RECONCILIATION")).isEmpty();
  }

  @Test
  void adr0054ThreeAnomalies() {
    String v = tenant("accept-adr0054");
    // 1. 消耗未回写，越过宽限 → 执行侧丢失
    Decision lost = ReconcileFixture.allow(v, 1);
    ReconcileFixture.consume(v, lost, MCP);
    // 2. DENY 决策被直接改库为已消耗 → aster-api 自身被绕过
    Decision deny = ReconcileFixture.deny(v);
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity r = new GuardReceiptEntity();
      r.decisionId = UUID.fromString(deny.id());
      r.tenantId = v;
      r.actionHash = deny.actionHash();
      r.kid = "test-a";
      r.issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      r.expiresAt = r.issuedAt.plusSeconds(300);
      r.signature = "tampered";
      r.consumedAt = r.issuedAt.plusSeconds(1);
      r.executorId = MCP;
      r.consumeAuditId = 1L;
      r.resultStatus = "executed";
      r.resultAt = r.consumedAt;
      r.persist();
    });
    // 3. 上报指向一个从未放行的决策
    Decision deny2 = ReconcileFixture.deny(v);
    report(v, effect("e-deny", "crm", deny2.id(), null, null));

    service.reconcileAll(future());

    Map<String, String> severityByKind = new java.util.TreeMap<>();
    List<Map<String, Object>> items = orphans(v, "").jsonPath().getList("items");
    items.forEach(i -> severityByKind.put((String) i.get("kind"), (String) i.get("severity")));
    assertThat(severityByKind).containsExactlyInAnyOrderEntriesOf(Map.of(
        "consumed_without_result", "warning",
        "consume_on_non_allow", "critical",
        "side_effect_unconsumed", "critical"));
  }
}
