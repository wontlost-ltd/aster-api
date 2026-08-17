package io.aster.policy.integration;

import io.aster.policy.entity.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * 端到端集成测试 - 验证策略评估完整流程
 *
 * 基于动态策略加载（从 Prisma 数据库读取策略）
 * 测试策略评估、审计日志、多租户隔离等核心功能
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyEvaluationE2ETest {

    // audit_logs 自 V6.22.0 起为数据库层 append-only，
    // 夹具清理须走显式保留期通道（与生产清理任务同一条豁免路径）。
    @jakarta.inject.Inject
    io.aster.test.BlockingDbTestHelper auditDb;

    // 使用 default 租户以匹配 V99 种子数据中的策略
    private static final String TENANT_A = "default";
    private static final String TENANT_B = "default";
    private static final String POLICY_MODULE = "aster.finance.loan";
    private static final String POLICY_FUNCTION = "evaluateLoanEligibility";

    @BeforeEach
    @Transactional
    void setUp() {
        // 清理审计日志
        auditDb.executeAsAuditMaintenance("DELETE FROM audit_logs");
    }

    /**
     * 测试1：评估策略 - 信用分数低于650，应拒绝
     */
    @Test
    @Order(1)
    void test1_evaluatePolicy_lowCreditScore() {
        String context = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP001",
                        "amount": 50000,
                        "termMonths": 36,
                        "purpose": "Home renovation"
                    },
                    {
                        "age": 35,
                        "creditScore": 600,
                        "annualIncome": 80000,
                        "monthlyDebt": 2000,
                        "yearsEmployed": 5
                    }
                ]
            }
            """;

        // 注意：V99 种子策略返回静态结果 (approved=true, reason="Test approval")
        // 此测试验证动态加载和执行流程，不验证具体业务逻辑
        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(context)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true))
            .body("result.reason", equalTo("Test approval"))
            .body("error", nullValue());

        // 验证审计日志记录了评估操作。审计经 @ObservesAsync 异步持久化，需等落库后再断言，
        // 否则 full-suite 负载下即时查询会早于异步写入完成（time race）。用 REST 端点轮询等待
        // （await 的轮询线程无 CDI 请求上下文/事务，不能直接跑 blocking Panache 查询——照本类
        // line ~215 的范例走 REST），落库后再用 Panache 在测试主线程读取详情断言。
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            var response = given().header("X-Tenant-Id", TENANT_A)
                .get("/api/v1/audit/type/POLICY_EVALUATION");
            return response.statusCode() == 200 && !response.jsonPath().getList("$").isEmpty();
        });

        List<AuditLog> logs = AuditLog.findByEventType("POLICY_EVALUATION", TENANT_A);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).policyModule).isEqualTo(POLICY_MODULE);
        assertThat(logs.get(0).policyFunction).isEqualTo(POLICY_FUNCTION);
        assertThat(logs.get(0).success).isTrue();
    }

    /**
     * 测试2：评估策略 - 信用分数高，应批准
     */
    @Test
    @Order(2)
    void test2_evaluatePolicy_highCreditScore() {
        String context = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP002",
                        "amount": 50000,
                        "termMonths": 36,
                        "purpose": "Home renovation"
                    },
                    {
                        "age": 35,
                        "creditScore": 720,
                        "annualIncome": 100000,
                        "monthlyDebt": 1500,
                        "yearsEmployed": 10
                    }
                ]
            }
            """;

        // 注意：V99 种子策略返回静态结果
        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(context)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true))
            .body("result.reason", equalTo("Test approval"))
            .body("result.approvedAmount", equalTo(50000))
            .body("result.interestRateBps", equalTo(450)) // V99 种子策略固定值
            .body("error", nullValue());

        // 验证审计日志
        List<AuditLog> logs = AuditLog.findByEventType("POLICY_EVALUATION", TENANT_A);
        assertThat(logs).isNotEmpty();
    }

    /**
     * 测试3：多次评估验证
     *
     * 注意：由于 V99 种子策略只支持 default 租户，此测试验证
     * 同一租户的多次评估都能成功执行并生成审计日志。
     * 真正的多租户隔离测试需要在种子数据中添加多租户策略。
     */
    @Test
    @Order(3)
    void test3_multipleEvaluations() {
        // 第一次评估
        String contextA = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP-A",
                        "amount": 30000,
                        "termMonths": 24,
                        "purpose": "Car purchase"
                    },
                    {
                        "age": 30,
                        "creditScore": 700,
                        "annualIncome": 60000,
                        "monthlyDebt": 1000,
                        "yearsEmployed": 3
                    }
                ]
            }
            """;

        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(contextA)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true));

        // 第二次评估
        String contextB = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP-B",
                        "amount": 20000,
                        "termMonths": 12,
                        "purpose": "Education"
                    },
                    {
                        "age": 25,
                        "creditScore": 680,
                        "annualIncome": 50000,
                        "monthlyDebt": 500,
                        "yearsEmployed": 2
                    }
                ]
            }
            """;

        given()
            .header("X-Tenant-Id", TENANT_A)  // 使用相同租户
            .contentType(ContentType.JSON)
            .body(contextB)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true));

        // 等待异步审计日志处理完成
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            var response = given()
                .header("X-Tenant-Id", TENANT_A)
                .get("/api/v1/audit/type/POLICY_EVALUATION");
            return response.statusCode() == 200 && response.jsonPath().getList("$").size() >= 2;
        });

        // 验证审计日志 - 通过 REST API 查询
        given()
            .header("X-Tenant-Id", TENANT_A)
            .get("/api/v1/audit/type/POLICY_EVALUATION")
            .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(2))  // 至少 2 条日志
            .body("[0].tenantId", equalTo(TENANT_A))
            .body("[0].eventType", equalTo("POLICY_EVALUATION"));
    }

    /**
     * 测试4：查询审计日志 API - 按租户查询
     */
    @Test
    @Order(4)
    void test4_queryAuditLogsByTenant() {
        // 先创建一些审计日志数据
        test1_evaluatePolicy_lowCreditScore();

        // 查询审计日志
        given()
            .header("X-Tenant-Id", TENANT_A)
            .when()
            .get("/api/v1/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0))
            .body("[0].eventType", equalTo("POLICY_EVALUATION"))
            .body("[0].tenantId", equalTo(TENANT_A));
    }

    /**
     * 测试5：查询审计日志 API - 按事件类型查询
     */
    @Test
    @Order(5)
    void test5_queryAuditLogsByEventType() {
        test2_evaluatePolicy_highCreditScore();

        given()
            .header("X-Tenant-Id", TENANT_A)
            .when()
            .get("/api/v1/audit/type/POLICY_EVALUATION")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0))
            .body("[0].eventType", equalTo("POLICY_EVALUATION"));
    }

    /**
     * 测试6：查询审计日志 API - 按策略查询
     */
    @Test
    @Order(6)
    void test6_queryAuditLogsByPolicy() {
        test1_evaluatePolicy_lowCreditScore();

        given()
            .header("X-Tenant-Id", TENANT_A)
            .when()
            .get("/api/v1/audit/policy/" + POLICY_MODULE + "/" + POLICY_FUNCTION)
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0))
            .body("policyModule", everyItem(equalTo(POLICY_MODULE)))
            .body("policyFunction", everyItem(equalTo(POLICY_FUNCTION)));
    }

    /**
     * 测试7：验证审计日志完整性
     */
    @Test
    @Order(7)
    void test7_verifyAuditLogCompleteness() {
        test2_evaluatePolicy_highCreditScore();

        // 获取所有审计日志
        List<AuditLog> allLogs = AuditLog.findByTenant(TENANT_A);

        assertThat(allLogs).isNotEmpty();

        // 验证所有日志包含必要字段
        allLogs.forEach(log -> {
            assertThat(log.eventType).isNotNull();
            assertThat(log.timestamp).isNotNull();
            assertThat(log.tenantId).isNotNull();
            assertThat(log.policyModule).isNotNull();
            assertThat(log.policyFunction).isNotNull();
            assertThat(log.success).isNotNull();
        });

        // 验证 PII 脱敏（如果有的话）
        allLogs.forEach(log -> {
            if (log.performedBy != null && log.performedBy.contains("@")) {
                assertThat(log.performedBy).isEqualTo("***@***.***");
            }
        });
    }

    /**
     * 测试8：性能指标验证
     */
    @Test
    @Order(8)
    void test8_verifyMetrics() {
        test2_evaluatePolicy_highCreditScore();

        // 验证 Micrometer 指标已记录
        given()
            .when()
            .get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("policy_evaluation"));
    }

    /**
     * 测试9：批量评估
     */
    @Test
    @Order(9)
    void test9_batchEvaluation() {
        String batchContext = """
            {
                "requests": [
                    {
                        "policyModule": "aster.finance.loan",
                        "policyFunction": "evaluateLoanEligibility",
                        "context": [
                            {
                                "applicantId": "APP001",
                                "amount": 30000,
                                "termMonths": 24,
                                "purpose": "Car"
                            },
                            {
                                "age": 30,
                                "creditScore": 700,
                                "annualIncome": 60000,
                                "monthlyDebt": 1000,
                                "yearsEmployed": 3
                            }
                        ]
                    },
                    {
                        "policyModule": "aster.finance.loan",
                        "policyFunction": "evaluateLoanEligibility",
                        "context": [
                            {
                                "applicantId": "APP002",
                                "amount": 50000,
                                "termMonths": 36,
                                "purpose": "Home"
                            },
                            {
                                "age": 40,
                                "creditScore": 600,
                                "annualIncome": 80000,
                                "monthlyDebt": 2000,
                                "yearsEmployed": 10
                            }
                        ]
                    }
                ]
            }
            """;

        // 注意：V99 种子策略返回静态结果 (approved=true)
        // 此测试验证批量评估流程，不验证具体业务逻辑
        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(batchContext)
            .when()
            .post("/api/v1/policies/evaluate/batch")
            .then()
            .statusCode(200)
            .body("results", hasSize(2))
            .body("results[0].result.approved", equalTo(true))
            .body("results[0].result.reason", equalTo("Test approval"))
            .body("successCount", greaterThanOrEqualTo(1))  // 至少一个成功
            .body("totalExecutionTimeMs", greaterThanOrEqualTo(0));
    }
}
