package io.aster.policy.multitenant;

import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.test.PostgresTestResource;
import io.aster.api.workflow.WorkflowStateEntity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 多租户隔离验证：
 * 1. 基本隔离：AuditLogResource 应按 X-Tenant-Id 过滤；workflow/anomaly API 需要同样能力
 * 2. 并发隔离：多租户并发查询互不干扰
 * 3. 异常/边界：缺失 tenant header、恶意 tenant_id（SQL 注入）等
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class MultiTenantIsolationTest {

    // audit_logs 自 V6.22.0 起为数据库层 append-only，
    // 夹具清理须走显式保留期通道（与生产清理任务同一条豁免路径）。
    @jakarta.inject.Inject
    io.aster.test.BlockingDbTestHelper auditDb;

    private static final String TENANT_ALPHA = "tenant-alpha";
    private static final String TENANT_BETA = "tenant-beta";
    private static final String TENANT_GAMMA = "tenant-gamma";
    private static final List<String> TENANTS = List.of(TENANT_ALPHA, TENANT_BETA, TENANT_GAMMA);
    private static final int WORKFLOWS_PER_TENANT = 2;
    private static final int AUDIT_LOGS_PER_TENANT = 3;

    private Long sharedVersionId;
    private Instant baseTime;

    @BeforeEach
    @Transactional
    void setupTenants() {
        // 仅清理本测试创建的数据（按租户和策略模块隔离），保留 V99 种子数据
        TENANTS.forEach(tenant -> {
            auditDb.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id = ?", tenant);
            WorkflowStateEntity.delete("tenantId = ?1", tenant);
            AnomalyReportEntity.delete("tenantId = ?1", tenant);
        });
        // 清理本测试创建的共享策略版本（policyId = policy-shared）及 library 夹具（lib-%）
        io.aster.policy.entity.PolicyArtifact.delete(
            "policyVersionId in (select id from PolicyVersion where policyId = 'policy-shared' or policyId like 'lib-%')");
        PolicyVersion.delete("policyId = ?1", "policy-shared");
        PolicyVersion.delete("policyId like ?1", "lib-%");

        baseTime = Instant.now().minusSeconds(600);
        sharedVersionId = persistSharedPolicyVersion();

        TENANTS.forEach(tenant -> {
            for (int i = 0; i < WORKFLOWS_PER_TENANT; i++) {
                persistWorkflowState(tenant, baseTime.plusSeconds(i * 30L), i % 2 == 0 ? "COMPLETED" : "FAILED");
            }
            for (int i = 0; i < AUDIT_LOGS_PER_TENANT; i++) {
                persistAuditLog(tenant, baseTime.plusSeconds(i * 45L));
            }
            persistAnomalyReport(tenant);
        });
    }

    @Test
    void shouldReturnAuditLogsOnlyForRequestedTenant() {
        List<Map<String, Object>> payload = fetchAuditLogs(TENANT_ALPHA);
        assertFalse(payload.isEmpty(), "测试夹具未插入 tenant-alpha 审计记录");
        assertTrue(payload.stream().allMatch(row -> TENANT_ALPHA.equals(row.get("tenantId"))),
            "不同租户的数据不应出现在当前租户的查询结果中");
    }

    @Test
    void workflowStatsShouldHonorTenantHeaderWithoutExplicitFilter() {
        List<Map<String, Object>> buckets = toMapList(given()
            .header("X-Tenant-Id", TENANT_ALPHA)
            .queryParam("versionId", sharedVersionId)
            .queryParam("granularity", "hour")
            .when()
            .get("/api/v1/audit/stats/version-usage")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("", Map.class));

        assertFalse(buckets.isEmpty(), "版本统计返回为空，无法验证隔离");
        long totalCount = buckets.stream()
            .mapToLong(row -> ((Number) row.get("totalCount")).longValue())
            .sum();

        assertEquals(WORKFLOWS_PER_TENANT, totalCount,
            "header=tenant-alpha 时应只看到 " + WORKFLOWS_PER_TENANT + " 条 workflow，但实际 totalCount="
                + totalCount + "，buckets=" + buckets);
    }

    @Test
    void anomaliesShouldRespectTenantHeader() {
        List<Map<String, Object>> anomalies = toMapList(given()
            .header("X-Tenant-Id", TENANT_BETA)
            .queryParam("size", 50)
            .when()
            .get("/api/v1/audit/anomalies")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("", Map.class));

        assertFalse(anomalies.isEmpty(), "异常列表为空，无法验证隔离");
        List<String> policyIds = anomalies.stream()
            .map(row -> (String) row.get("policyId"))
            .toList();
        assertTrue(policyIds.stream().allMatch(id -> id.startsWith(TENANT_BETA)),
            "异常 API 应按租户过滤，实际返回 policyId=" + policyIds);
    }

    @Test
    void missingTenantHeaderShouldBeRejected() {
        int status = given()
            .when()
            .get("/api/v1/audit")
            .then()
            .extract()
            .statusCode();
        assertEquals(400, status, "缺少租户 header 时应拒绝访问，但实际状态码=" + status);
    }

    @Test
    void sqlInjectionLikeTenantHeaderShouldNotReturnData() {
        String maliciousTenant = TENANT_ALPHA + "' OR '1'='1";
        // 恶意租户 header 应该被拒绝（返回 400），或者返回空结果（200）
        // 两种行为都是安全的，不应返回其他租户的数据
        int status = given()
            .header("X-Tenant-Id", maliciousTenant)
            .when()
            .get("/api/v1/audit")
            .then()
            .extract()
            .statusCode();

        // 接受 400（拒绝恶意输入）或 200（返回空结果）两种安全行为
        if (status == 200) {
            List<Map<String, Object>> payload = toMapList(given()
                .header("X-Tenant-Id", maliciousTenant)
                .when()
                .get("/api/v1/audit")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("", Map.class));
            assertTrue(payload.isEmpty(), "SQL 注入形式的 header 不应匹配任意租户数据");
        } else {
            assertEquals(400, status, "恶意租户 header 应被拒绝（400）或返回空结果（200）");
        }
    }

    @Test
    void concurrentAuditAccessShouldRemainIsolated() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (String tenant : List.of(TENANT_ALPHA, TENANT_BETA)) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        List<Map<String, Object>> payload = fetchAuditLogs(tenant);
                        boolean leak = payload.stream().anyMatch(row -> !tenant.equals(row.get("tenantId")));
                        if (leak) {
                            failure.set(new AssertionError("检测到租户泄露: " + tenant));
                            break;
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "并发读取未在 15 秒内完成");
        executor.shutdownNow();
        if (failure.get() != null) {
            fail(failure.get());
        }
    }

    private List<Map<String, Object>> fetchAuditLogs(String tenantHeader) {
        return toMapList(given()
            .header("X-Tenant-Id", tenantHeader)
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("", Map.class));
    }

    private Long persistSharedPolicyVersion() {
        PolicyVersion version = new PolicyVersion();
        version.policyId = "policy-shared";
        version.version = 1L;
        version.moduleName = "aster.shared";
        version.functionName = "evaluateShared";
        version.content = "// multitenant fixture";
        version.active = true;
        version.createdAt = baseTime;
        version.createdBy = "multitenant-test";
        version.notes = "multi-tenant isolation fixture";
        version.persist();
        return version.id;
    }

    private void persistWorkflowState(String tenant, Instant startedAt, String status) {
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.randomUUID();
        state.status = status;
        state.lastEventSeq = 1L;
        state.startedAt = startedAt;
        state.completedAt = startedAt.plusSeconds(30);
        state.durationMs = 30_000L;
        state.createdAt = startedAt.minusSeconds(60);
        state.updatedAt = startedAt;
        state.policyActivatedAt = startedAt.minusSeconds(120);
        state.policyVersionId = sharedVersionId;
        state.tenantId = tenant;
        state.persist();
    }

    private void persistAuditLog(String tenant, Instant timestamp) {
        AuditLog log = new AuditLog();
        log.eventType = "POLICY_EVALUATION";
        log.timestamp = timestamp;
        log.tenantId = tenant;
        log.policyModule = "aster.shared";
        log.policyFunction = "evaluateShared";
        log.policyId = "policy-" + tenant;
        log.success = Boolean.TRUE;
        log.executionTimeMs = 15L;
        log.reason = "fixture";
        log.persist();
    }

    private void persistAnomalyReport(String tenant) {
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "HIGH_FAILURE_RATE";
        anomaly.policyId = tenant + "-policy";
        anomaly.versionId = sharedVersionId;
        anomaly.severity = "CRITICAL";
        anomaly.description = "multi-tenant fixture for " + tenant;
        anomaly.metricValue = 0.6;
        anomaly.threshold = 0.3;
        anomaly.detectedAt = Instant.now();
        anomaly.status = "PENDING";
        anomaly.tenantId = tenant;  // Phase 4.3: 添加租户 ID 确保隔离
        anomaly.persist();
    }

    // ============================================================
    // 模块目录隔离（ADR 0015 阶段3d）：ModuleCatalogResource 注释声称
    // "严格 tenant 隔离"，此前无测试佐证。补进隔离矩阵。
    // ============================================================

    @Test
    void moduleCatalogShouldReturnOnlyCurrentTenantLibraryModules() {
        seedLibraryModule(TENANT_ALPHA, "alpha.lib.scoring", "score");
        seedLibraryModule(TENANT_BETA, "beta.lib.pricing", "price");

        List<Map<String, Object>> alphaModules = fetchModuleCatalog(TENANT_ALPHA);
        List<String> alphaNames = alphaModules.stream()
            .map(m -> (String) m.get("moduleName")).toList();

        assertTrue(alphaNames.contains("alpha.lib.scoring"),
            "alpha 应能看到自己的 library 模块，实际=" + alphaNames);
        assertFalse(alphaNames.contains("beta.lib.pricing"),
            "alpha 不应看到 beta 的 library 模块（跨租户泄露），实际=" + alphaNames);
    }

    @Test
    void moduleCatalogShouldRejectMissingTenantContext() {
        int status = given()
            .when()
            .get("/api/v1/modules/catalog")
            .then()
            .extract()
            .statusCode();
        // 无 tenant context → 拒绝（不泄露任何租户的模块）
        assertTrue(status == 401 || status == 400,
            "缺失 tenant context 应被拒绝（401/400），实际 status=" + status);
    }

    private List<Map<String, Object>> fetchModuleCatalog(String tenant) {
        Map<String, Object> resp = given()
            .header("X-Tenant-Id", tenant)
            .when()
            .get("/api/v1/modules/catalog")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("");
        Object modules = resp == null ? null : resp.get("modules");
        return modules == null ? List.of() : toMapList((List<?>) modules);
    }

    void seedLibraryModule(String tenant, String moduleName, String functionName) {
        // @Test 内 self-invocation，@Transactional 不生效，用显式事务（项目惯例）
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            PolicyVersion v = new PolicyVersion();
            // 唯一 policyId（lib-{tenant}-{module}）避免与 shared fixture 或彼此撞
            // uk_policy_version UNIQUE(policy_id, version)；清理范围含 lib-%
            v.policyId = "lib-" + tenant + "-" + moduleName;
            v.version = 1L;
            v.moduleName = moduleName;
            v.functionName = functionName;
            v.content = "// library fixture for " + tenant;
            v.active = true;
            v.libraryVisible = true;  // 关键：可作为 library 被 Use 引用
            v.tenantId = tenant;
            v.createdAt = baseTime;
            v.createdBy = "multitenant-test";
            v.notes = "module catalog isolation fixture";
            v.persist();
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(List<?> raw) {
        return (List<Map<String, Object>>) raw;
    }
}
