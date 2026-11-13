package io.aster.policy.integration;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态策略加载集成测试
 *
 * 验证完整流程：数据库加载 → 编译 → 执行 → 缓存
 * 测试 Feature Flag 切换和回退机制
 *
 * 注意：这些测试需要以下条件：
 * 1. 启用动态加载 Feature Flag: aster.policy.dynamic-loading.enabled=true
 * 2. 配置外部数据库连接（Prisma PostgreSQL）
 * 3. 数据库中存在测试数据或使用 @Transactional 创建测试数据
 *
 * 如果测试环境不满足上述条件，测试将被跳过或使用 classpath 回退机制。
 */
@QuarkusTest
class DynamicPolicyExecutionTest {

    @Inject
    PolicyEvaluationService policyEvaluationService;

    @Inject
    PolicySourceRepository policySourceRepository;

    @Inject
    CompiledPolicyCache compiledPolicyCache;

    @Inject
    io.aster.policy.cache.PolicyVersionResolutionCache versionResolutionCache;

    @ConfigProperty(name = "aster.policy.dynamic-loading.enabled", defaultValue = "false")
    boolean dynamicLoadingEnabled;

    private static final String TEST_TENANT = "dynamic-policy-test-tenant";
    private static final String TEST_MODULE = "aster.dynamic.test";
    private static final String TEST_FUNCTION = "dynamicTestFunction";

    @BeforeEach
    @Transactional
    void setUp() {
        // 仅清理本测试创建的数据（按租户隔离），保留 V99 种子数据
        // 1. 先清除测试租户的 PolicyCatalog 的 defaultVersionId 外键引用
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TEST_TENANT);
        // 2. 删除测试租户的 PolicyArtifact（通过关联 PolicyVersion）
        PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TEST_TENANT);
        // 3. 删除测试租户的 PolicyVersion
        PolicyVersion.delete("tenantId = ?1", TEST_TENANT);
        // 4. 最后删除测试租户的 PolicyCatalog
        PolicyCatalog.delete("tenantId = ?1", TEST_TENANT);

        // 清空编译缓存 + 版本解析缓存（后者短 TTL，跨测试方法会服务陈旧 versionId
        // 导致用例间污染——清掉确保每个测试从干净状态解析新建的 version）。
        compiledPolicyCache.clear();
        versionResolutionCache.clear();
    }

    /**
     * 测试动态执行：数据库加载 → 编译 → 执行
     *
     * 注意：PolicyEvaluationService 使用 REQUIRES_NEW 事务，
     * 因此测试数据必须先提交才能被策略评估服务看到。
     */
    @Test
    void testDynamicExecution() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 在独立事务中准备测试数据，确保数据先提交
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = createTestCatalog();
            catalog.persist();

            PolicyVersion version = createTestVersion(catalog.id);
            version.persist();

            PolicyArtifact artifact = createTestArtifact(version.id);
            artifact.persist();

            // 激活版本
            catalog.defaultVersionId = version.id;
            catalog.persist();
        });

        // When: 执行策略（数据已提交，策略评估服务可以在其 REQUIRES_NEW 事务中看到）
        PolicyEvaluationResult result = policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证结果
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isNotNull();
        assertThat(result.getExecutionTimeMs()).isGreaterThan(0);
    }

    /**
     * 测试缓存机制：首次编译并缓存，后续直接使用缓存
     *
     * 注意：PolicyEvaluationService 使用 REQUIRES_NEW 事务，
     * 因此测试数据必须先提交才能被策略评估服务看到。
     */
    @Test
    void testCachingMechanism() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 在独立事务中准备测试数据，确保数据先提交
        AtomicReference<String> versionIdRef = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = createTestCatalog();
            catalog.persist();

            PolicyVersion version = createTestVersion(catalog.id);
            version.persist();

            PolicyArtifact artifact = createTestArtifact(version.id);
            artifact.persist();

            catalog.defaultVersionId = version.id;
            catalog.persist();

            versionIdRef.set(String.valueOf(version.id));
        });

        String versionId = versionIdRef.get();

        // When: 首次执行（应该编译并缓存）
        PolicyEvaluationResult result1 = policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证缓存已创建
        Optional<io.aster.policy.cache.CompiledPolicy> cached = compiledPolicyCache.get(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            versionId
        );
        assertThat(cached).isPresent();
        assertThat(cached.get().getVersionId()).isEqualTo(versionId);

        // When: 第二次执行（应该使用缓存）
        PolicyEvaluationResult result2 = policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证两次结果一致
        assertThat(result2).isNotNull();
        assertThat(result2.getResult()).isEqualTo(result1.getResult());
    }

    /**
     * 测试策略未找到时的错误处理
     *
     * 注意：系统设计为从数据库加载策略，不存在 classpath 回退机制。
     * 当数据库中找不到策略时，应该抛出明确的错误。
     *
     * 不使用 @Transactional，因为测试期望抛出异常，
     * 在事务环境中异常会导致不必要的回滚错误。
     */
    @Test
    void testPolicyNotFoundError() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 不创建任何测试数据（@BeforeEach 已清空数据库）

        // When/Then: 执行策略时应该抛出明确的错误
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> {
            policyEvaluationService.evaluatePolicy(
                "nonexistent-tenant",
                "aster.nonexistent.module",
                "nonexistentFunction",
                new Object[]{1, 2}
            ).await().indefinitely();
        }, "策略未找到时应该抛出 RuntimeException");
    }

    /**
     * 测试缓存失效：版本更新后缓存应该失效
     *
     * 注意：PolicyEvaluationService 使用 REQUIRES_NEW 事务，
     * 因此测试数据必须先提交才能被策略评估服务看到。
     */
    @Test
    void testCacheInvalidation() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 在独立事务中准备测试数据，确保数据先提交
        AtomicReference<String> versionIdRef = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            PolicyCatalog catalog = createTestCatalog();
            catalog.persist();

            PolicyVersion version1 = createTestVersion(catalog.id);
            version1.persist();

            PolicyArtifact artifact1 = createTestArtifact(version1.id);
            artifact1.persist();

            catalog.defaultVersionId = version1.id;
            catalog.persist();

            versionIdRef.set(String.valueOf(version1.id));
        });

        String versionId1 = versionIdRef.get();

        // When: 首次执行（缓存版本1）
        policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证缓存存在
        assertThat(compiledPolicyCache.get(TEST_TENANT, TEST_MODULE, TEST_FUNCTION, versionId1))
            .isPresent();

        // When: 失效缓存
        compiledPolicyCache.invalidate(TEST_TENANT, TEST_MODULE, TEST_FUNCTION);

        // Then: 验证缓存已失效
        assertThat(compiledPolicyCache.get(TEST_TENANT, TEST_MODULE, TEST_FUNCTION, versionId1))
            .isEmpty();
    }

    /**
     * 创建测试用的 PolicyCatalog
     */
    private PolicyCatalog createTestCatalog() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.id = UUID.randomUUID();
        catalog.tenantId = TEST_TENANT;
        catalog.moduleName = TEST_MODULE;
        catalog.functionName = TEST_FUNCTION;
        catalog.domain = "test";
        catalog.tags = "{}";
        catalog.createdAt = Instant.now();
        catalog.updatedAt = Instant.now();
        return catalog;
    }

    /**
     * 创建测试用的 PolicyVersion
     */
    private PolicyVersion createTestVersion(UUID catalogId) {
        PolicyVersion version = new PolicyVersion();
        version.policyId = TEST_MODULE + "." + TEST_FUNCTION;
        version.version = System.currentTimeMillis();
        version.moduleName = TEST_MODULE;
        version.functionName = TEST_FUNCTION;
        version.content = "test content";
        version.active = true;
        version.status = VersionStatus.APPROVED;
        version.sourceHash = "test-hash-" + System.currentTimeMillis();
        version.tenantId = TEST_TENANT;
        version.createdAt = Instant.now();
        return version;
    }

    /**
     * 创建测试用的 PolicyArtifact（包含简单的 Core JSON）
     *
     * Core JSON 格式遵循 aster-lang-truffle 的 CoreModel.java 定义：
     * - Module: { name: string, decls: Decl[] }
     * - Func: { kind: "Func", name, params, ret, body }
     * - Body: { kind: "Block", statements: Stmt[] }（core 的 CoreModel 要求类型标记，规则元数据按它解析）
     * - Return: { kind: "Return", expr: Expr }
     * - Bool: { kind: "Bool", value: boolean }
     */
    private PolicyArtifact createTestArtifact(Long versionId) {
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = UUID.randomUUID();
        artifact.policyVersionId = versionId;
        artifact.artifactType = "CORE_JSON";

        // 创建符合 CoreModel.Module 格式的 Core JSON
        String coreJson = """
            {
              "name": "%s",
              "decls": [
                {
                  "kind": "Func",
                  "name": "%s",
                  "params": [
                    {"name": "amount", "type": {"kind": "TypeName", "name": "Int"}},
                    {"name": "term", "type": {"kind": "TypeName", "name": "Int"}}
                  ],
                  "ret": {"kind": "TypeName", "name": "Bool"},
                  "body": {
                    "kind": "Block",
                    "statements": [
                      {
                        "kind": "Return",
                        "expr": {"kind": "Bool", "value": true}
                      }
                    ]
                  }
                }
              ]
            }
            """.formatted(TEST_MODULE, TEST_FUNCTION);

        artifact.content = coreJson.getBytes(StandardCharsets.UTF_8);
        artifact.contentSha256 = "test-sha256";
        artifact.compilerOpts = "{\"functionSignature\":\"" + TEST_FUNCTION + "(amount: Int, term: Int): Bool\"}";
        artifact.createdAt = Instant.now();
        return artifact;
    }
}
