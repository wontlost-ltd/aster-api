package io.aster.guard.api;

import io.aster.audit.chain.AuditChainAnchorService;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalService;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.GuardDecisionStore;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §4：待审批列表、批准 / 拒绝（四眼、角色、comment）、过期与 {@code waitSeconds} 端到端。
 * 测试配置下 apikey / rbac 过滤器关闭，审批人身份只靠 {@code X-User-Id}；业务角色由
 * {@code RequestIdentityResolver.businessRoles()} 回落读取 {@code X-User-Business-Roles}（逗号分隔）。
 */
@QuarkusTest
class GuardApprovalResourceTest {
  /** 每次 JVM 用独立租户：审计链与锚点只追加不删除，避免与历史运行互相干扰。 */
  private static final String TENANT = "tenant-guard-approval-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String DPO = "Data Protection Officer";
  private static final String APPROVALS = "/api/v1/guard/approvals";
  /** 探针策略换上规则 ID 与控制项注解：列表项的 ruleId / controls 取自真实决策行。 */
  private static final String SOURCE = DeployedMapParamProbeTest.SOURCE
      .replace("@id(\"PROBE-001\")", "@id(\"CUST-DEL-001\")\n@control(\"GDPR:ART17\")");

  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;
  @Inject AuditChainAnchorService anchorService;
  @Inject GuardApprovalService approvalService;
  @Inject GuardDecisionStore store;

  @BeforeEach
  void setUp() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
      PolicyVersion.delete("tenantId = ?1", TENANT);
      PolicyCatalog.delete("tenantId = ?1", TENANT);
    });
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    DeployedMapParamProbeTest.deploy(TENANT, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
        SOURCE);
  }

  /** 探针策略对 delete_customer_record 返回 REQUIRE_APPROVAL(Data Protection Officer)。 */
  private static Map<String, Object> pendingBody(Integer waitSeconds) {
    Map<String, Object> action = Map.of(
        "principal", Map.of("id", "agent-42", "type", "agent", "roles", List.of("ops-bot")),
        "action", Map.of("name", "delete_customer_record"),
        "resource", Map.of("type", "customer", "id", "C-1001"),
        "context", Map.of("amount", 250000),
        "agent", Map.of("model", "claude-fable-5-1", "session", "s-1"));
    Map<String, Object> body = new HashMap<>(Map.of(
        "policyModule", DeployedMapParamProbeTest.MODULE,
        "policyFunction", DeployedMapParamProbeTest.FUNCTION,
        "action", action));
    if (waitSeconds != null) {
      body.put("waitSeconds", waitSeconds);
    }
    return body;
  }

  private static Response postDecision(Integer waitSeconds) {
    return postDecision(waitSeconds, null);
  }

  private static Response postDecision(Integer waitSeconds, String idempotencyKey) {
    var spec = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-approval")
        .contentType(ContentType.JSON)
        .body(pendingBody(waitSeconds));
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.when().post("/api/v1/guard/decisions");
  }

  /** 产生一条 PENDING 决策，返回 {decisionId, approvalId}。 */
  private static String[] createPending() {
    Response r = postDecision(null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    return new String[]{r.jsonPath().getString("decisionId"), r.jsonPath().getString("approval.id")};
  }

  private static Response getDecision(String decisionId) {
    return given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-approval")
        .when().get("/api/v1/guard/decisions/" + decisionId);
  }

  private static Response list(String status) {
    var spec = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "dpo-1");
    if (status != null) {
      spec = spec.queryParam("status", status);
    }
    return spec.when().get(APPROVALS);
  }

  /** 以 userId / 业务角色集合（逗号分隔，null 表示不带头）对审批作出结论；comment 为 null 时请求体为空对象。 */
  private static Response decide(String approvalId, String verb, String userId, String businessRoles, String comment) {
    return decideWithHeader(approvalId, verb, userId, "X-User-Business-Roles", businessRoles, comment);
  }

  private static Response decideWithHeader(String approvalId, String verb, String userId, String roleHeader,
                                           String roleValue, String comment) {
    var spec = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", userId)
        .contentType(ContentType.JSON)
        .body(comment == null ? Map.of() : Map.of("comment", comment));
    if (roleValue != null) {
      spec = spec.header(roleHeader, roleValue);
    }
    return spec.when().post(APPROVALS + "/" + approvalId + "/" + verb);
  }

  private static GuardApprovalEntity approvalRow(String approvalId) {
    return QuarkusTransaction.requiringNew().call(() -> GuardApprovalEntity.<GuardApprovalEntity>findById(UUID.fromString(approvalId)));
  }

  @Test
  void 列表含待审批_DPO批准后决策为ALLOW() {
    String[] ids = createPending();

    Response listed = list(null);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(listed.jsonPath().getList("items.id", String.class)).containsExactly(ids[1]);
    assertThat(listed.jsonPath().getString("items[0].decisionId")).isEqualTo(ids[0]);
    assertThat(listed.jsonPath().getString("items[0].requiredRole")).isEqualTo(DPO);
    assertThat(listed.jsonPath().getString("items[0].principalId")).isEqualTo("agent-42");
    assertThat(listed.jsonPath().getInt("page")).isZero();
    assertThat(listed.jsonPath().getInt("size")).isEqualTo(20);
    // 信封字段按字母序输出
    assertThat(listed.asString()).startsWith("{\"items\":[{").endsWith("],\"page\":0,\"size\":20}");

    Response approved = decide(ids[1], "approve", "dpo-1", DPO, null);

    assertThat(approved.statusCode()).isEqualTo(200);
    assertThat(approved.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(approved.jsonPath().getString("approval.status")).isEqualTo("APPROVED");
    Response read = getDecision(ids[0]);
    assertThat(read.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(read.asString()).isEqualTo(approved.asString());
    GuardApprovalEntity row = approvalRow(ids[1]);
    assertThat(row.status).isEqualTo(GuardApprovalStatus.APPROVED);
    assertThat(row.decidedBy).isEqualTo("dpo-1");
    assertThat(row.receiptAuditId).isNotNull();
    assertThat(list("PENDING").asString()).isEqualTo("{\"items\":[],\"page\":0,\"size\":20}");
    assertThat(list("APPROVED").jsonPath().getList("items.id", String.class)).containsExactly(ids[1]);

    Response again = decide(ids[1], "approve", "dpo-2", DPO, null);
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.jsonPath().getString("error")).isEqualTo("approval_not_pending");
  }

  @Test
  void 发起主体自批返回403_segregation_of_duties() {
    String[] ids = createPending();

    Response r = decide(ids[1], "approve", "agent-42", DPO, null);

    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("segregation_of_duties");
    assertThat(approvalRow(ids[1]).status).isEqualTo(GuardApprovalStatus.PENDING);
  }

  @Test
  void 提交决策的同一用户换principal也不能自批_403_segregation_of_duties() {
    // 决策由 user-guard-approval 提交、principal.id 为 agent-42；同一用户带上所需角色头来批准
    String[] ids = createPending();
    assertThat(QuarkusTransaction.requiringNew().call(() -> GuardDecisionEntity
        .<GuardDecisionEntity>findById(UUID.fromString(ids[0])).requestedBy)).isEqualTo("user-guard-approval");

    Response r = decide(ids[1], "approve", "user-guard-approval", DPO, null);

    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("segregation_of_duties");
    assertThat(approvalRow(ids[1]).status).isEqualTo(GuardApprovalStatus.PENDING);
    assertThat(getDecision(ids[0]).jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
  }

  @Test
  void 缺角色头返回403_role_mismatch() {
    String[] ids = createPending();

    Response r = decide(ids[1], "approve", "dpo-1", null, null);

    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("role_mismatch");
  }

  @Test
  void 自报单值头不再放行() {
    String[] ids = createPending();

    Response r = decideWithHeader(ids[1], "approve", "dpo-1", "X-User-Business-Role", DPO, null);

    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("role_mismatch");
    assertThat(r.jsonPath().getList("verifiedRoles", String.class)).isEmpty();
    assertThat(approvalRow(ids[1]).status).isEqualTo(GuardApprovalStatus.PENDING);
  }

  @Test
  void 多角色集合含所需角色即可() {
    String[] ids = createPending();

    Response r = decide(ids[1], "approve", "dpo-1", "CISO," + DPO, null);

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
  }

  @Test
  void 角色不符时错误体带已验证角色集合() {
    String[] ids = createPending();

    Response r = decide(ids[1], "approve", "dpo-1", "CISO, Engineer", null);

    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.jsonPath().getString("error")).isEqualTo("role_mismatch");
    assertThat(r.jsonPath().getString("message")).contains(DPO);
    assertThat(r.jsonPath().getList("verifiedRoles", String.class)).containsExactly("CISO", "Engineer");
  }

  @Test
  void 列表项含决策字段() {
    String[] ids = createPending();

    Response listed = list(null);

    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(listed.jsonPath().getString("items[0].id")).isEqualTo(ids[1]);
    assertThat(listed.jsonPath().getString("items[0].actionName")).isEqualTo("delete_customer_record");
    assertThat(listed.jsonPath().getString("items[0].ruleId")).isEqualTo("CUST-DEL-001");
    assertThat(listed.jsonPath().getList("items[0].controls", String.class)).containsExactly("GDPR:ART17");
    assertThat(listed.jsonPath().getString("items[0].policyModule")).isEqualTo(DeployedMapParamProbeTest.MODULE);
    assertThat(listed.jsonPath().getString("items[0].policyFunction")).isEqualTo(DeployedMapParamProbeTest.FUNCTION);
    assertThat(listed.jsonPath().getString("items[0].resourceType")).isEqualTo("customer");
    assertThat(listed.jsonPath().getString("items[0].resourceId")).isEqualTo("C-1001");
    assertThat(listed.jsonPath().getString("items[0].reason")).isNotBlank();
    // 普通决策没有证据相关键：null 字段不写出
    assertThat(listed.jsonPath().getMap("items[0]")).doesNotContainKey("evidenceCorrelationId");
  }

  @Test
  void 拒绝无comment返回400_有comment返回DENY() {
    String[] ids = createPending();

    Response missing = decide(ids[1], "reject", "dpo-1", DPO, null);
    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.jsonPath().getString("error")).isEqualTo("comment_required");

    Response rejected = decide(ids[1], "reject", "dpo-1", DPO, "no legal basis");
    assertThat(rejected.statusCode()).isEqualTo(200);
    assertThat(rejected.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(getDecision(ids[0]).jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(approvalRow(ids[1]).comment).isEqualTo("no legal basis");
  }

  @Test
  void 不存在或非法id返回404() {
    assertThat(decide(UUID.randomUUID().toString(), "approve", "dpo-1", DPO, null).statusCode()).isEqualTo(404);
    assertThat(decide("not-a-uuid", "approve", "dpo-1", DPO, null).statusCode()).isEqualTo(404);
  }

  @Test
  void 非法status返回400() {
    Response r = list("BOGUS");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("invalid_status");
  }

  @Test
  void 过期后读取即为DENY_调度过期后状态EXPIRED() {
    // 先清掉库中其他历史遗留的到期行，使下面的计数只反映本用例
    approvalService.expireDue(Instant.now());
    String[] ids = createPending();
    QuarkusTransaction.requiringNew().run(() -> GuardApprovalEntity.update(
        "expiresAt = ?1 where id = ?2", Instant.now().minus(Duration.ofMinutes(5)), UUID.fromString(ids[1])));

    Response read = getDecision(ids[0]);
    assertThat(read.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(approvalRow(ids[1]).status).isEqualTo(GuardApprovalStatus.PENDING);
    // 行仍是 PENDING，但已到期：不出现在待审批列表里
    assertThat(list("PENDING").jsonPath().getList("items.id", String.class)).doesNotContain(ids[1]);
    Response late = decide(ids[1], "approve", "dpo-1", DPO, null);
    assertThat(late.statusCode()).isEqualTo(409);
    assertThat(late.jsonPath().getString("error")).isEqualTo("approval_not_pending");

    assertThat(approvalService.expireDue(Instant.now())).isEqualTo(1);

    GuardApprovalEntity row = approvalRow(ids[1]);
    assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(row.receiptAuditId).isNotNull();
    Response after = getDecision(ids[0]);
    assertThat(after.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    assertThat(after.jsonPath().getString("approval.status")).isEqualTo("EXPIRED");
  }

  @Test
  void 校验后加锁前到期_tx2拒绝迁移且决策保持DENY() throws Exception {
    String[] ids = createPending();
    UUID approvalId = UUID.fromString(ids[1]);
    QuarkusTransaction.requiringNew().run(() -> GuardApprovalEntity.update(
        "expiresAt = ?1 where id = ?2", Instant.now().plusMillis(300), approvalId));
    // 模拟 tx1：在到期前完成校验并定下目标状态
    GuardApprovalEntity decided = store.findApproval(TENANT, approvalId).orElseThrow();
    decided.status = GuardApprovalStatus.APPROVED;
    decided.decidedBy = "dpo-1";
    decided.decidedAt = Instant.now();
    assertThat(decided.isExpired(decided.decidedAt)).isFalse();
    String responseJson = store.findDecision(TENANT, decided.decisionId).orElseThrow().responseJson;
    Thread.sleep(400);

    assertThat(store.completeApproval(decided, responseJson)).isFalse();

    assertThat(approvalRow(ids[1]).status).isEqualTo(GuardApprovalStatus.PENDING);
    assertThat(getDecision(ids[0]).jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    Response late = decide(ids[1], "approve", "dpo-1", DPO, null);
    assertThat(late.statusCode()).isEqualTo(409);
    assertThat(late.jsonPath().getString("error")).isEqualTo("approval_not_pending");
  }

  @Test
  void 幂等重放返回同一决策且反映审批现状() {
    String key = "idem-approval-" + UUID.randomUUID();
    Response first = postDecision(null, key);
    assertThat(first.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    String approvalId = first.jsonPath().getString("approval.id");
    assertThat(decide(approvalId, "approve", "dpo-1", DPO, null).statusCode()).isEqualTo(200);

    Response replay = postDecision(null, key);

    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.jsonPath().getString("decisionId")).isEqualTo(first.jsonPath().getString("decisionId"));
    assertThat(replay.jsonPath().getString("receipt.hash")).isEqualTo(first.jsonPath().getString("receipt.hash"));
    assertThat(replay.jsonPath().getString("approval.id")).isEqualTo(approvalId);
    assertThat(replay.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(replay.jsonPath().getString("approval.status")).isEqualTo("APPROVED");
  }

  @Test
  void waitSeconds期间另一线程批准则在时限内返回ALLOW() throws Exception {
    // 预热：首次评估要编译策略，不应计入等待时长；预热产生的待审批随即拒绝掉
    String[] warmup = createPending();
    assertThat(decide(warmup[1], "reject", "dpo-1", DPO, "warmup").statusCode()).isEqualTo(200);
    // 用测试线程派生的执行器：ForkJoin 公共池线程没有 Quarkus 测试类加载器，RestAssured 序列化请求体会失败
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> approver = pool.submit(() -> {
        String approvalId = awaitPendingApprovalId();
        sleep(1000);
        return decide(approvalId, "approve", "dpo-1", DPO, null).statusCode();
      });
      long start = System.nanoTime();

      Response r = postDecision(3);

      Duration took = Duration.ofNanos(System.nanoTime() - start);
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
      assertThat(r.jsonPath().getString("approval.status")).isEqualTo("APPROVED");
      assertThat(took).isLessThan(Duration.ofSeconds(3));
      assertThat(approver.get(10, TimeUnit.SECONDS)).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void verify_chain含决策与审批事件仍valid() {
    Instant start = Instant.now().minus(Duration.ofHours(1));
    String[] approved = createPending();
    String[] rejected = createPending();
    assertThat(decide(approved[1], "approve", "dpo-1", DPO, "ok").statusCode()).isEqualTo(200);
    assertThat(decide(rejected[1], "reject", "dpo-1", DPO, "no").statusCode()).isEqualTo(200);
    Instant end = Instant.now().plus(Duration.ofMinutes(1));
    // verify-chain 把「有审计记录却零锚点」判为不可信；生产由定时任务锚定，此处显式触发。
    anchorService.anchorAllTenants();

    Response resp = given()
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", "user-guard-approval")
        .header("X-User-Role", "ADMIN")
        .queryParam("start", start.toString())
        .queryParam("end", end.toString())
        .when().get("/api/v1/audit/verify-chain");

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.jsonPath().getBoolean("valid")).isTrue();
    assertThat(resp.jsonPath().getInt("recordsVerified")).isGreaterThanOrEqualTo(4);
  }

  /** 轮询列表直到出现一条待审批（POST 仍阻塞在 waitSeconds 中）。 */
  private static String awaitPendingApprovalId() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      List<String> ids = list("PENDING").jsonPath().getList("items.id", String.class);
      if (!ids.isEmpty()) {
        return ids.get(0);
      }
      sleep(100);
    }
    throw new IllegalStateException("5 秒内未出现待审批");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
