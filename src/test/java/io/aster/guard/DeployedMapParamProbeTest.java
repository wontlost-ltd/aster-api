package io.aster.guard;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §2.3 探针：部署路径的 Truffle 上下文能否让规则读取 Map 参数的成员。
 * 通过本测试，Action Guard 才能把扁平化的 Action 作为单一记录参数传入已部署规则。
 */
@QuarkusTest
public class DeployedMapParamProbeTest {
  static final String TENANT = "tenant-guard-probe";
  public static final String MODULE = "guard.probe";
  public static final String FUNCTION = "decide";
  public static final String SOURCE = String.join("\n",
      "Module guard.probe.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.", "",
      "@id(\"PROBE-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  If req.action equals to \"delete_customer_record\":",
      "    Return Verdict.require_approval(\"Data Protection Officer\", \"customer record deletion\").",
      "  Otherwise:",
      "    Return Verdict.allow().");

  @Inject PolicyEvaluationService evaluationService;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach @Transactional
  void clean() {
    PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
    PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
    PolicyVersion.delete("tenantId = ?1", TENANT);
    PolicyCatalog.delete("tenantId = ?1", TENANT);
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
  }

  /** 在独立事务中部署目录 + 活跃版本并返回版本 id；同时供 {@code io.aster.guard.api} 的 REST 测试复用。 */
  public static Long deploy(String tenant, String module, String function, String source) {
    return QuarkusTransaction.requiringNew().call(() -> {
      PolicyCatalog catalog = new PolicyCatalog();
      catalog.id = UUID.randomUUID(); catalog.tenantId = tenant; catalog.moduleName = module;
      catalog.functionName = function; catalog.domain = "guard"; catalog.tags = "{}";
      catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
      catalog.persist();
      PolicyVersion version = new PolicyVersion();
      version.policyId = module + "." + function; version.version = System.currentTimeMillis();
      version.moduleName = module; version.functionName = function; version.content = source;
      version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
      version.tenantId = tenant; version.createdAt = Instant.now(); version.createdBy = "probe";
      version.persist();
      catalog.defaultVersionId = version.id; catalog.persist();
      return version.id;
    });
  }

  @Test
  void 规则能读取Map参数成员() {
    deploy(TENANT, MODULE, FUNCTION, SOURCE);
    Map<String, Object> req = Map.of(
        "principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
        "action", "delete_customer_record", "resource_type", "customer", "resource_id", "C-1001",
        "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");
    PolicyEvaluationResult result = evaluationService
        .evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{req}).await().indefinitely();
    assertThat(result.getDecision()).isNotNull();
    assertThat(result.getDecision().outcome()).isEqualTo("REQUIRE_APPROVAL");
    assertThat(result.getDecision().role()).isEqualTo("Data Protection Officer");
    assertThat(result.getRuleId()).isEqualTo("PROBE-001");
  }
}
