package io.aster.guard.deploy;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.aster.policy.entity.AuditLog;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.deploy.PolicyDeployment.DeployResult;
import io.aster.guard.deploy.PolicyDeployment.Deployed;
import io.aster.guard.deploy.PolicyDeployment.Rejected;
import io.aster.guard.deploy.PolicyDeployment.Withdrawn;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.service.PolicyVersionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0054 §11 D1（R66）：cloud 策略部署服务，真实 PG。测试配置下 apikey / rbac / 签名过滤器关闭，
 * 决策端点只靠 {@code X-Tenant-Id} 识别租户。版本链按 {@code module.function} 跨租户共享，故模块名带运行后缀。
 */
@QuarkusTest
class PolicyDeploymentServiceTest {
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-deploy-" + RUN;
  private static final String MODULE = "guard.deploy.r" + RUN;
  private static final String FUNCTION = "decide";
  private static final String USER = "u-cloud-admin";
  private static final String DEFINE =
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.";
  /** 只有在词汇下 Fahrer / alter 才解析为规范名 Driver / age。 */
  private static final Map<String, Object> VOCABULARY = Map.of(
      "id", "custom-" + RUN, "name", "Custom", "locale", "en-US", "version", "user",
      "structs", List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
      "fields", List.of(Map.of("canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
      "functions", List.of(), "enumValues", List.of());

  /** 词汇把本地化类型名 Fahrer 解析为规范名 Driver：Core IR 里出现名为 Driver 的声明即用了词汇。 */
  private static final java.util.regex.Pattern DRIVER_TYPE = java.util.regex.Pattern.compile("\"name\":\\s*\"Driver\"");

  @Inject PolicyDeploymentService service;
  @Inject PolicyCompiler compiler;
  @Inject PolicyVersionService versionService;

  @AfterEach
  void clean() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardReceiptEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
      PolicyVersion.delete("tenantId = ?1", TENANT);
      PolicyCatalog.delete("tenantId = ?1", TENANT);
    });
  }

  private static String allow(String module, String ruleId) {
    return String.join("\n", "Module " + module + ".", "", DEFINE, "",
        "@id(\"" + ruleId + "\")",
        "Rule decide given req as GuardRequest, produce Verdict:",
        "  Return Verdict.allow().");
  }

  private static String localized(String module) {
    return String.join("\n", "Module " + module + ".", "", "Define Fahrer has alter as Int.", "",
        "Rule decide given driver as Fahrer, produce Int:", "  Return driver.alter.");
  }

  private static PolicyDeployment deployment(String cloudPolicyId, long generation, String cloudVersionId,
                                             String source, Map<String, Object> vocabulary) {
    return new PolicyDeployment(cloudPolicyId, generation, cloudVersionId, 1, MODULE, FUNCTION, source, "en-US",
        null, vocabulary, PolicyDeploymentService.sha256(source));
  }

  private DeployResult deploy(String cloudPolicyId, long generation, String cloudVersionId, String source) {
    return service.deploy(TENANT, USER, deployment(cloudPolicyId, generation, cloudVersionId, source, null));
  }

  private static Deployed ok(DeployResult r) {
    assertThat(r).as(String.valueOf(r)).isInstanceOf(Deployed.class);
    return (Deployed) r;
  }

  private static Rejected rejected(DeployResult r, int status, String error) {
    assertThat(r).as(String.valueOf(r)).isInstanceOf(Rejected.class);
    Rejected rej = (Rejected) r;
    assertThat(rej.status()).isEqualTo(status);
    assertThat(rej.error()).isEqualTo(error);
    return rej;
  }

  private static PolicyVersion activeVersion() {
    return QuarkusTransaction.requiringNew().call(() -> {
      PolicyCatalog c = PolicyCatalog.<PolicyCatalog>find("tenantId = ?1 and moduleName = ?2 and functionName = ?3",
          TENANT, MODULE, FUNCTION).firstResult();
      return c == null || c.defaultVersionId == null ? null
          : PolicyVersion.<PolicyVersion>find("id = ?1 and active = true", c.defaultVersionId).firstResult();
    });
  }

  private static long versionRows() {
    return QuarkusTransaction.requiringNew().call(() -> PolicyVersion.count("tenantId = ?1", TENANT));
  }

  private static io.restassured.response.Response decide() {
    Map<String, Object> action = Map.of(
        "principal", Map.of("id", "agent-1", "type", "agent"),
        "action", Map.of("name", "lookup_payment"),
        "context", Map.of("paymentId", "P-1"));
    return given().header("X-Tenant-Id", TENANT).header("X-User-Id", "agent-user")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(ContentType.JSON)
        .body(Map.of("policyModule", MODULE, "policyFunction", FUNCTION, "action", action))
        .post("/api/v1/guard/decisions");
  }

  @Test
  void firstDeployCreatesActiveVersionWithFrozenVocabulary() {
    Deployed d = ok(service.deploy(TENANT, USER,
        deployment("cp-1", 1, "cv-1", localized(MODULE), VOCABULARY)));
    assertThat(d.created()).isTrue();
    PolicyVersion v = activeVersion();
    assertThat(v).isNotNull();
    assertThat(v.id).isEqualTo(d.policyVersionId());
    assertThat(v.sourceKind).isEqualTo("cloud-deploy");
    assertThat(v.status).isEqualTo(VersionStatus.APPROVED);
    assertThat(v.approvedBy).isEqualTo(USER);
    assertThat(v.externalRef).isEqualTo("cv-1");
    assertThat(v.vocabularyJson).contains("Fahrer").contains("Driver");
    assertThat(v.coreJson).containsPattern(DRIVER_TYPE);
  }

  @Test
  void compileByVersionUsesFrozenVocabularyWhenNoCoreJson() {
    ok(service.deploy(TENANT, USER, deployment("cp-1", 1, "cv-1", localized(MODULE), VOCABULARY)));
    Long id = activeVersion().id;
    QuarkusTransaction.requiringNew().run(() -> PolicyVersion.update("coreJson = null where id = ?1", id));
    PolicyVersion fresh = QuarkusTransaction.requiringNew().call(() -> PolicyVersion.<PolicyVersion>findById(id));
    assertThat(compiler.typecheckVersion(fresh).getCoreJson()).containsPattern(DRIVER_TYPE);
    fresh.vocabularyJson = null;
    assertThat(compiler.typecheckVersion(fresh).getCoreJson()).doesNotContainPattern(DRIVER_TYPE);
  }

  @Test
  void envelopeOfDeployedVersionCoversVocabulary() {
    String source = localized(MODULE);
    long withVocab = ok(service.deploy(TENANT, USER, deployment("cp-1", 1, "cv-1", source, VOCABULARY)))
        .policyVersionId();
    long without = ok(service.deploy(TENANT, USER, deployment("cp-1", 2, "cv-2", source, null))).policyVersionId();
    String[] envelopes = QuarkusTransaction.requiringNew().call(() -> new String[] {
        PolicyVersion.<PolicyVersion>findById(withVocab).sourceEnvelopeSha256,
        PolicyVersion.<PolicyVersion>findById(without).sourceEnvelopeSha256});
    assertThat(envelopes[0]).isNotEqualTo(envelopes[1]);
    PolicyVersion plain = QuarkusTransaction.requiringNew().call(() -> PolicyVersion.<PolicyVersion>findById(without));
    assertThat(plain.vocabularyJson).isNull();
    assertThat(PolicyVersion.computeSourceEnvelope(plain.content, plain.aliasSet, plain.locale,
        plain.sourceToolchainId, "")).isEqualTo(plain.sourceEnvelopeSha256);
  }

  @Test
  void sameCloudVersionReplayIsIdempotent() {
    Deployed first = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    Deployed again = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    assertThat(again.created()).isFalse();
    assertThat(again.policyVersionId()).isEqualTo(first.policyVersionId());
    assertThat(versionRows()).isEqualTo(1);
  }

  @Test
  void redeployOlderCloudVersionReactivatesIt() {
    Deployed v1 = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    Deployed v2 = ok(deploy("cp-1", 2, "cv-2", allow(MODULE, "R-2")));
    assertThat(activeVersion().id).isEqualTo(v2.policyVersionId());
    Deployed back = ok(deploy("cp-1", 3, "cv-1", allow(MODULE, "R-1")));
    assertThat(back.created()).isFalse();
    assertThat(back.policyVersionId()).isEqualTo(v1.policyVersionId());
    assertThat(activeVersion().id).isEqualTo(v1.policyVersionId());
    assertThat(versionRows()).isEqualTo(2);
  }

  @Test
  void sameCloudVersionDifferentSourceIs409Mismatch() {
    ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    rejected(deploy("cp-1", 2, "cv-1", allow(MODULE, "R-other")), 409, "deployment_mismatch");
  }

  @Test
  void otherCloudPolicyOnSameAddressIs409AddressConflict() {
    Deployed mine = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    rejected(deploy("cp-2", 1, "cv-9", allow(MODULE, "R-9")), 409, "address_conflict");
    assertThat(activeVersion().id).isEqualTo(mine.policyVersionId());
  }

  @Test
  void compileFailureIs422WithDiagnostics() {
    Rejected r = rejected(deploy("cp-1", 1, "cv-1", "Module " + MODULE + ".\n\nRule decide given x as, produce:"),
        422, "compile_failed");
    assertThat(r.detail()).isInstanceOf(Map.class);
    assertThat(versionRows()).isZero();
  }

  @Test
  void missingEntryFunctionIs422() {
    PolicyDeployment d = new PolicyDeployment("cp-1", 1, "cv-1", 1, MODULE, "nope", allow(MODULE, "R-1"), "en-US",
        null, null, PolicyDeploymentService.sha256(allow(MODULE, "R-1")));
    rejected(service.deploy(TENANT, USER, d), 422, "function_not_found");
    assertThat(versionRows()).isZero();
  }

  @Test
  void moduleHeaderMismatchIs400() {
    rejected(deploy("cp-1", 1, "cv-1", allow(MODULE + "x", "R-1")), 400, "invalid_deployment");
  }

  @Test
  void sourceSha256MismatchIs400() {
    PolicyDeployment d = new PolicyDeployment("cp-1", 1, "cv-1", 1, MODULE, FUNCTION, allow(MODULE, "R-1"), "en-US",
        null, null, PolicyDeploymentService.sha256("something else"));
    rejected(service.deploy(TENANT, USER, d), 400, "invalid_deployment");
    assertThat(versionRows()).isZero();
  }

  @Test
  void experimentalFeatureIs422StabilityBlocked() {
    String experimental = String.join("\n", "Module " + MODULE + ".", "", "@deprecated",
        "Rule decide, produce Text:", "  Return \"x\".");
    rejected(deploy("cp-1", 1, "cv-1", experimental), 422, "stability_blocked");
    assertThat(versionRows()).isZero();
  }

  @Test
  void invalidVocabularyIs422() {
    Map<String, Object> bad = Map.of("structs", List.of(Map.of("canonical", 5)));
    rejected(service.deploy(TENANT, USER, deployment("cp-1", 1, "cv-1", allow(MODULE, "R-1"), bad)),
        422, "vocabulary_invalid");
  }

  @Test
  void delayedOlderGenerationIsRejected() {
    ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    Deployed v2 = ok(deploy("cp-1", 2, "cv-2", allow(MODULE, "R-2")));
    rejected(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")), 409, "stale_generation");
    rejected(service.withdraw(TENANT, USER, "cp-1", 1), 409, "stale_generation");
    assertThat(activeVersion().id).isEqualTo(v2.policyVersionId());
  }

  @Test
  void deployedPolicyResolvesForDecisionsAndWithdrawMakesItPolicyNotFound() {
    Deployed d = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    var allowed = decide();
    assertThat(allowed.statusCode()).as(allowed.asString()).isEqualTo(200);
    assertThat(allowed.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    assertThat(allowed.jsonPath().getLong("policyVersion.id")).isEqualTo(d.policyVersionId());
    assertThat(allowed.jsonPath().getLong("executionReceipt.payload.policyVersionId")).isEqualTo(d.policyVersionId());

    assertThat(service.withdraw(TENANT, USER, "cp-1", 2)).isInstanceOf(Withdrawn.class);
    var gone = decide();
    assertThat(gone.statusCode()).as(gone.asString()).isEqualTo(404);
    assertThat(gone.jsonPath().getString("error")).isEqualTo("policy_not_found");
    assertThat(service.withdraw(TENANT, USER, "cp-1", 2)).isInstanceOf(Withdrawn.class);
    rejected(service.withdraw(TENANT, USER, "cp-unknown", 1), 404, "not_found");

    ok(deploy("cp-1", 3, "cv-1", allow(MODULE, "R-1")));
    assertThat(decide().statusCode()).isEqualTo(200);
  }

  @Test
  void decisionReadCarriesPolicyAddress() {
    ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    String id = decide().jsonPath().getString("decisionId");
    var read = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "agent-user")
        .get("/api/v1/guard/decisions/" + id);
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.jsonPath().getString("policyModule")).isEqualTo(MODULE);
    assertThat(read.jsonPath().getString("policyFunction")).isEqualTo(FUNCTION);
  }

  @Test
  void addressChangeWithdrawsOldAddress() {
    ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    String moved = MODULE + "b";
    String source = allow(moved, "R-2");
    ok(service.deploy(TENANT, USER, new PolicyDeployment("cp-1", 2, "cv-2", 2, moved, FUNCTION, source, "en-US",
        null, null, PolicyDeploymentService.sha256(source))));
    assertThat(activeVersion()).isNull();
    assertThat(decide().statusCode()).isEqualTo(404);
  }

  @Test
  void deployAndWithdrawAppendAuditEvents() {
    Deployed d = ok(deploy("cp-1", 1, "cv-1", allow(MODULE, "R-1")));
    service.withdraw(TENANT, USER, "cp-1", 2);
    List<AuditLog> events = QuarkusTransaction.requiringNew().call(() -> AuditLog.<AuditLog>find(
        "tenantId = ?1 and eventType in ('POLICY_DEPLOYED', 'POLICY_WITHDRAWN') order by id", TENANT).list());
    assertThat(events).extracting(e -> e.eventType).containsExactly("POLICY_DEPLOYED", "POLICY_WITHDRAWN");
    assertThat(events.get(0).metadata).contains("\"cloudPolicyId\":\"cp-1\"").contains("\"cloudVersionId\":\"cv-1\"")
        .contains("\"policyVersionId\":" + d.policyVersionId());
    assertThat(events.get(0).performedBy).isEqualTo(USER);
  }

  @Test
  void concurrentDeploysLeaveExactlyOneActive() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<DeployResult>> futures = new ArrayList<>();
      for (int i = 1; i <= 2; i++) {
        long gen = i;
        String cv = "cv-" + i;
        futures.add(pool.submit(() -> {
          start.await();
          return deploy("cp-1", gen, cv, allow(MODULE, "R-" + gen));
        }));
      }
      start.countDown();
      List<DeployResult> results = new ArrayList<>();
      for (Future<DeployResult> f : futures) {
        results.add(f.get(60, TimeUnit.SECONDS));
      }
      for (DeployResult r : results) {
        if (r instanceof Rejected rej) {
          assertThat(rej.error()).isIn("stale_generation", "concurrent_activation");
        }
      }
      long active = QuarkusTransaction.requiringNew().call(() ->
          PolicyVersion.count("tenantId = ?1 and active = true", TENANT));
      assertThat(active).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
