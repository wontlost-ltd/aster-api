package io.aster.policy.api;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.aster.policy.api.PolicyEvaluationServiceRuleMetadataTest.FUNCTION;
import static io.aster.policy.api.PolicyEvaluationServiceRuleMetadataTest.MODULE;
import static io.aster.policy.api.PolicyEvaluationServiceRuleMetadataTest.request;
import static io.aster.policy.api.PolicyEvaluationServiceRuleMetadataTest.source;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态加载关闭时（{@code aster.policy.dynamic-loading.enabled=false}）按版本的入口忽略传入的 versionId，
 * 元数据与评估都走无版本路径，结果与引入版本固定之前相同（总是活跃版本）。独立的测试 profile 需要单独的类。
 */
@QuarkusTest
@TestProfile(PolicyEvaluationServiceRuleMetadataNoDynamicTest.NoDynamicLoading.class)
class PolicyEvaluationServiceRuleMetadataNoDynamicTest {
  static final String TENANT = "tenant-rule-metadata-nodyn";

  public static class NoDynamicLoading implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("aster.policy.dynamic-loading.enabled", "false");
    }
  }

  @Inject PolicyEvaluationService service;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach
  void clean() {
    PolicyEvaluationServiceRuleMetadataTest.clean(TENANT);
    service.clearAllCache().await().indefinitely();
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
  }

  @Test
  void dynamicLoadingDisabledUsesNullVersion() {
    Long first = DeployedMapParamProbeTest.deploy(TENANT, MODULE, FUNCTION, source("NODYN-A"));
    Long second = PolicyEvaluationServiceRuleMetadataTest.activateNewVersion(TENANT, MODULE, FUNCTION,
        source("NODYN-B"));

    // 传入旧版本 id 也读活跃版本：与 3 参入口、与 evaluatePolicy 结果相同
    assertThat(service.ruleMetadata(TENANT, MODULE, FUNCTION, String.valueOf(first)).ruleId()).isEqualTo("NODYN-B");
    assertThat(service.ruleMetadata(TENANT, MODULE, FUNCTION).ruleId()).isEqualTo("NODYN-B");
    PolicyEvaluationResult atVersion = service.evaluatePolicyAtVersion(TENANT, MODULE, FUNCTION,
        String.valueOf(first), new Object[]{request()}).await().indefinitely();
    PolicyEvaluationResult plain = service.evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{request()})
        .await().indefinitely();
    assertThat(atVersion.getRuleId()).isEqualTo("NODYN-B").isEqualTo(plain.getRuleId());
    assertThat(atVersion.getPolicyVersionId()).isEqualTo(second).isEqualTo(plain.getPolicyVersionId());
    assertThat(atVersion.getDecision()).isEqualTo(plain.getDecision());
  }
}
