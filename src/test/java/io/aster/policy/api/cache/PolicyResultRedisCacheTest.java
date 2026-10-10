package io.aster.policy.api.cache;

import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.guard.GuardDecisionEntity;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 缓存后端下，相同评估请求第二次命中 policy-results 缓存时必须能还原结果。
 * 生产默认缓存后端是 Redis，而常规测试强制 Caffeine（对象原样存取），故单独以 Redis profile 覆盖。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class PolicyResultRedisCacheTest {

    private static final String MODULE = DeployedMapParamProbeTest.MODULE;
    private static final String FUNCTION = DeployedMapParamProbeTest.FUNCTION;

    @Inject PolicyEvaluationService evaluationService;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;

    /** 租户带随机后缀，确保缓存键不与其他测试或历史运行冲突。 */
    private String tenant;

    private String deployFreshTenant(String prefix) {
        tenant = prefix + UUID.randomUUID().toString().substring(0, 8);
        DeployedMapParamProbeTest.deploy(tenant, MODULE, FUNCTION, DeployedMapParamProbeTest.SOURCE);
        return tenant;
    }

    @AfterEach
    void clean() {
        String t = tenant;
        QuarkusTransaction.requiringNew().run(() -> {
            GuardDecisionEntity.delete("tenantId = ?1", t);
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", t);
            PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", t);
            PolicyVersion.delete("tenantId = ?1", t);
            PolicyCatalog.delete("tenantId = ?1", t);
        });
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
    }

    @Test
    void 服务层相同请求第二次命中Redis缓存仍返回ALLOW() {
        String t = deployFreshTenant("tenant-redis-cache-svc-");
        Map<String, Object> req = Map.of(
            "principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
            "action", "read_customer_record", "resource_type", "customer", "resource_id", "C-1001",
            "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");

        PolicyEvaluationResult first = evaluate(t, req);
        PolicyEvaluationResult second = evaluate(t, req);

        assertThat(first.getDecision()).isNotNull();
        assertThat(first.getDecision().outcome()).isEqualTo("ALLOW");
        assertThat(second.getDecision()).isNotNull();
        assertThat(second.getDecision().outcome()).isEqualTo("ALLOW");
        assertThat(second.getResult()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) second.getResult()).get("__type")).isEqualTo("Verdict");
        assertThat(second.isFromCache()).isTrue();
    }

    @Test
    void Guard决策相同请求第二次仍为ALLOW() {
        String t = deployFreshTenant("tenant-redis-cache-guard-");
        Map<String, Object> body = Map.of(
            "policyModule", MODULE,
            "policyFunction", FUNCTION,
            "action", Map.of(
                "principal", Map.of("id", "agent-42", "type", "agent", "roles", List.of("ops-bot")),
                "action", Map.of("name", "read_customer_record"),
                "resource", Map.of("type", "customer", "id", "C-1001"),
                "context", Map.of("amount", 250000),
                "agent", Map.of("model", "claude-fable-5-1", "session", "s-1")));

        Response first = postDecision(t, body);
        Response second = postDecision(t, body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
        assertThat(second.jsonPath().getMap("$")).doesNotContainKey("reasonCode");
    }

    /** 档案（ADR 0046 §6）须随 Redis 缓存值往返：命中缓存的结果仍带 profile。 */
    @Test
    void 声明档案的策略命中Redis缓存仍带profile() {
        tenant = "tenant-redis-cache-profile-" + UUID.randomUUID().toString().substring(0, 8);
        String source = DeployedMapParamProbeTest.SOURCE
            .replace("Module guard.probe.", "Module guard.probe.\nProfile \"governed\".");
        DeployedMapParamProbeTest.deploy(tenant, MODULE, FUNCTION, source);
        Map<String, Object> req = Map.of(
            "principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
            "action", "read_customer_record", "resource_type", "customer", "resource_id", "C-1001",
            "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");

        PolicyEvaluationResult first = evaluate(tenant, req);
        PolicyEvaluationResult second = evaluate(tenant, req);

        assertThat(first.getProfile()).isEqualTo("governed");
        assertThat(second.isFromCache()).isTrue();
        assertThat(second.getProfile()).isEqualTo("governed");
        assertThat(second.getRuleId()).isEqualTo("PROBE-001");
    }

    private PolicyEvaluationResult evaluate(String t, Map<String, Object> req) {
        return evaluationService.evaluatePolicy(t, MODULE, FUNCTION, new Object[]{req})
            .await().atMost(Duration.ofSeconds(30));
    }

    private static Response postDecision(String t, Map<String, Object> body) {
        return given()
            .header("X-Tenant-Id", t)
            .header("X-User-Id", "user-redis-cache")
            .contentType(ContentType.JSON)
            .body(body)
            .when().post("/api/v1/guard/decisions");
    }
}
