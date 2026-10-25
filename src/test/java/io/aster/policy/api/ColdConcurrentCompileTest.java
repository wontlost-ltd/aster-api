package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * issue #392：新副本上多个租户的不同版本同时首次评估（冷缓存、门禁开启、8 连接池）必须全部成功。
 *
 * <p>此前加载 + 门禁类型检查 + 编译整段包在一个 requiringNew 事务里，2–3s 的 CPU 密集编译期间一直
 * 占着连接，提交时出现 "Enlisted connection used without active transaction" → 500。修复后 DB 读取
 * 在短事务内完成，类型检查与编译在任何事务之外执行——下面用 spy 在编译入口记录当时的事务状态来锁定这一点。
 */
@QuarkusTest
@TestProfile(ColdConcurrentCompileTest.ProdLikeProfile.class)
class ColdConcurrentCompileTest {

    /** 与生产一致：门禁开启、默认 8 连接池、小 worker 核心线程数；随机端口避免与并行测试冲突。 */
    public static class ProdLikeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.compile.gate", "on",
                "quarkus.http.test-port", "0",
                "quarkus.datasource.jdbc.max-size", "8",
                "quarkus.datasource.jdbc.min-size", "0",
                "quarkus.thread-pool.core-threads", "4",
                "quarkus.thread-pool.max-threads", "32",
                "quarkus.thread-pool.queue-size", "512");
        }
    }

    static final String TENANT_PREFIX = "tenant-cold-compile-";
    static final String MODULE = "demo.cold";
    static final String FUNCTION = "decide";
    static final int VERSIONS = 5;
    static final int REQUESTS_PER_VERSION = 12;
    /** 人为放慢编译，模拟 0.5 CPU 下的冷编译，确保各版本的编译窗口相互重叠。 */
    static final long SLOW_COMPILE_MS = 400;

    static final String SOURCE = String.join("\n",
        "Module demo.cold.", "",
        "Rule decide given a as Int, b as Int, produce Int:",
        "  Return a plus b.");

    @Inject PolicyEvaluationService service;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @Inject TransactionManager transactionManager;
    @InjectSpy PolicyCompiler policyCompiler;

    /** CPU 密集段（门禁类型检查、源码编译）被调用时所在线程的事务状态。 */
    final ConcurrentLinkedQueue<Integer> compileTxStatuses = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void seedColdVersions() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < VERSIONS; i++) {
                String tenant = TENANT_PREFIX + i;
                PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
                PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
                PolicyVersion.delete("tenantId = ?1", tenant);
                PolicyCatalog.delete("tenantId = ?1", tenant);
            }
        });
        service.clearAllCache().await().indefinitely();
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
        for (int i = 0; i < VERSIONS; i++) {
            deploy(TENANT_PREFIX + i);
        }
        compileTxStatuses.clear();
        Mockito.doAnswer(inv -> recordAndSlow(inv)).when(policyCompiler).typecheckVersion(any());
        Mockito.doAnswer(inv -> recordAndSlow(inv)).when(policyCompiler).compile(anyString(), any(), any());
    }

    private Object recordAndSlow(org.mockito.invocation.InvocationOnMock inv) throws Throwable {
        compileTxStatuses.add(transactionManager.getStatus());
        Thread.sleep(SLOW_COMPILE_MS);
        return inv.callRealMethod();
    }

    static void deploy(String tenant) {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = new PolicyCatalog();
            catalog.id = UUID.randomUUID(); catalog.tenantId = tenant; catalog.moduleName = MODULE;
            catalog.functionName = FUNCTION; catalog.domain = "test"; catalog.tags = "{}";
            catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
            catalog.persist();
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + "." + FUNCTION; version.version = System.nanoTime();
            version.moduleName = MODULE; version.functionName = FUNCTION; version.content = SOURCE;
            version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
            version.tenantId = tenant; version.createdAt = Instant.now(); version.createdBy = "cold-test";
            version.persist();
            catalog.defaultVersionId = version.id; catalog.persist();
        });
    }

    @Test
    void 冷缓存下多个版本并发首次评估全部200() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(VERSIONS * REQUESTS_PER_VERSION);
        List<Future<String>> responses = new ArrayList<>();
        try {
            for (int r = 0; r < REQUESTS_PER_VERSION; r++) {
                for (int i = 0; i < VERSIONS; i++) {
                    responses.add(pool.submit(evaluateOverHttp(TENANT_PREFIX + i, r, i)));
                }
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> response : responses) {
                outcomes.add(response.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).allMatch(o -> o.startsWith("200 "), "全部 200");
        } finally {
            pool.shutdownNow();
        }
        // 每个版本至少一次类型检查 + 一次编译，且全部发生在事务之外（不占连接）
        assertThat(compileTxStatuses).hasSizeGreaterThanOrEqualTo(VERSIONS * 2)
            .allMatch(status -> status == Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void 类型检查与编译不在任何事务内执行() {
        PolicyEvaluationResult result = service
            .evaluatePolicy(TENANT_PREFIX + 0, MODULE, FUNCTION, new Object[]{2, 3}).await().indefinitely();

        assertThat(result.getResult()).isEqualTo(5);
        assertThat(compileTxStatuses).isNotEmpty().allMatch(status -> status == Status.STATUS_NO_TRANSACTION);
    }

    private static java.util.concurrent.Callable<String> evaluateOverHttp(String tenant, int a, int b) {
        String body = "{\"policyModule\":\"" + MODULE + "\",\"policyFunction\":\"" + FUNCTION
            + "\",\"context\":[" + a + "," + b + "]}";
        return () -> {
            var response = RestAssured.given().header("X-Tenant-Id", tenant)
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/policies/evaluate");
            return response.statusCode() + " " + response.asString();
        };
    }
}
