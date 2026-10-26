package io.aster.policy.cache;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.health.CompileWarmupReadinessCheck;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编译缓存启动预热（issue #392）：预热后首个请求不再编译；数量与时长有界；readiness 等预热完成或超时。
 */
@QuarkusTest
@TestProfile(CompiledPolicyWarmupTest.WarmupProfile.class)
class CompiledPolicyWarmupTest {

    /** 启动时不自动预热（数据尚未就绪），由测试手动触发同一实例的同步预热。 */
    public static class WarmupProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("aster.policy.compile-warmup.enabled", "false",
                "aster.policy.compile-warmup.max-versions", "1000");
        }
    }

    static final String TENANT = "tenant-compile-warmup";
    static final String MODULE = "demo.warmup";
    static final String SOURCE = String.join("\n",
        "Module demo.warmup.", "",
        "Rule decide given a as Int, b as Int, produce Int:",
        "  Return a times b.");

    @Inject CompiledPolicyWarmup warmup;
    @Inject PolicyEvaluationService service;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @InjectSpy PolicyCompiler policyCompiler;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
            PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
            PolicyVersion.delete("tenantId = ?1", TENANT);
            PolicyCatalog.delete("tenantId = ?1", TENANT);
            PolicyCatalog catalog = new PolicyCatalog();
            catalog.id = UUID.randomUUID(); catalog.tenantId = TENANT; catalog.moduleName = MODULE;
            catalog.functionName = "decide"; catalog.domain = "test"; catalog.tags = "{}";
            catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
            catalog.persist();
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + ".decide"; version.version = System.nanoTime();
            version.moduleName = MODULE; version.functionName = "decide"; version.content = SOURCE;
            version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
            version.tenantId = TENANT; version.createdAt = Instant.now(); version.createdBy = "warmup-test";
            version.activatedAt = Instant.now().plusSeconds(3600);
            version.persist();
            catalog.defaultVersionId = version.id; catalog.persist();
        });
        service.clearAllCache().await().indefinitely();
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
    }

    @Test
    void 预热后首次评估命中编译缓存不再编译() {
        int warmed = warmup.warmUp();
        assertThat(warmed).isGreaterThanOrEqualTo(1);
        assertThat(warmup.isReady()).isTrue();
        Mockito.clearInvocations(policyCompiler);

        PolicyEvaluationResult result = service
            .evaluatePolicy(TENANT, MODULE, "decide", new Object[]{6, 7}).await().indefinitely();

        assertThat(result.getResult()).isEqualTo(42);
        verify(policyCompiler, never()).compile(anyLong(), any());
        verify(policyCompiler, never()).typecheckVersion(any());
    }

    // ---- 纯单元：有界性与 readiness，不依赖数据库 ----

    private static CompiledPolicyWarmup unit(List<PolicyVersion> candidates, PolicyEvaluationService evaluation,
                                             int maxVersions, Duration maxDuration) {
        CompiledPolicyWarmup w = new CompiledPolicyWarmup();
        w.repository = mock(PolicySourceRepository.class);
        when(w.repository.findDefaultActiveVersions(maxVersions)).thenReturn(candidates);
        w.evaluationService = evaluation;
        w.enabled = true;
        w.maxVersions = maxVersions;
        w.maxDuration = maxDuration;
        return w;
    }

    private static PolicyVersion version(long id) {
        PolicyVersion v = new PolicyVersion();
        v.id = id; v.tenantId = "t"; v.moduleName = "m"; v.functionName = "f";
        return v;
    }

    @Test
    void 单个版本失败不影响其余版本且只查询上限条数() {
        PolicyEvaluationService evaluation = mock(PolicyEvaluationService.class);
        PolicyVersion bad = version(1);
        PolicyVersion good = version(2);
        when(evaluation.warmUpCompiled(bad)).thenReturn(Uni.createFrom().failure(new IllegalStateException("编译失败")));
        when(evaluation.warmUpCompiled(good)).thenReturn(Uni.createFrom().voidItem());
        CompiledPolicyWarmup w = unit(List.of(bad, good), evaluation, 2, Duration.ofSeconds(5));

        assertThat(w.warmUp()).isEqualTo(1);
        assertThat(w.isReady()).isTrue();
        verify(w.repository, times(1)).findDefaultActiveVersions(2);
        // 幂等：第二次调用不再预热
        assertThat(w.warmUp()).isEqualTo(1);
        verify(evaluation, times(1)).warmUpCompiled(good);
    }

    @Test
    void 编译卡住时按时限放弃并报告就绪() {
        PolicyEvaluationService evaluation = mock(PolicyEvaluationService.class);
        when(evaluation.warmUpCompiled(any())).thenReturn(Uni.createFrom().nothing());
        CompiledPolicyWarmup w = unit(List.of(version(1), version(2), version(3)), evaluation, 3, Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThat(w.warmUp()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(w.isReady()).isTrue();
        // 第一个卡满时限后，其余版本不再尝试
        verify(evaluation, times(1)).warmUpCompiled(any());
    }

    @Test
    void 预热进行中报告未就绪_完成后就绪() throws Exception {
        PolicyEvaluationService evaluation = mock(PolicyEvaluationService.class);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(evaluation.warmUpCompiled(any())).thenReturn(Uni.createFrom().voidItem()
            .onItem().invoke(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        CompiledPolicyWarmup w = unit(List.of(version(1)), evaluation, 1, Duration.ofSeconds(30));
        CompileWarmupReadinessCheck check = new CompileWarmupReadinessCheck(w);
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);

        Thread runner = new Thread(w::warmUp);
        runner.start();
        waitUntil(() -> !w.isReady());
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);

        release.countDown();
        runner.join(5000);
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(w.warmedCount()).isEqualTo(1);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
