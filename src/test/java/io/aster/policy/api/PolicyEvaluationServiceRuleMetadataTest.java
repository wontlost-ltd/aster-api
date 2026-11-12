package io.aster.policy.api;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.provenance.InputSources;
import io.aster.guard.provenance.VerifiedInputsGate;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicy;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.governance.RuleMetadata;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按版本固定的规则元数据与评估（Task 16 carry-over 2 / 5）：真实编译缓存、版本解析缓存与版本表。
 */
@QuarkusTest
class PolicyEvaluationServiceRuleMetadataTest {
  static final String TENANT = "tenant-rule-metadata";
  static final String MODULE = "guard.meta";
  static final String FUNCTION = "decide";
  static final String INT_MODULE = "demo.meta";

  /** 规则 id 不同的两个版本，用来区分读到的是哪一版。 */
  static String source(String ruleId) {
    return String.join("\n",
        "Module guard.meta.", "",
        "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.", "",
        "@id(\"" + ruleId + "\")",
        "Rule decide given req as GuardRequest, produce Verdict:",
        "  If req.action equals to \"delete_customer_record\":",
        "    Return Verdict.require_approval(\"Data Protection Officer\", \"customer record deletion\").",
        "  Otherwise:",
        "    Return Verdict.allow().");
  }

  static final String INT_SOURCE = String.join("\n",
      "Module demo.meta.", "",
      "Rule decide given a as Int, b as Int, produce Int:",
      "  Return a times b.");

  static Map<String, Object> request() {
    return Map.of("principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
        "action", "delete_customer_record", "resource_type", "customer", "resource_id", "C-1001",
        "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");
  }

  @Inject PolicyEvaluationService service;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void clean() {
    clean(TENANT);
    service.clearAllCache().await().indefinitely();
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
  }

  static void clean(String tenant) {
    QuarkusTransaction.requiringNew().run(() -> {
      PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
      PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
      PolicyVersion.delete("tenantId = ?1", tenant);
      PolicyCatalog.delete("tenantId = ?1", tenant);
    });
  }

  /** 在已有目录下新增一个活跃版本并设为默认（激活版本「移动」）。 */
  static Long activateNewVersion(String tenant, String module, String function, String source) {
    return QuarkusTransaction.requiringNew().call(() -> {
      PolicyCatalog catalog = PolicyCatalog.<PolicyCatalog>find("tenantId = ?1 and moduleName = ?2 and functionName = ?3",
          tenant, module, function).firstResult();
      PolicyVersion version = new PolicyVersion();
      version.policyId = module + "." + function; version.version = System.nanoTime();
      version.moduleName = module; version.functionName = function; version.content = source;
      version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
      version.tenantId = tenant; version.createdAt = Instant.now(); version.createdBy = "rule-metadata-test";
      version.persist();
      catalog.defaultVersionId = version.id; catalog.persist();
      return version.id;
    });
  }

  @Test
  void ruleMetadataIsVersionPinned() {
    Long first = DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, source("META-A"));
    assertThat(service.ruleMetadata(TENANT, MODULE, FUNCTION).ruleId()).isEqualTo("META-A");

    Long second = activateNewVersion(TENANT, MODULE, FUNCTION, source("META-B"));
    versionResolutionCache.clear();

    // 激活版本已移动：按版本取仍读旧版本，按活跃版本取读新版本
    assertThat(service.ruleMetadata(TENANT, MODULE, FUNCTION, String.valueOf(first)).ruleId()).isEqualTo("META-A");
    assertThat(service.ruleMetadata(TENANT, MODULE, FUNCTION).ruleId()).isEqualTo("META-B");
    // 评估按同一 versionId 执行：结果的规则身份与版本号都属于旧版本
    PolicyEvaluationResult pinned = service.evaluatePolicyAtVersion(TENANT, MODULE, FUNCTION, String.valueOf(first),
        new Object[]{request()}).await().indefinitely();
    assertThat(pinned.getRuleId()).isEqualTo("META-A");
    assertThat(pinned.getPolicyVersionId()).isEqualTo(first);
    PolicyEvaluationResult active = service.evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{request()})
        .await().indefinitely();
    assertThat(active.getRuleId()).isEqualTo("META-B");
    assertThat(active.getPolicyVersionId()).isEqualTo(second);
  }

  @Test
  void ruleMetadataMemoizedPerCompiledArtifact() {
    Long id = DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, source("META-M"));
    String versionId = String.valueOf(id);

    RuleMetadata first = service.ruleMetadata(TENANT, MODULE, FUNCTION, versionId);
    RuleMetadata second = service.ruleMetadata(TENANT, MODULE, FUNCTION, versionId);
    // 评估同样走同一产物上的 memo
    service.evaluatePolicyAtVersion(TENANT, MODULE, FUNCTION, versionId, new Object[]{request()}).await().indefinitely();

    // RuleMetadata.of 每次构造新实例：同一实例说明 Core IR 只解析了一次
    assertThat(second).isSameAs(first);
    CompiledPolicy compiled = compiledPolicyCache.get(TENANT, MODULE, FUNCTION, versionId).orElseThrow();
    AtomicInteger parses = new AtomicInteger();
    RuleMetadata memo = compiled.ruleMetadata(FUNCTION, fn -> {
      parses.incrementAndGet();
      return RuleMetadata.NONE;
    });
    assertThat(parses).hasValue(0);
    assertThat(memo).isSameAs(first);
    assertThat(memo.ruleId()).isEqualTo("META-M");
  }

  /** 租户标记作为第一个上下文元素时被剥离（与 evaluatePolicy 同一 normalizeContext 步骤）。 */
  @Test
  void evaluatePolicyAtVersionNormalizesContext() {
    Long id = DeployedMapParamProbeTest.deploy(TENANT, INT_MODULE, FUNCTION, INT_SOURCE);

    PolicyEvaluationResult withMarker = service.evaluatePolicyAtVersion(TENANT, INT_MODULE, FUNCTION,
        String.valueOf(id), new Object[]{TENANT, 6, 7}).await().indefinitely();
    PolicyEvaluationResult plain = service.evaluatePolicyAtVersion(TENANT, INT_MODULE, FUNCTION,
        String.valueOf(id), new Object[]{6, 7}).await().indefinitely();

    assertThat(withMarker.getResult()).isEqualTo(42);
    assertThat(plain.getResult()).isEqualTo(42);
  }

  /**
   * 今天没有档案要求 verifiedInputs：声明高风险档案的已部署模块，真实元数据也是「不要求」，
   * 来源门对全 declared 的请求不拒绝（Task 16a 不改变任何决策）。
   */
  @Test
  void highRiskProfileModuleIsNotGatedToday() {
    String src = source("HR-001")
        .replace("Module guard.meta.", "Module guard.meta.\nProfile \"eu-ai-act-high-risk\".")
        .replace("@id(\"HR-001\")", "@id(\"HR-001\")\n@control(\"EU_AI_ACT:ART14\")");
    Long id = DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, src);

    RuleMetadata meta = service.ruleMetadata(TENANT, MODULE, FUNCTION, String.valueOf(id));

    assertThat(meta.profile()).isEqualTo("eu-ai-act-high-risk");
    assertThat(meta.ruleId()).isEqualTo("HR-001");
    assertThat(meta.verifiedInputsRequired()).isFalse();
    Action action = new Action(new Principal("agent-42", null, List.of("ops-bot")),
        new ActionName("delete_customer_record"), null, Map.of("amount", 250000), null);
    assertThat(VerifiedInputsGate.check(meta, InputSources.NONE, VerifiedInputsGate.presentLeafPaths(action))).isEmpty();
  }

  /** 租户隔离：别的租户的 versionId 交给本租户的按版本入口，按「不存在」拒绝，不加载他人的产物。 */
  @Test
  void foreignTenantVersionIdIsRejected() {
    String other = TENANT + "-other";
    clean(other);
    try {
      Long foreign = DeployedMapParamProbeTest.deploy(other, MODULE, FUNCTION, source("OTHER-1"));
      DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, source("MINE-1"));

      org.assertj.core.api.Assertions.assertThatThrownBy(
          () -> service.ruleMetadata(TENANT, MODULE, FUNCTION, String.valueOf(foreign)))
          .isInstanceOf(PolicyNotExecutableException.class).hasMessageContaining("not found");
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluatePolicyAtVersion(TENANT, MODULE,
          FUNCTION, String.valueOf(foreign), new Object[]{request()}).await().indefinitely());
    } finally {
      clean(other);
    }
  }
}
