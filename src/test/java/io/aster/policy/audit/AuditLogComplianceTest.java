package io.aster.policy.audit;

import io.aster.policy.common.PIIRedactor;
import io.aster.policy.entity.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * 审计日志合规性测试
 *
 * 验证审计日志系统满足以下合规要求：
 * 1. 所有策略操作都生成审计日志记录
 * 2. PII 在数据库中被正确脱敏
 * 3. 查询 API 支持多租户隔离
 * 4. 日志记录包含完整的审计信息
 */
@QuarkusTest
class AuditLogComplianceTest {

    @Inject
    AuditLogger auditLogger;

    private final PIIRedactor piiRedactor = new PIIRedactor();

    @jakarta.inject.Inject
    io.aster.test.BlockingDbTestHelper db;

    @BeforeEach
    void setUp() {
        // 清理测试数据。
        //
        // ★不能再用 AuditLog.deleteAll()：V6.22.0 起 audit_logs 是数据库层
        //   append-only，常规 DELETE（含 Panache 的 deleteAll）会被触发器拒绝。
        //   夹具清理必须像生产的保留期清理任务一样**显式声明意图**，
        //   走同一条豁免通道——这样测试与生产跑在同一套数据库语义上，
        //   append-only 保护本身才是可测的。
        db.executeAsAuditMaintenance("DELETE FROM audit_logs");
    }

    /**
     * 测试1：验证策略评估操作生成审计日志
     */
    @Test
    void testPolicyEvaluationLogging() {
        // Given: 执行策略评估
        String policyModule = "aster.finance.loan";
        String policyFunction = "evaluateLoanEligibility";
        String tenantId = "tenant-123";
        long executionTimeMs = 42;
        boolean success = true;

        // When: 记录策略评估日志
        auditLogger.logPolicyEvaluation(policyModule, policyFunction, tenantId, executionTimeMs, success);

        // Then: 验证数据库中存在审计日志记录
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.eventType).isEqualTo("POLICY_EVALUATION");
        assertThat(log.policyModule).isEqualTo(policyModule);
        assertThat(log.policyFunction).isEqualTo(policyFunction);
        assertThat(log.tenantId).isEqualTo(tenantId);
        assertThat(log.executionTimeMs).isEqualTo(executionTimeMs);
        assertThat(log.success).isTrue();
        assertThat(log.timestamp).isNotNull();
    }

    /**
     * 测试2：验证策略创建操作生成审计日志
     */
    @Test
    void testPolicyCreationLogging() {
        // Given: 策略创建参数
        String policyId = "policy-456";
        Long version = 1L;
        String moduleName = "aster.finance.risk";
        String functionName = "assessCreditRisk";
        String tenantId = "tenant-456";
        String createdBy = "admin@example.com";

        // When: 记录策略创建日志
        auditLogger.logPolicyCreation(policyId, version, moduleName, functionName, tenantId, createdBy);

        // Then: 验证数据库中存在审计日志记录
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.eventType).isEqualTo("POLICY_CREATED");
        assertThat(log.policyId).isEqualTo(policyId);
        assertThat(log.toVersion).isEqualTo(version);
        assertThat(log.policyModule).isEqualTo(moduleName);
        assertThat(log.policyFunction).isEqualTo(functionName);
        assertThat(log.tenantId).isEqualTo(tenantId);
        // 验证 PII 脱敏
        assertThat(log.performedBy).isEqualTo("***@***.***");
    }

    /**
     * 测试3：验证策略回滚操作生成审计日志
     */
    @Test
    void testPolicyRollbackLogging() {
        // Given: 策略回滚参数
        String policyId = "policy-789";
        Long fromVersion = 3L;
        Long toVersion = 2L;
        String tenantId = "tenant-789";
        String performedBy = "operator-123-45-6789"; // 包含 SSN 格式
        String reason = "生产环境发现严重bug，紧急回滚";

        // When: 记录策略回滚日志
        auditLogger.logRollback(policyId, fromVersion, toVersion, tenantId, performedBy, reason);

        // Then: 验证数据库中存在审计日志记录
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.eventType).isEqualTo("POLICY_ROLLBACK");
        assertThat(log.policyId).isEqualTo(policyId);
        assertThat(log.fromVersion).isEqualTo(fromVersion);
        assertThat(log.toVersion).isEqualTo(toVersion);
        assertThat(log.tenantId).isEqualTo(tenantId);
        // 验证 PII 脱敏（performedBy 包含 SSN 格式）
        assertThat(log.performedBy).isEqualTo("operator-***-**-****");
        assertThat(log.reason).isEqualTo(reason);
    }

    /**
     * 测试4：验证 PII 在数据库中被正确脱敏
     */
    @Test
    void testPIIRedactionInDatabase() {
        // Given: 包含各种 PII 的审计日志参数
        String policyId = "policy-123";
        String tenantId = "tenant-456";
        String createdBy = "user@example.com"; // Email
        String moduleName = "module-192.168.1.1"; // 包含 IP 地址

        // When: 记录策略创建日志
        auditLogger.logPolicyCreation(policyId, 1L, moduleName, "func", tenantId, createdBy);

        // Then: 验证 PII 被正确脱敏
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        // Email 脱敏
        assertThat(log.performedBy).isEqualTo("***@***.***");
        // IP 脱敏
        assertThat(log.policyModule).contains("***.***.***.***");
        // 非 PII 数据保持不变
        assertThat(log.policyId).isEqualTo(policyId);
        assertThat(log.tenantId).isEqualTo(tenantId);
    }

    /**
     * 测试5：验证查询 API - 按租户查询
     */
    @Test
    void testQueryByTenant() {
        // Given: 为两个不同租户创建审计日志
        auditLogger.logPolicyEvaluation("module1", "func1", "tenant-A", 10, true);
        auditLogger.logPolicyEvaluation("module2", "func2", "tenant-A", 20, true);
        auditLogger.logPolicyEvaluation("module3", "func3", "tenant-B", 30, true);

        // When & Then: 查询租户 A 的日志
        given()
            .header("X-Tenant-Id", "tenant-A")
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", equalTo(2))
            .body("eventType", everyItem(equalTo("POLICY_EVALUATION")))
            .body("tenantId", everyItem(equalTo("tenant-A")));

        // 验证租户 B 只能看到自己的日志
        given()
            .header("X-Tenant-Id", "tenant-B")
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", equalTo(1))
            .body("[0].tenantId", equalTo("tenant-B"));
    }

    /**
     * 测试6：验证查询 API - 按事件类型查询
     */
    @Test
    void testQueryByEventType() {
        // Given: 创建不同类型的审计日志
        String tenantId = "tenant-test";
        auditLogger.logPolicyEvaluation("module1", "func1", tenantId, 10, true);
        auditLogger.logPolicyCreation("policy1", 1L, "module2", "func2", tenantId, "user1");
        auditLogger.logRollback("policy2", 2L, 1L, tenantId, "user2", "回滚原因");

        // When & Then: 查询 POLICY_EVALUATION 类型
        given()
            .header("X-Tenant-Id", tenantId)
            .when()
            .get("/api/v1/audit/type/POLICY_EVALUATION")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", equalTo(1))
            .body("[0].eventType", equalTo("POLICY_EVALUATION"));

        // 查询 POLICY_CREATED 类型
        given()
            .header("X-Tenant-Id", tenantId)
            .when()
            .get("/api/v1/audit/type/POLICY_CREATED")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].eventType", equalTo("POLICY_CREATED"));

        // 查询 POLICY_ROLLBACK 类型
        given()
            .header("X-Tenant-Id", tenantId)
            .when()
            .get("/api/v1/audit/type/POLICY_ROLLBACK")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].eventType", equalTo("POLICY_ROLLBACK"));
    }

    /**
     * 测试7：验证查询 API - 按策略查询
     */
    @Test
    void testQueryByPolicy() {
        // Given: 创建针对特定策略的审计日志
        String tenantId = "tenant-policy-test";
        String policyModule = "aster.finance.loan";
        String policyFunction = "evaluateLoanEligibility";

        auditLogger.logPolicyEvaluation(policyModule, policyFunction, tenantId, 10, true);
        auditLogger.logPolicyEvaluation(policyModule, policyFunction, tenantId, 20, true);
        auditLogger.logPolicyEvaluation("other.module", "otherFunction", tenantId, 30, true);

        // When & Then: 查询特定策略的日志
        given()
            .header("X-Tenant-Id", tenantId)
            .when()
            .get("/api/v1/audit/policy/" + policyModule + "/" + policyFunction)
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", equalTo(2))
            .body("policyModule", everyItem(equalTo(policyModule)))
            .body("policyFunction", everyItem(equalTo(policyFunction)));
    }

    /**
     * 测试8：验证查询 API - 按时间范围查询
     */
    @Test
    void testQueryByTimeRange() throws InterruptedException {
        // Given: 创建不同时间的审计日志
        String tenantId = "tenant-time-test";

        Instant startTime = Instant.now();
        auditLogger.logPolicyEvaluation("module1", "func1", tenantId, 10, true);

        Thread.sleep(100); // 确保时间差异

        Instant midTime = Instant.now();
        auditLogger.logPolicyEvaluation("module2", "func2", tenantId, 20, true);

        Thread.sleep(100);

        auditLogger.logPolicyEvaluation("module3", "func3", tenantId, 30, true);

        Thread.sleep(50); // 确保最后一条日志也被创建

        Instant endTime = Instant.now();

        // When & Then: 查询整个时间范围
        given()
            .header("X-Tenant-Id", tenantId)
            .queryParam("startTime", startTime.toString())
            .queryParam("endTime", endTime.toString())
            .when()
            .get("/api/v1/audit/range")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", equalTo(3));

        // 查询部分时间范围（应包含中间的记录）
        given()
            .header("X-Tenant-Id", tenantId)
            .queryParam("startTime", startTime.plusMillis(50).toString())
            .queryParam("endTime", midTime.plusMillis(50).toString())
            .when()
            .get("/api/v1/audit/range")
            .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(1));
    }

    /**
     * 测试9：验证多租户隔离
     */
    @Test
    void testMultiTenantIsolation() {
        // Given: 为不同租户创建审计日志
        auditLogger.logPolicyEvaluation("module1", "func1", "tenant-A", 10, true);
        auditLogger.logPolicyEvaluation("module2", "func2", "tenant-B", 20, true);

        // When & Then: 租户 A 不能看到租户 B 的日志
        given()
            .header("X-Tenant-Id", "tenant-A")
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].tenantId", equalTo("tenant-A"));

        // 租户 B 不能看到租户 A 的日志
        given()
            .header("X-Tenant-Id", "tenant-B")
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].tenantId", equalTo("tenant-B"));
    }

    /**
     * 测试10：验证默认租户 ID
     */
    @Test
    void testDefaultTenantId() {
        // Given: 创建默认租户的审计日志
        auditLogger.logPolicyEvaluation("module1", "func1", "default", 10, true);

        // When & Then: 使用默认租户 ID 查询
        given()
            .header("X-Tenant-Id", "default")
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].tenantId", equalTo("default"));
    }

    /**
     * 测试11：验证审计日志完整性 - 所有必需字段
     */
    @Test
    void testAuditLogCompleteness() {
        // Given: 记录策略评估日志
        String policyModule = "aster.finance.loan";
        String policyFunction = "evaluateLoanEligibility";
        String tenantId = "tenant-completeness";
        long executionTimeMs = 42;

        // When: 记录日志
        auditLogger.logPolicyEvaluation(policyModule, policyFunction, tenantId, executionTimeMs, true);

        // Then: 验证所有必需字段都存在
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.id).isNotNull();
        assertThat(log.eventType).isNotNull().isNotBlank();
        assertThat(log.timestamp).isNotNull();
        assertThat(log.tenantId).isNotNull().isNotBlank();
        assertThat(log.policyModule).isNotNull().isNotBlank();
        assertThat(log.policyFunction).isNotNull().isNotBlank();
        assertThat(log.executionTimeMs).isNotNull();
        assertThat(log.success).isNotNull();
    }

    /**
     * 测试12：验证时间戳准确性
     */
    @Test
    void testTimestampAccuracy() {
        // Given: 记录前后的时间戳
        Instant beforeLog = Instant.now();

        // When: 记录审计日志
        auditLogger.logPolicyEvaluation("module", "func", "tenant", 10, true);

        Instant afterLog = Instant.now();

        // Then: 验证日志时间戳在合理范围内
        List<AuditLog> logs = AuditLog.findByTenant("tenant");
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.timestamp)
            .isAfterOrEqualTo(beforeLog)
            .isBeforeOrEqualTo(afterLog);
    }
}
