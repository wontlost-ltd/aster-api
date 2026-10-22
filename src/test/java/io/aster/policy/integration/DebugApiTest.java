package io.aster.policy.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DebugApiTest {

    @Test
    void debugApiResponse() {
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

        String response = given()
            .header("X-Tenant-Id", "debug-tenant")
            .contentType(ContentType.JSON)
            .body(request)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            // debug-tenant 下没有部署该策略：按 F11 的状态码契约回 404 policy_not_found（此前为 200 + error）
            .statusCode(404)
            .body("code", org.hamcrest.Matchers.equalTo("policy_not_found"))
            .extract()
            .body()
            .asString();

        System.out.println("========== API RESPONSE ==========");
        System.out.println(response);
        System.out.println("==================================");
    }
}
