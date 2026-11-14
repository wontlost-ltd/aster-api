package io.aster.guard.snapshot;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardActionMapper;
import io.aster.guard.GuardApprovalConflictException;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.api.GuardActionRequest;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.PiiMark;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0042 §12 D5 / D7 / D8：审批输入端点与按人入链的原值解除（真实库、真实审计链）。
 * 开启全局 PII 响应过滤器（{@code aster.pii.enforce=true}），钉住解除视图的原值不被改写。
 * 测试配置下 apikey 过滤器关闭，身份取 {@code X-User-Id}，业务角色取 {@code X-User-Business-Roles}。
 */
@QuarkusTest
@TestProfile(GuardApprovalInputResourceTest.PiiEnforceProfile.class)
class GuardApprovalInputResourceTest {
  /** 只打开 PII 响应过滤器，其余沿用测试默认配置。 */
  public static class PiiEnforceProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("aster.pii.enforce", "true");
    }
  }

  private static final String TENANT = "tenant-input-view-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String OTHER = "tenant-input-other-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String DPO = "Data Protection Officer";
  private static final String APPROVALS = "/api/v1/guard/approvals/";
  private static final InputClassification CLS = new InputClassification(
      List.of("context.amount", "context.email", "context.note"),
      List.of(new PiiMark("context.email", "L2", "email", "ir")));

  @Inject InputSnapshotService service;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      for (String t : List.of(TENANT, OTHER)) {
        GuardInputRevealEntity.delete("tenantId = ?1", t);
        GuardDecisionInputEntity.delete("tenantId = ?1", t);
        GuardApprovalEntity.delete("tenantId = ?1", t);
        GuardDecisionEntity.delete("tenantId = ?1", t);
      }
    });
  }

  /** 种入一条决策 + 审批（+ 可选输入行），返回审批行。 */
  private static GuardApprovalEntity seed(String requiredRole, boolean withInput, Map<String, Object> context) {
    GuardActionRequest.Action action = new GuardActionRequest.Action(
        new GuardActionRequest.Principal("agent-42", "agent", List.of()), new GuardActionRequest.ActionName("approve"),
        new GuardActionRequest.Resource("loan", "L-1"), context, null);
    String contextHash = new GuardActionMapper().contextHash(context);
    GuardDecisionEntity d = new GuardDecisionEntity();
    d.id = UUID.randomUUID();
    d.tenantId = TENANT;
    d.policyModule = "m";
    d.policyFunction = "f";
    d.principalId = "agent-42";
    d.actionName = "approve";
    d.contextHash = contextHash;
    d.outcome = requiredRole == null ? "ESCALATE" : "REQUIRE_APPROVAL";
    d.requiredRole = requiredRole;
    d.responseJson = "{}";
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = TENANT;
    a.decisionId = d.id;
    a.outcome = d.outcome;
    a.requiredRole = requiredRole;
    a.principalId = "agent-42";
    a.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    QuarkusTransaction.requiringNew().run(() -> {
      d.persistAndFlush();
      a.persistAndFlush();
      if (withInput) {
        DecisionInputFactory.direct(d.id, TENANT, action, contextHash, CLS).persistAndFlush();
      }
    });
    return a;
  }

  private static Map<String, Object> context() {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("amount", 250000);
    ctx.put("email", "applicant@example.com");
    ctx.put("extra", Map.of("k@x.io", 1));
    return ctx;
  }

  private static Response get(String tenant, String id, String user) {
    return given().header("X-Tenant-Id", tenant).header("X-User-Id", user).when().get(APPROVALS + id + "/input");
  }

  private static Response reveal(String tenant, String id, String user, String roles) {
    var spec = given().header("X-Tenant-Id", tenant).header("X-User-Id", user).contentType(ContentType.JSON);
    if (roles != null) {
      spec = spec.header("X-User-Business-Roles", roles);
    }
    return spec.when().post(APPROVALS + id + "/input/reveal");
  }

  /** 每条审批对应唯一决策：按 decision_id 投影列计数解除事件。 */
  private static long revealEvents(UUID approvalId) {
    UUID decisionId = QuarkusTransaction.requiringNew().call(() ->
        GuardApprovalEntity.<GuardApprovalEntity>findById(approvalId).decisionId);
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.count(
        "tenantId = ?1 and eventType = 'ACTION_GUARD_INPUT_REVEAL' and decisionId = ?2", TENANT, decisionId.toString()));
  }

  private static long revealRows(UUID approvalId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardInputRevealEntity.count("approvalId", approvalId));
  }

  // ---- 状态 ----

  @Test
  void maskedViewByDefault() {
    GuardApprovalEntity a = seed(DPO, true, context());
    Response r = get(TENANT, a.id.toString(), "u-member");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("status")).isEqualTo("available");
    assertThat(r.jsonPath().getMap("input.context.email")).isEqualTo(Map.of("$pii", Map.of("level", "L2",
        "category", "email")));
    assertThat(r.jsonPath().getInt("input.context.amount")).isEqualTo(250000);
    assertThat(r.jsonPath().getMap("input.context.extra")).isEqualTo(Map.of("$pii", Map.of("level", "unknown")));
    assertThat(r.jsonPath().getString("inputSnapshotHash")).hasSize(64);
    assertThat(r.jsonPath().getString("marksSource")).isEqualTo("version");
    assertThat(r.jsonPath().getString("inputBinding")).isEqualTo("direct");
    assertThat(r.jsonPath().getBoolean("revealed")).isFalse();
    assertThat(r.jsonPath().getBoolean("revealedByMe")).isFalse();
    assertThat(r.asString()).doesNotContain("applicant@example.com").doesNotContain("k@x.io");
  }

  @Test
  void statusUnavailableForLegacyDecision() {
    GuardApprovalEntity a = seed(DPO, false, context());
    Response r = get(TENANT, a.id.toString(), "u-member");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getString("status")).isEqualTo("unavailable");
    assertThat(r.jsonPath().getString("input")).isNull();
    assertThat(r.jsonPath().getString("inputSnapshotHash")).isNull();
  }

  @Test
  void statusPurgedAfterTerminalDelete() {
    GuardApprovalEntity a = seed(DPO, false, context());
    QuarkusTransaction.requiringNew().run(() -> GuardApprovalEntity.update(
        "status = ?1, decidedAt = ?2 where id = ?3", GuardApprovalStatus.APPROVED,
        Instant.now().minus(91, ChronoUnit.DAYS), a.id));
    assertThat(get(TENANT, a.id.toString(), "u").jsonPath().getString("status")).isEqualTo("purged");
  }

  @Test
  void tamperedActionJsonIsIntegrityFailed() {
    GuardApprovalEntity a = seed(DPO, true, context());
    QuarkusTransaction.requiringNew().run(() -> {
      GuardDecisionInputEntity in = GuardDecisionInputEntity.findByDecisionAndTenant(a.decisionId, TENANT).orElseThrow();
      in.actionJson = in.actionJson.replace("250000", "1");
    });
    Response r = get(TENANT, a.id.toString(), "u");
    assertThat(r.jsonPath().getString("status")).isEqualTo("integrity_failed");
    assertThat(r.jsonPath().getString("input")).isNull();
  }

  @Test
  void oversizeViewIsTooLarge() {
    Map<String, Object> ctx = context();
    ctx.put("note", "x".repeat((1 << 20) + 16));
    GuardApprovalEntity a = seed(DPO, true, ctx);
    assertThat(get(TENANT, a.id.toString(), "u").jsonPath().getString("status")).isEqualTo("too_large");
  }

  // ---- 解除 ----

  @Test
  void roleHolderRevealAppendsOneEventFirstTimeOnly() {
    GuardApprovalEntity a = seed(DPO, true, context());
    Response first = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    assertThat(first.jsonPath().getString("input.context.email")).isEqualTo("applicant@example.com");
    assertThat(first.jsonPath().getMap("input.context.extra")).isEqualTo(Map.of("$pii", Map.of("level", "unknown")));
    assertThat(first.jsonPath().getBoolean("revealed")).isTrue();
    Response second = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.jsonPath().getString("inputSnapshotHash")).isEqualTo(first.jsonPath().getString("inputSnapshotHash"));
    assertThat(revealEvents(a.id)).isEqualTo(1);
    assertThat(revealRows(a.id)).isEqualTo(1);

    AuditLog event = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>find(
        "tenantId = ?1 and eventType = 'ACTION_GUARD_INPUT_REVEAL' and decisionId = ?2", TENANT,
        a.decisionId.toString()).firstResult());
    assertThat(event.performedBy).isEqualTo("u-dpo");
    assertThat(event.decisionId).isEqualTo(a.decisionId.toString());
    assertThat(event.metadata).contains("\"revealedFields\":1").contains(first.jsonPath().getString("inputSnapshotHash"))
        .doesNotContain("applicant@example.com");
    // GET 仍是打码视图，但标出本人已解除
    Response masked = get(TENANT, a.id.toString(), "u-dpo");
    assertThat(masked.jsonPath().getBoolean("revealed")).isFalse();
    assertThat(masked.jsonPath().getBoolean("revealedByMe")).isTrue();
    assertThat(get(TENANT, a.id.toString(), "u-other").jsonPath().getBoolean("revealedByMe")).isFalse();
  }

  @Test
  void concurrentFirstRevealsAppendExactlyOneEvent() throws Exception {
    GuardApprovalEntity a = seed(DPO, true, context());
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Object>> calls = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return service.reveal(TENANT, a.id, "u-dpo", List.of(DPO));
      }));
    }
    start.countDown();
    for (CompletableFuture<Object> c : calls) {
      c.get();
    }
    assertThat(revealEvents(a.id)).isEqualTo(1);
    GuardInputRevealEntity row = QuarkusTransaction.requiringNew().call(() ->
        GuardInputRevealEntity.<GuardInputRevealEntity>findById(new GuardInputRevealEntity.Key(a.id, "u-dpo")));
    AuditLog event = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>findById(row.auditId));
    assertThat(event.eventType).isEqualTo("ACTION_GUARD_INPUT_REVEAL");
  }

  @Test
  void noRoleIs403WithVerifiedRoles() {
    GuardApprovalEntity a = seed(DPO, true, context());
    Response r = reveal(TENANT, a.id.toString(), "u-member", "Credit Officer");
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("reveal_not_permitted");
    assertThat(r.jsonPath().getList("verifiedRoles")).containsExactly("Credit Officer");
    assertThat(r.asString()).doesNotContain("applicant@example.com");
    assertThat(revealEvents(a.id)).isZero();
  }

  @Test
  void escalateIsNotRevealable() {
    GuardApprovalEntity a = seed(null, true, context());
    Response r = reveal(TENANT, a.id.toString(), "u-admin", DPO);
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("reveal_not_permitted");
  }

  @Test
  void terminalApprovalIs409() {
    GuardApprovalEntity a = seed(DPO, true, context());
    QuarkusTransaction.requiringNew().run(() -> GuardApprovalEntity.update("status = ?1 where id = ?2",
        GuardApprovalStatus.REJECTED, a.id));
    Response r = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("approval_not_pending");
  }

  @Test
  void unavailableInputIs409() {
    GuardApprovalEntity a = seed(DPO, false, context());
    Response r = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("input_not_available");
  }

  @Test
  void appendFailureReturns503WithoutRawValues() {
    GuardApprovalEntity a = seed(DPO, true, context());
    var previous = service.replaceRevealAppenderForTest(e -> {
      throw new AuditChainAppender.AuditAppendException("down", new IllegalStateException("db"));
    });
    try {
      Response r = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
      assertThat(r.statusCode()).isEqualTo(503);
      assertThat(r.jsonPath().getString("error")).isEqualTo("receipt_unavailable");
      assertThat(r.asString()).doesNotContain("applicant@example.com");
      assertThatThrownBy(() -> service.reveal(TENANT, a.id, "u-dpo", List.of(DPO)))
          .isInstanceOf(GuardApprovalConflictException.class);
    } finally {
      service.replaceRevealAppenderForTest(previous);
    }
    assertThat(revealRows(a.id)).isZero();
    assertThat(revealEvents(a.id)).isZero();
  }

  // ---- HTTP 口径 ----

  @Test
  void crossTenantIs404SameBodyAsApprove() {
    GuardApprovalEntity a = seed(DPO, true, context());
    Response view = get(OTHER, a.id.toString(), "u");
    Response rev = reveal(OTHER, a.id.toString(), "u-dpo", DPO);
    Response approve = given().header("X-Tenant-Id", OTHER).header("X-User-Id", "u-dpo")
        .header("X-User-Business-Roles", DPO).contentType(ContentType.JSON).body(Map.of())
        .when().post(APPROVALS + a.id + "/approve");
    assertThat(view.statusCode()).isEqualTo(404);
    assertThat(rev.statusCode()).isEqualTo(404);
    assertThat(approve.statusCode()).isEqualTo(404);
    assertThat(view.jsonPath().getString("error")).isEqualTo("approval_not_found");
    assertThat(view.jsonPath().getMap("$").keySet()).isEqualTo(approve.jsonPath().getMap("$").keySet());
  }

  @Test
  void invalidUuidIs404() {
    Response r = get(TENANT, "not-a-uuid", "u");
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.jsonPath().getString("error")).isEqualTo("approval_not_found");
    assertThat(reveal(TENANT, "not-a-uuid", "u", DPO).statusCode()).isEqualTo(404);
  }

  @Test
  void getHasNoSideEffects() {
    GuardApprovalEntity a = seed(DPO, true, context());
    long before = QuarkusTransaction.requiringNew().call(() -> AuditLog.count("tenantId", TENANT));
    get(TENANT, a.id.toString(), "u-dpo");
    get(TENANT, a.id.toString(), "u-dpo");
    assertThat(QuarkusTransaction.requiringNew().call(() -> AuditLog.count("tenantId", TENANT))).isEqualTo(before);
    assertThat(revealRows(a.id)).isZero();
  }

  @Test
  void revealedValuesSurvivePiiResponseFilter() {
    GuardApprovalEntity a = seed(DPO, true, context());
    Response r = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.asString()).contains("applicant@example.com");
  }

  @Test
  void oversizeRevealedViewIsNotReturned() {
    Map<String, Object> ctx = context();
    ctx.put("email", "e".repeat((1 << 20) + 16) + "@example.com");
    GuardApprovalEntity a = seed(DPO, true, ctx);
    assertThat(get(TENANT, a.id.toString(), "u").jsonPath().getString("status")).isEqualTo("available");
    Response r = reveal(TENANT, a.id.toString(), "u-dpo", DPO);
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("input_not_available");
    assertThat(r.jsonPath().getString("status")).isEqualTo("too_large");
    assertThat(r.asString()).doesNotContain("eeeeeeeeee");
    assertThat(revealEvents(a.id)).isZero();
  }
}
