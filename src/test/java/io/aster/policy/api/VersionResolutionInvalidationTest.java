package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 版本解析在途时发生发版与失效：之后到达的请求必须解析到新版本，不得加入读到旧版本的
 * 在途解析，旧解析结束后也不得把旧版本写回解析缓存。用闩锁把第一次解析卡在「已读到旧版本、
 * 尚未返回」处，确定性地构造这一交错。
 */
@QuarkusTest
@RedisEnabledTest
class VersionResolutionInvalidationTest {

    private static final String MODULE = "invalidation.probe";
    private static final String FUNCTION = "scale";
    private static final String V1 = source(1);
    private static final String V2 = source(2);

    @Inject PolicyEvaluationService service;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @InjectSpy PolicySourceRepository repository;

    private final CountDownLatch staleRead = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private String tenant;

    @BeforeEach
    void holdFirstResolutionAfterRead() {
        tenant = "tenant-invalidate-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(inv -> {
            Object read = inv.callRealMethod();
            if (first.compareAndSet(true, false)) {
                staleRead.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return read;
        }).when(repository).findActiveVersion(any());
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", tenant);
            PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", tenant);
            PolicyVersion.delete("tenantId = ?1", tenant);
            PolicyCatalog.delete("tenantId = ?1", tenant);
        });
        versionResolutionCache.clear();
        compiledPolicyCache.clear();
    }

    @Test
    void 按策略失效后新请求解析到新版本() {
        assertNewRequestSeesNewVersion(t -> service.invalidateCache(t, MODULE, FUNCTION, new Object[]{3})
            .await().indefinitely());
    }

    @Test
    void 按租户批量失效后新请求解析到新版本() {
        assertNewRequestSeesNewVersion(t -> service.invalidateCache(t, MODULE, FUNCTION)
            .await().indefinitely());
    }

    private void assertNewRequestSeesNewVersion(Consumer<String> invalidate) {
        deploy(V1);
        CompletableFuture<PolicyEvaluationResult> stale = evaluate().subscribeAsCompletionStage().toCompletableFuture();
        await(staleRead);

        deploy(V2);
        invalidate.accept(tenant);

        // 旧解析仍卡在闩锁上：若新请求加入它，此处会超时
        assertThat(result(evaluate().await().atMost(Duration.ofSeconds(20)))).isEqualTo(6);

        release.countDown();
        assertThat(result(stale.join())).isEqualTo(3);
        // 旧解析结束后不得把旧版本写回解析缓存
        assertThat(result(evaluate().await().atMost(Duration.ofSeconds(20)))).isEqualTo(6);
    }

    private io.smallrye.mutiny.Uni<PolicyEvaluationResult> evaluate() {
        return service.evaluatePolicy(tenant, MODULE, FUNCTION, new Object[]{3});
    }

    private static int result(PolicyEvaluationResult r) {
        return ((Number) r.getResult()).intValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 新建版本并设为目录的默认版本（首次调用同时建目录）。 */
    private void deploy(String source) {
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = PolicyCatalog.<PolicyCatalog>find(
                "tenantId = ?1 and moduleName = ?2 and functionName = ?3", tenant, MODULE, FUNCTION)
                .firstResultOptional().orElseGet(this::newCatalog);
            PolicyVersion version = new PolicyVersion();
            version.policyId = MODULE + "." + FUNCTION + "." + tenant;
            version.version = System.nanoTime();
            version.moduleName = MODULE; version.functionName = FUNCTION; version.content = source;
            version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
            version.tenantId = tenant; version.createdAt = Instant.now(); version.createdBy = "probe";
            version.persist();
            catalog.defaultVersionId = version.id;
            catalog.updatedAt = Instant.now();
            catalog.persist();
        });
    }

    private PolicyCatalog newCatalog() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.id = UUID.randomUUID(); catalog.tenantId = tenant; catalog.moduleName = MODULE;
        catalog.functionName = FUNCTION; catalog.domain = "test"; catalog.tags = "{}";
        catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
        catalog.persist();
        return catalog;
    }

    private static String source(int factor) {
        return String.join("\n", "Module " + MODULE + ".", "",
            "Rule " + FUNCTION + " given x as Int, produce Int:",
            "  Return x times " + factor + ".", "");
    }
}
