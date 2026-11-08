package io.aster.guard.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalExpiryScheduler;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.api.GuardFromEvidenceResourceTest;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §11 D4 / §11.3 第 6 条：签发方吊销（DELETE、PUT {@code enabled=false}、过 {@code notAfter}）即把本租户依赖它的
 * PENDING 审批转 EXPIRED（{@code expired_reason=fact_issuer_revoked}），并在租户链上追加一条
 * {@code GUARD_FACT_ISSUER_REVOKED}。复用 {@link GuardFromEvidenceResourceTest.AuthProfile}：签发方端点只走 HMAC，
 * 决策请求走 Bearer，审批走 HMAC（双通道）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class FactIssuerRevocationTest {
  /**
   * 每个用例独立租户：审计链只追加不删除，用例按租户数吊销事件，不能与同一 JVM 内其它用例共用。
   * 用例顺序执行，静态字段在 {@link #setUp} 中重置，供静态请求辅助方法使用。
   */
  private static String tenantA;
  private static String tenantB;
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
  private static final String REQUESTER_KEY = "ak_test_guard_revoke_requester";
  private static final String OTHER_KEY = "ak_test_guard_revoke_other";
  private static final String DPO = "Data Protection Officer";
  private static final String ISSUERS = "/api/v1/guard/fact-issuers";
  private static final String REVOKED_EVENT = "GUARD_FACT_ISSUER_REVOKED";
  private static final String REASON = "fact_issuer_revoked";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ApiKeyVerifierService verifier;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;
  @Inject GuardApprovalExpiryScheduler scheduler;
  @Inject FactIssuerStore issuerStore;

  @BeforeEach
  void setUp() {
    String run = UUID.randomUUID().toString().substring(0, 8);
    tenantA = "tenant-guard-revoke-" + run;
    tenantB = "tenant-guard-revoke-other-" + run;
    verifier.seedCacheForTest(REQUESTER_KEY,
        ApiKeyVerifyResult.valid("ak-rv-1", "u-req", tenantA, "pro", "active", "MEMBER"));
    verifier.seedCacheForTest(OTHER_KEY,
        ApiKeyVerifyResult.valid("ak-rv-2", "u-req-other", tenantB, "pro", "active", "MEMBER"));
    QuarkusTransaction.requiringNew().run(() -> List.of(tenantA, tenantB).forEach(tenant -> {
      GuardApprovalEntity.delete("tenantId = ?1", tenant);
      GuardReceiptEntity.delete("tenantId = ?1", tenant);
      GuardDecisionEntity.delete("tenantId = ?1", tenant);
      GuardFactIssuerEntity.delete("tenantId = ?1", tenant);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
      PolicyVersion.delete("tenantId = ?1", tenant);
      PolicyCatalog.delete("tenantId = ?1", tenant);
    }));
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
    for (String tenant : List.of(tenantA, tenantB)) {
      DeployedMapParamProbeTest.deploy(tenant, DeployedMapParamProbeTest.MODULE, DeployedMapParamProbeTest.FUNCTION,
          DeployedMapParamProbeTest.SOURCE);
    }
  }

  // ---------------------------------------------------------------- 请求辅助

  private static Map<String, String> signedHeaders(String method, String path, String body, String tenant,
                                                   String userId, List<String> roles) {
    return InternalCallerSigning.headers(HMAC_KEY, method, path, "", body, tenant, "member", userId, roles);
  }

  private static Response send(String method, String path, String body, Map<String, String> headers) {
    var req = given().headers(headers);
    if (!body.isEmpty()) {
      req = req.contentType("application/json").body(body);
    }
    return req.request(method, path);
  }

  private static String issuerPath(String kid) {
    return ISSUERS + "/" + TestFactSigner.ISSUER + "/" + kid;
  }

  private static Response putIssuer(String tenant, String kid, String body) {
    return send("PUT", issuerPath(kid), body, signedHeaders("PUT", issuerPath(kid), body, tenant, "u-admin", List.of()));
  }

  private static void register(String tenant, TestFactSigner signer, String kid) {
    Response r = putIssuer(tenant, kid, "{\"publicKey\":\"" + signer.publicKey() + "\"}");
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
  }

  private static Map<String, String> deleteHeaders(String tenant, String kid) {
    return signedHeaders("DELETE", issuerPath(kid), "", tenant, "u-admin", List.of());
  }

  private static Response deleteIssuer(String tenant, String kid) {
    return send("DELETE", issuerPath(kid), "", deleteHeaders(tenant, kid));
  }

  private static Map<String, String> approveHeaders(String approvalId) {
    String path = "/api/v1/guard/approvals/" + approvalId + "/approve";
    return signedHeaders("POST", path, "{}", tenantA, "u-dpo", List.of(DPO));
  }

  private static Response approve(String approvalId) {
    return send("POST", "/api/v1/guard/approvals/" + approvalId + "/approve", "{}", approveHeaders(approvalId));
  }

  /** 探针策略对 delete_customer_record 返回 REQUIRE_APPROVAL(DPO)；facts 为 null 时不带事实。 */
  private static String pendingBody(String tenant, ObjectNode fact) {
    ObjectNode body = JSON.createObjectNode();
    body.put("policyModule", DeployedMapParamProbeTest.MODULE);
    body.put("policyFunction", DeployedMapParamProbeTest.FUNCTION);
    ObjectNode action = body.putObject("action");
    action.set("principal", JSON.valueToTree(Map.of("id", "agent-42", "type", "agent", "roles", List.of("ops-bot"))));
    action.putObject("action").put("name", "delete_customer_record");
    action.set("resource", JSON.valueToTree(Map.of("type", "customer", "id", "C-1001")));
    action.putObject("context").put("amount", 250000);
    if (fact != null) {
      action.putArray("facts").add(fact);
    }
    return body.toString();
  }

  private static ObjectNode amountFact(TestFactSigner signer, String tenant, String kid) {
    Instant now = Instant.now();
    return signer.fresh().kid(kid).audience(tenant).window(now.minusSeconds(30), now.plusSeconds(600))
        .claims("{\"context.amount\":250000}").sources("{\"context.amount\":\"ledger.amount\"}").sign();
  }

  private static Response postDecision(String tenant, ObjectNode fact, String idempotencyKey) {
    var spec = given()
        .header("Authorization", "Bearer " + (tenant.equals(tenantA) ? REQUESTER_KEY : OTHER_KEY))
        .header("X-Tenant-Id", tenant)
        .contentType("application/json")
        .body(pendingBody(tenant, fact));
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.post("/api/v1/guard/decisions");
  }

  /** 附 issuer#kid 事实开出一条 PENDING 决策，返回响应（断言为 PENDING 且 verified 含 context.amount）。 */
  private static Response pendingWithFact(String tenant, TestFactSigner signer, String kid) {
    Response r = postDecision(tenant, amountFact(signer, tenant, kid), null);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(r.jsonPath().getList("inputSources.verified")).containsExactly("context.amount");
    return r;
  }

  // ---------------------------------------------------------------- 读库辅助

  private static GuardApprovalEntity approvalRow(String approvalId) {
    return QuarkusTransaction.requiringNew().call(
        () -> GuardApprovalEntity.<GuardApprovalEntity>findById(UUID.fromString(approvalId)));
  }

  private static List<AuditLog> events(String tenant, String type) {
    return QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>list(
        "tenantId = ?1 and eventType = ?2 order by id", tenant, type));
  }

  private static JsonNode metadata(AuditLog log) {
    try {
      return JSON.readTree(log.metadata);
    } catch (Exception e) {
      throw new IllegalStateException(log.metadata, e);
    }
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 某审批的 ACTION_GUARD_APPROVAL 事件 metadata（按追加顺序）。 */
  private static List<JsonNode> approvalEvents(String approvalId) {
    return events(tenantA, "ACTION_GUARD_APPROVAL").stream().map(FactIssuerRevocationTest::metadata)
        .filter(m -> approvalId.equals(m.path("approvalId").asText())).toList();
  }

  private static String approvalId(Response decision) {
    return decision.jsonPath().getString("approval.id");
  }

  // ---------------------------------------------------------------- 用例

  /** ADR 0040 §11.3 第 6 条整条。 */
  @Test
  void revokingIssuerExpiresDependentPendingApprovals() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    Response dependent = pendingWithFact(tenantA, signer, TestFactSigner.KID);
    String decisionId = dependent.jsonPath().getString("decisionId");
    Response unrelated = postDecision(tenantA, null, null);
    assertThat(unrelated.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");

    Response deleted = deleteIssuer(tenantA, TestFactSigner.KID);

    assertThat(deleted.statusCode()).as(deleted.asString()).isEqualTo(204);
    GuardApprovalEntity row = approvalRow(approvalId(dependent));
    assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(row.expiredReason).isEqualTo(REASON);
    assertThat(row.decidedBy).isNull();
    assertThat(approvalRow(approvalId(unrelated)).status).isEqualTo(GuardApprovalStatus.PENDING);
    // 审批事件：outcome EXPIRED、带 expiredReason、SYSTEM 发起
    List<JsonNode> approvalEvents = approvalEvents(approvalId(dependent));
    assertThat(approvalEvents).hasSize(1);
    assertThat(approvalEvents.get(0).path("outcome").asText()).isEqualTo("EXPIRED");
    assertThat(approvalEvents.get(0).path("expiredReason").asText()).isEqualTo(REASON);
    // 租户链上一条吊销事件，含该决策；decision_id 列不投影
    List<AuditLog> revoked = events(tenantA, REVOKED_EVENT);
    assertThat(revoked).hasSize(1);
    JsonNode m = metadata(revoked.get(0));
    assertThat(m.path("issuer").asText()).isEqualTo(TestFactSigner.ISSUER);
    assertThat(m.path("kid").asText()).isEqualTo(TestFactSigner.KID);
    assertThat(m.path("performedBy").asText()).isEqualTo("u-admin");
    assertThat(texts(m.path("affectedDecisionIds"))).containsExactly(decisionId);
    assertThat(m.has("affectedCount")).isFalse();
    assertThat(m.has("affectedTruncated")).isFalse();
    assertThat(revoked.get(0).decisionId).isNull();
    assertThat(revoked.get(0).performedBy).isEqualTo("u-admin");
    // 决策回读：审批 EXPIRED → DENY
    Response read = given().header("Authorization", "Bearer " + REQUESTER_KEY).header("X-Tenant-Id", tenantA)
        .get("/api/v1/guard/decisions/" + decisionId);
    assertThat(read.jsonPath().getString("approval.status")).as(read.asString()).isEqualTo("EXPIRED");
    assertThat(read.jsonPath().getString("effectiveOutcome")).isEqualTo("DENY");
    // 收件箱：EXPIRED 列表项带 expiredReason
    String listPath = "/api/v1/guard/approvals";
    Response inbox = given().headers(InternalCallerSigning.headers(HMAC_KEY, "GET", listPath, "status=EXPIRED", "",
        tenantA, "member", "u-dpo", List.of(DPO))).get(listPath + "?status=EXPIRED");
    assertThat(inbox.statusCode()).as(inbox.asString()).isEqualTo(200);
    assertThat(inbox.jsonPath().getList("items.expiredReason")).containsExactly(REASON);

    // 对它 approve → 409 approval_not_pending，附 expiredReason
    Response late = approve(approvalId(dependent));
    assertThat(late.statusCode()).as(late.asString()).isEqualTo(409);
    assertThat(late.jsonPath().getString("error")).isEqualTo("approval_not_pending");
    assertThat(late.jsonPath().getString("expiredReason")).isEqualTo(REASON);

    // 吊销后旧签发方的事实 → 400 invalid_fact
    Response stale = postDecision(tenantA, amountFact(signer, tenantA, TestFactSigner.KID), null);
    assertThat(stale.statusCode()).as(stale.asString()).isEqualTo(400);
    assertThat(stale.jsonPath().getString("error")).isEqualTo("invalid_fact");
    // 同一动作换新键、不附事实 → declared（PENDING，无 inputSources）
    Response declared = postDecision(tenantA, null, "idem-revoke-declared-" + UUID.randomUUID());
    assertThat(declared.statusCode()).as(declared.asString()).isEqualTo(200);
    assertThat(declared.jsonPath().getString("effectiveOutcome")).isEqualTo("PENDING");
    assertThat(declared.jsonPath().getMap("$")).doesNotContainKey("inputSources");
    // 附新签发方（轮换到新 kid）的事实 → PENDING 且 verified
    TestFactSigner rotated = TestFactSigner.newKey();
    register(tenantA, rotated, "2026-12");
    Response renewed = pendingWithFact(tenantA, rotated, "2026-12");
    assertThat(renewed.jsonPath().getList("inputSources.factIssuers"))
        .containsExactly(TestFactSigner.ISSUER + "#2026-12");

    // 幂等：再次 DELETE 仍 204，不写第二条吊销事件，不动其它审批
    Response again = deleteIssuer(tenantA, TestFactSigner.KID);
    assertThat(again.statusCode()).isEqualTo(204);
    assertThat(events(tenantA, REVOKED_EVENT)).hasSize(1);
    assertThat(approvalEvents(approvalId(dependent))).hasSize(1);
    assertThat(approvalRow(approvalId(renewed)).status).isEqualTo(GuardApprovalStatus.PENDING);
  }

  /** PUT enabled=false（true → false）同样触发联动；重复提交 enabled=false 不再写事件。 */
  @Test
  void putEnabledFalseRevokesOnce() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    Response dependent = pendingWithFact(tenantA, signer, TestFactSigner.KID);
    String disable = "{\"publicKey\":\"" + signer.publicKey() + "\",\"enabled\":false}";

    Response first = putIssuer(tenantA, TestFactSigner.KID, disable);
    Response second = putIssuer(tenantA, TestFactSigner.KID, disable);

    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    assertThat(first.jsonPath().getBoolean("enabled")).isFalse();
    assertThat(second.statusCode()).as(second.asString()).isEqualTo(200);
    GuardApprovalEntity row = approvalRow(approvalId(dependent));
    assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(row.expiredReason).isEqualTo(REASON);
    List<AuditLog> revoked = events(tenantA, REVOKED_EVENT);
    assertThat(revoked).hasSize(1);
    assertThat(texts(metadata(revoked.get(0)).path("affectedDecisionIds")))
        .containsExactly(dependent.jsonPath().getString("decisionId"));
  }

  /** 新建即禁用的签发方没有「启用 → 禁用」迁移：不写吊销事件。 */
  @Test
  void createdDisabledWritesNoRevocationEvent() {
    TestFactSigner signer = TestFactSigner.newKey();

    Response r = putIssuer(tenantA, TestFactSigner.KID, "{\"publicKey\":\"" + signer.publicKey() + "\",\"enabled\":false}");

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(events(tenantA, REVOKED_EVENT)).isEmpty();
  }

  /** 吊销只作用于本租户：其它租户同名 issuer#kid 的 PENDING 审批不受影响。 */
  @Test
  void revocationIsTenantScoped() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    register(tenantB, signer, TestFactSigner.KID);
    Response mine = pendingWithFact(tenantA, signer, TestFactSigner.KID);
    Response theirs = pendingWithFact(tenantB, signer, TestFactSigner.KID);

    assertThat(deleteIssuer(tenantA, TestFactSigner.KID).statusCode()).isEqualTo(204);

    assertThat(approvalRow(approvalId(mine)).status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(approvalRow(approvalId(theirs)).status).isEqualTo(GuardApprovalStatus.PENDING);
    assertThat(events(tenantB, REVOKED_EVENT)).isEmpty();
    assertThat(issuerStore.find(tenantB, TestFactSigner.ISSUER, TestFactSigner.KID).orElseThrow().enabled).isTrue();
  }

  /** 过 notAfter 由过期调度同一轮处理：performedBy=SYSTEM，签发方行转为禁用，第二轮不再写事件。 */
  @Test
  void notAfterExpiryHandledByScheduler() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    Response dependent = pendingWithFact(tenantA, signer, TestFactSigner.KID);
    String lapsed = "{\"publicKey\":\"" + signer.publicKey() + "\",\"notAfter\":\""
        + Instant.now().minusSeconds(5) + "\"}";
    assertThat(putIssuer(tenantA, TestFactSigner.KID, lapsed).statusCode()).isEqualTo(200);
    assertThat(events(tenantA, REVOKED_EVENT)).isEmpty();

    scheduler.expire();
    scheduler.expire();

    GuardApprovalEntity row = approvalRow(approvalId(dependent));
    assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(row.expiredReason).isEqualTo(REASON);
    List<AuditLog> revoked = events(tenantA, REVOKED_EVENT);
    assertThat(revoked).hasSize(1);
    JsonNode m = metadata(revoked.get(0));
    assertThat(m.path("performedBy").asText()).isEqualTo("SYSTEM");
    assertThat(revoked.get(0).performedBy).isEqualTo("SYSTEM");
    assertThat(texts(m.path("affectedDecisionIds"))).containsExactly(dependent.jsonPath().getString("decisionId"));
    assertThat(issuerStore.find(tenantA, TestFactSigner.ISSUER, TestFactSigner.KID).orElseThrow().enabled).isFalse();
  }

  /** 超过 64 条受影响决策：affectedDecisionIds 截为 64 项，另写 affectedCount 与 affectedTruncated:true；全部转 EXPIRED。 */
  @Test
  void affectedOver64Truncated() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    List<String> approvals = new ArrayList<>();
    List<String> decisions = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      Response r = pendingWithFact(tenantA, signer, TestFactSigner.KID);
      approvals.add(approvalId(r));
      decisions.add(r.jsonPath().getString("decisionId"));
    }

    assertThat(deleteIssuer(tenantA, TestFactSigner.KID).statusCode()).isEqualTo(204);

    approvals.forEach(id -> assertThat(approvalRow(id).status).isEqualTo(GuardApprovalStatus.EXPIRED));
    List<AuditLog> revoked = events(tenantA, REVOKED_EVENT);
    assertThat(revoked).hasSize(1);
    JsonNode m = metadata(revoked.get(0));
    List<String> listed = texts(m.path("affectedDecisionIds"));
    assertThat(listed).hasSize(64).doesNotHaveDuplicates();
    assertThat(decisions).containsAll(listed);
    assertThat(m.path("affectedCount").asInt()).isEqualTo(65);
    assertThat(m.path("affectedTruncated").asBoolean()).isTrue();
  }

  /**
   * 级联漏掉的审批（如吊销与决策落库并发、或联动中途失败）：审批时发现所依赖的签发方已吊销，
   * 就地按同一路径转 EXPIRED 并 409 approval_not_pending（附 expiredReason），不放行。
   */
  @Test
  void approveAfterMissedCascadeExpiresInPlace() {
    TestFactSigner signer = TestFactSigner.newKey();
    register(tenantA, signer, TestFactSigner.KID);
    Response dependent = pendingWithFact(tenantA, signer, TestFactSigner.KID);
    assertThat(issuerStore.markRevoked(tenantA, TestFactSigner.ISSUER, TestFactSigner.KID)).isTrue();

    Response r = approve(approvalId(dependent));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("error")).isEqualTo("approval_not_pending");
    assertThat(r.jsonPath().getString("expiredReason")).isEqualTo(REASON);
    GuardApprovalEntity row = approvalRow(approvalId(dependent));
    assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
    assertThat(row.expiredReason).isEqualTo(REASON);
  }

  /**
   * 审批与吊销并发：行锁决定唯一终态。批准赢 → APPROVED、200、吊销事件不含该决策；
   * 吊销赢 → EXPIRED(fact_issuer_revoked)、409（附 expiredReason）。决策行 response_json 与审批行一致。
   */
  @Test
  void concurrentApproveAndRevokeEndInOneTerminalState() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 6; round++) {
        String kid = "race-" + round;
        TestFactSigner signer = TestFactSigner.newKey();
        register(tenantA, signer, kid);
        Response dependent = pendingWithFact(tenantA, signer, kid);
        String id = approvalId(dependent);
        String decisionId = dependent.jsonPath().getString("decisionId");
        // 签名头在主线程预先生成：并发签名可能拿到相同 nonce 而被防重放拒绝，与被测逻辑无关
        Map<String, String> approveHeaders = approveHeaders(id);
        Map<String, String> deleteHeaders = deleteHeaders(tenantA, kid);
        int revokedBefore = events(tenantA, REVOKED_EVENT).size();
        CountDownLatch start = new CountDownLatch(1);

        Future<Response> approving = pool.submit(() -> {
          start.await();
          return send("POST", "/api/v1/guard/approvals/" + id + "/approve", "{}", approveHeaders);
        });
        Future<Response> revoking = pool.submit(() -> {
          start.await();
          return send("DELETE", issuerPath(kid), "", deleteHeaders);
        });
        start.countDown();
        Response approved = approving.get(30, TimeUnit.SECONDS);
        Response revokedResp = revoking.get(30, TimeUnit.SECONDS);

        assertThat(revokedResp.statusCode()).as(revokedResp.asString()).isEqualTo(204);
        GuardApprovalEntity row = approvalRow(id);
        List<AuditLog> revoked = events(tenantA, REVOKED_EVENT);
        assertThat(revoked).hasSize(revokedBefore + 1);
        List<String> affected = texts(metadata(revoked.get(revoked.size() - 1)).path("affectedDecisionIds"));
        GuardDecisionEntity decision = QuarkusTransaction.requiringNew().call(
            () -> GuardDecisionEntity.<GuardDecisionEntity>findById(UUID.fromString(decisionId)));
        JsonNode stored = JSON.readTree(decision.responseJson);
        assertThat(stored.path("approval").path("status").asText()).isEqualTo(row.status.name());
        if (row.status == GuardApprovalStatus.APPROVED) {
          assertThat(approved.statusCode()).as(approved.asString()).isEqualTo(200);
          assertThat(row.expiredReason).isNull();
          assertThat(affected).doesNotContain(decisionId);
          assertThat(stored.path("effectiveOutcome").asText()).isEqualTo("ALLOW");
        } else {
          assertThat(row.status).isEqualTo(GuardApprovalStatus.EXPIRED);
          assertThat(row.expiredReason).isEqualTo(REASON);
          assertThat(approved.statusCode()).as(approved.asString()).isEqualTo(409);
          assertThat(approved.jsonPath().getString("error")).isEqualTo("approval_not_pending");
          assertThat(approved.jsonPath().getString("expiredReason")).isEqualTo(REASON);
          // 吊销已先生效时，审批请求可能自己按同一路径把它转 EXPIRED（就地兜底），此时吊销事件不列该决策
          assertThat(stored.path("effectiveOutcome").asText()).isEqualTo("DENY");
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
