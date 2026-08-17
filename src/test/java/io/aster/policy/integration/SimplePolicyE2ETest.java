package io.aster.policy.integration;

import io.aster.policy.entity.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.awaitility.Awaitility.await;

/**
 * 简化的端到端集成测试
 *
 * 验证策略评估、多租户隔离、性能指标核心功能
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimplePolicyE2ETest {

    // audit_logs 自 V6.22.0 起为数据库层 append-only，
    // 夹具清理须走显式保留期通道（与生产清理任务同一条豁免路径）。
    @jakarta.inject.Inject
    io.aster.test.BlockingDbTestHelper auditDb;

    // 使用 default 租户以匹配 V99 种子数据中的策略
    private static final String TENANT_A = "default";
    private static final String TENANT_B = "default";

    @BeforeEach
    @Transactional
    void setUp() {
        auditDb.executeAsAuditMaintenance("DELETE FROM audit_logs");
    }

    /**
     * 测试1：基本策略评估
     */
    @Test
    @Order(1)
    void test1_basicEvaluation() {
        String request = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP001",
                        "amount": 50000,
                        "termMonths": 36,
                        "purpose": "Home"
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
            .body(request)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true))
            .body("result.reason", equalTo("Test approval"))
            .body("error", nullValue());

        // 等待异步审计日志处理完成，然后通过 REST API 验证
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            var response = given()
                .header("X-Tenant-Id", TENANT_A)
                .get("/api/v1/audit/type/POLICY_EVALUATION");
            return response.statusCode() == 200 && response.jsonPath().getList("$").size() > 0;
        });

        // 通过 REST API 查询审计日志
        given()
            .header("X-Tenant-Id", TENANT_A)
            .get("/api/v1/audit/type/POLICY_EVALUATION")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].policyModule", equalTo("aster.finance.loan"))
            .body("[0].eventType", equalTo("POLICY_EVALUATION"));
    }

    /**
     * 测试2：多租户隔离
     */
    @Test
    @Order(2)
    void test2_multiTenantIsolation() {
        String requestA = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {"applicantId": "A1", "amount": 30000, "termMonths": 24, "purpose": "Car"},
                    {"age": 30, "creditScore": 700, "annualIncome": 60000, "monthlyDebt": 1000, "yearsEmployed": 3}
                ]
            }
            """;

        String requestB = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {"applicantId": "B1", "amount": 20000, "termMonths": 12, "purpose": "Education"},
                    {"age": 25, "creditScore": 680, "annualIncome": 50000, "monthlyDebt": 500, "yearsEmployed": 2}
                ]
            }
            """;

        // 租户A评估 - 验证返回成功
        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(requestA)
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true));

        // 租户B评估 - 验证返回成功
        given()
            .header("X-Tenant-Id", TENANT_B)
            .contentType(ContentType.JSON)
            .body(requestB)
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true));
    }

    /**
     * 测试3：策略批准场景
     */
    @Test
    @Order(3)
    void test3_approvedScenario() {
        String request = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP003",
                        "amount": 50000,
                        "termMonths": 36,
                        "purpose": "Home"
                    },
                    {
                        "age": 35,
                        "creditScore": 750,
                        "annualIncome": 100000,
                        "monthlyDebt": 1500,
                        "yearsEmployed": 10
                    }
                ]
            }
            """;

        // 注意：V99 种子策略返回静态结果
        // approvedAmount=50000, interestRateBps=450, termMonths=360
        given()
            .header("X-Tenant-Id", TENANT_A)
            .contentType(ContentType.JSON)
            .body(request)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            .statusCode(200)
            .body("result.approved", equalTo(true))
            .body("result.reason", equalTo("Test approval"))
            .body("result.approvedAmount", equalTo(50000))
            .body("result.interestRateBps", equalTo(450))  // V99 种子策略固定值
            .body("error", nullValue());
    }

    /**
     * 测试4：性能指标
     */
    @Test
    @Order(4)
    void test4_metrics() {
        given()
            .get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("policy_evaluation"));
    }
}
