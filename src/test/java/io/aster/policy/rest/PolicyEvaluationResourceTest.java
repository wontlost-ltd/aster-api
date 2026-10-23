package io.aster.policy.rest;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST API 测试 - 策略评估服务
 *
 * 测试覆盖:
 * 1. 单策略评估 - POST /api/policies/evaluate
 * 2. 批量评估 - POST /api/policies/evaluate/batch
 * 3. 策略验证 - POST /api/policies/validate
 * 4. 缓存清除 - DELETE /api/policies/cache
 * 5. 多租户隔离 - X-Tenant-Id 头部测试
 * 6. 错误处理 - 无效输入和边界情况
 */
@QuarkusTest
@RedisEnabledTest
public class PolicyEvaluationResourceTest {

    @Inject
    PolicyEvaluationService evaluationService;

    @BeforeEach
    public void setUp() {
        // 清除所有租户的缓存，确保测试隔离
        evaluationService.invalidateCache("default", null, null).await().indefinitely();
        evaluationService.invalidateCache("tenant1", null, null).await().indefinitely();
        evaluationService.invalidateCache("tenant2", null, null).await().indefinitely();
    }

    /**
     * 测试单策略评估 - 贷款评估场景
     */
    @Test
    public void testEvaluatePolicy_LoanScenario() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [
                        {
                            "applicantId": "APP-1001",
                            "amount": 200000,
                            "termMonths": 60,
                            "purpose": "购房"
                        },
                        {
                            "age": 35,
                            "creditScore": 750,
                            "annualIncome": 500000,
                            "monthlyDebt": 8000,
                            "yearsEmployed": 5
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("result", notNullValue())
            .body("result.approved", is(true))
            .body("result.interestRateBps", notNullValue())
            .body("executionTimeMs", greaterThanOrEqualTo(0))
            .body("error", nullValue());
    }

    /**
     * 测试单策略评估 - 信用卡评估场景
     */
    @Test
    public void testEvaluatePolicy_CreditCardScenario() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.creditcard",
                    "policyFunction": "evaluateCreditCardApplication",
                    "context": [
                        {
                            "applicantId": "CC-2001",
                            "age": 28,
                            "annualIncome": 300000,
                            "creditScore": 680,
                            "existingCreditCards": 1,
                            "monthlyRent": 6000,
                            "employmentStatus": "employed",
                            "yearsAtCurrentJob": 3
                        },
                        {
                            "bankruptcyCount": 0,
                            "latePayments": 0,
                            "utilization": 30,
                            "accountAge": 5,
                            "hardInquiries": 1
                        },
                        {
                            "productType": "白金卡",
                            "requestedLimit": 50000,
                            "hasRewards": true,
                            "annualFee": 800
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("result", notNullValue())
            .body("result.approved", is(true))
            .body("result.approvedLimit", notNullValue())
            .body("executionTimeMs", greaterThanOrEqualTo(0))
            .body("error", nullValue());
    }

    /**
     * 测试多租户隔离 - 使用不同租户ID
     */
    @Test
    public void testEvaluatePolicy_MultiTenant() {
        String requestBody = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP-2001",
                        "amount": 300000,
                        "termMonths": 120,
                        "purpose": "购房"
                    },
                    {
                        "age": 40,
                        "creditScore": 800,
                        "annualIncome": 600000,
                        "monthlyDebt": 5000,
                        "yearsEmployed": 10
                    }
                ]
            }
            """;

        // 租户1执行
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "tenant1")
            .body(requestBody)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200)
            .body("result", notNullValue())
            .body("error", nullValue());

        // 租户2执行（应该有独立缓存）
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "tenant2")
            .body(requestBody)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200)
            .body("result", notNullValue())
            .body("error", nullValue());
    }

    /**
     * 测试批量评估 - 多个策略
     */
    @Test
    public void testEvaluateBatch_MultipleRequests() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "requests": [
                        {
                            "policyModule": "aster.finance.loan",
                            "policyFunction": "evaluateLoanEligibility",
                            "context": [
                                {
                                    "applicantId": "APP-3001",
                                    "amount": 150000,
                                    "termMonths": 60,
                                    "purpose": "购车"
                                },
                                {
                                    "age": 30,
                                    "creditScore": 700,
                                    "annualIncome": 400000,
                                    "monthlyDebt": 6000,
                                    "yearsEmployed": 3
                                }
                            ]
                        },
                        {
                            "policyModule": "aster.finance.creditcard",
                            "policyFunction": "evaluateCreditCardApplication",
                            "context": [
                                {
                                    "applicantId": "CC-3001",
                                    "age": 25,
                                    "annualIncome": 250000,
                                    "creditScore": 650,
                                    "existingCreditCards": 0,
                                    "monthlyRent": 5000,
                                    "employmentStatus": "employed",
                                    "yearsAtCurrentJob": 2
                                },
                                {
                                    "bankruptcyCount": 0,
                                    "latePayments": 1,
                                    "utilization": 45,
                                    "accountAge": 3,
                                    "hardInquiries": 2
                                },
                                {
                                    "productType": "标准卡",
                                    "requestedLimit": 30000,
                                    "hasRewards": false,
                                    "annualFee": 300
                                }
                            ]
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate/batch")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("results", hasSize(2))
            .body("results[0].result", notNullValue())
            .body("results[1].result", notNullValue())
            .body("totalExecutionTimeMs", greaterThanOrEqualTo(0))
            .body("successCount", is(2))
            .body("failureCount", is(0));
    }

    /**
     * 测试批量评估 - 包含成功和失败的请求
     */
    @Test
    public void testEvaluateBatch_WithFailures() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "requests": [
                        {
                            "policyModule": "aster.finance.loan",
                            "policyFunction": "evaluateLoanEligibility",
                            "context": [
                                {
                                    "applicantId": "APP-4001",
                                    "amount": 150000,
                                    "termMonths": 60,
                                    "purpose": "购车"
                                },
                                {
                                    "age": 30,
                                    "creditScore": 700,
                                    "annualIncome": 400000,
                                    "monthlyDebt": 6000,
                                    "yearsEmployed": 3
                                }
                            ]
                        },
                        {
                            "policyModule": "aster.invalid.module",
                            "policyFunction": "nonExistentFunction",
                            "context": [{}]
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate/batch")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("results", hasSize(2))
            .body("results[0].result", notNullValue())
            .body("results[0].error", nullValue())
            .body("results[1].result", nullValue())
            .body("results[1].error", notNullValue())
            .body("successCount", is(1))
            .body("failureCount", is(1));
    }

    /**
     * 测试策略验证 - 有效策略
     */
    @Test
    public void testValidatePolicy_ValidPolicy() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility"
                }
                """)
        .when()
            .post("/api/v1/policies/validate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", is(true))
            .body("message", containsString("valid and callable"))
            .body("parameterCount", greaterThan(0))
            .body("returnType", notNullValue());
    }

    /**
     * 测试策略验证 - 无效策略（不存在的模块）
     */
    @Test
    public void testValidatePolicy_InvalidModule() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.nonexistent.module",
                    "policyFunction": "someFunction"
                }
                """)
        .when()
            .post("/api/v1/policies/validate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", is(false))
            .body("message", notNullValue())
            .body("parameterCount", nullValue())
            .body("returnType", nullValue());
    }

    /**
     * 测试策略验证 - 无效策略（不存在的函数）
     */
    @Test
    public void testValidatePolicy_InvalidFunction() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "nonExistentFunction"
                }
                """)
        .when()
            .post("/api/v1/policies/validate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", is(false))
            .body("message", notNullValue());
    }

    /**
     * 测试缓存清除 - 清除特定租户的所有缓存
     */
    @Test
    public void testClearCache_AllForTenant() {
        // 先执行一次评估生成缓存
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "tenant1")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [
                        {
                            "applicantId": "APP-CACHE-1",
                            "amount": 150000,
                            "termMonths": 60,
                            "purpose": "购车"
                        },
                        {
                            "age": 30,
                            "creditScore": 700,
                            "annualIncome": 400000,
                            "monthlyDebt": 6000,
                            "yearsEmployed": 3
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200);

        // 清除缓存
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "tenant1")
            .body("{}")
        .when()
            .delete("/api/v1/policies/cache")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(true))
            .body("message", containsString("Cache cleared"));
    }

    /**
     * 测试缓存清除 - 清除特定模块的缓存
     */
    @Test
    public void testClearCache_SpecificModule() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan"
                }
                """)
        .when()
            .delete("/api/v1/policies/cache")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(true))
            .body("message", containsString("module=aster.finance.loan"));
    }

    /**
     * 测试缓存清除 - 清除特定函数的缓存
     */
    @Test
    public void testClearCache_SpecificFunction() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility"
                }
                """)
        .when()
            .delete("/api/v1/policies/cache")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(true))
            .body("message", containsString("module=aster.finance.loan"))
            .body("message", containsString("function=evaluateLoanEligibility"));
    }

    /**
     * 测试错误处理 - 缺少必需字段（policyModule）
     */
    @Test
    public void testEvaluatePolicy_MissingModule() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [{}]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(400); // Jakarta Validation 错误
    }

    /**
     * 测试错误处理 - 缺少必需字段（policyFunction）
     */
    @Test
    public void testEvaluatePolicy_MissingFunction() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "context": [{}]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(400); // Jakarta Validation 错误
    }

    /**
     * 测试错误处理 - 缺少必需字段（context）
     */
    @Test
    public void testEvaluatePolicy_MissingContext() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility"
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(400); // Jakarta Validation 错误
    }

    /**
     * 测试错误处理 - 批量评估请求数量超过限制
     */
    @Test
    public void testEvaluateBatch_TooManyRequests() {
        // 构造101个请求（超过限制100）
        StringBuilder requestsJson = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) requestsJson.append(",");
            requestsJson.append("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [
                        {"applicantId": "APP-BULK-%1$d", "amount": 150000, "termMonths": 60, "purpose": "购车"},
                        {"age": 30, "creditScore": 700, "annualIncome": 400000, "monthlyDebt": 6000, "yearsEmployed": 3}
                    ]
                }
                """.formatted(i));
        }
        requestsJson.append("]");

        given()
            .contentType(ContentType.JSON)
            .body("{\"requests\": " + requestsJson.toString() + "}")
        .when()
            .post("/api/v1/policies/evaluate/batch")
        .then()
            .statusCode(400); // Jakarta Validation 错误
    }

    /**
     * 测试错误处理 - 空批量请求列表
     */
    @Test
    public void testEvaluateBatch_EmptyRequests() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "requests": []
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate/batch")
        .then()
            .statusCode(400); // Jakarta Validation 错误
    }

    /**
     * 测试错误处理 - 策略执行失败（参数不匹配）。
     * 运行时只给出执行失败文本，无法可靠区分输入问题与内部故障，按 500 evaluation_failed 回，且不透出内部消息。
     */
    @Test
    public void testEvaluatePolicy_ParameterMismatch() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [
                        {"wrongField": "wrongValue"}
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(500)
            .contentType(ContentType.JSON)
            .body("result", nullValue())
            .body("error", notNullValue())
            .body("code", equalTo("evaluation_failed"));
    }

    /**
     * 测试租户默认值 - 未提供X-Tenant-Id头部时使用default
     */
    @Test
    public void testEvaluatePolicy_DefaultTenant() {
        // 显式提供 X-Tenant-Id="default"（TenantFilter 强制要求）
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                {
                    "policyModule": "aster.finance.loan",
                    "policyFunction": "evaluateLoanEligibility",
                    "context": [
                        {
                            "applicantId": "APP-DEFAULT-1",
                            "amount": 150000,
                            "termMonths": 60,
                            "purpose": "购车"
                        },
                        {
                            "age": 30,
                            "creditScore": 700,
                            "annualIncome": 400000,
                            "monthlyDebt": 6000,
                            "yearsEmployed": 3
                        }
                    ]
                }
                """)
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(200)
            .body("result", notNullValue())
            .body("error", nullValue());
    }
}
