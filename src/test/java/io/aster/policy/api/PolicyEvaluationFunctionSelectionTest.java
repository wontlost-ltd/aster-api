package io.aster.policy.api;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /evaluate 路径（PolicyEvaluationService → TrufflePolicyRuntime）同样必须执行所请求的函数。
 *
 * <p>部署记录按 module.function 定位，但 coreJson 是整个模块；运行时此前只 eval 整段 IR，
 * 由 Truffle 按 main → 第一个函数选入口，于是请求第二个函数时拿到第一个函数的结果。
 */
@QuarkusTest
class PolicyEvaluationFunctionSelectionTest {

    static final String TENANT = "tenant-function-selection";
    static final String MODULE = "demo.select";

    static final String SOURCE = String.join("\n",
        "Module demo.select.", "",
        "Rule add2 given a as Int, b as Int, produce Int:",
        "  Return a plus b.", "",
        "Rule twice given a as Int, b as Int, produce Int:",
        "  Return add2(a, b) times 2.");

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

    @Test
    void 请求第二个函数时返回第二个函数的结果() {
        DeployedMapParamProbeTest.deploy(TENANT, MODULE, "twice", SOURCE);

        PolicyEvaluationResult result = evaluationService
            .evaluatePolicy(TENANT, MODULE, "twice", new Object[]{7, 5}).await().indefinitely();

        assertThat(result.getResult()).isEqualTo(24);
    }

    @Test
    void 请求第一个函数时行为不变() {
        DeployedMapParamProbeTest.deploy(TENANT, MODULE, "add2", SOURCE);

        PolicyEvaluationResult result = evaluationService
            .evaluatePolicy(TENANT, MODULE, "add2", new Object[]{7, 5}).await().indefinitely();

        assertThat(result.getResult()).isEqualTo(12);
    }
}
