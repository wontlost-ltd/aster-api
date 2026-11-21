package io.aster.policy.rest;

import io.aster.common.JacksonMappers;
import aster.core.embed.ParseBudget;
import aster.core.embed.ParseBudgetTestHooks;
import io.aster.security.apikey.CompileStructuralAliasHmacTest;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验签的内部调用方（cloud BFF：编辑器 compile-on-type、保存门）调 /compile 不受匿名额度限制（aster-api#489），
 * 仍按进程级 {@code aster.parse.time-budget}（默认 2s）；同一请求不签名则按匿名额度 500ms。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
public class PolicyCompileParseBudgetHmacTest {

    private static final String PATH = "/api/v1/policies/compile";
    private static final String TENANT = "tenant-compile-489";

    @AfterEach
    void restore() {
        ParseBudgetTestHooks.slowChecks(null);
    }

    private static String body() throws Exception {
        return JacksonMappers.DEFAULT.writeValueAsString(
            Map.of("source", PolicyCompileParseBudgetTest.slowSource(), "locale", "en-US"));
    }

    private static long compileAndReportedBudget(String body, Map<String, String> headers, String expectedBudgetMs) {
        long t0 = System.nanoTime();
        given().contentType(ContentType.JSON).headers(headers).body(body)
        .when()
            .post(PATH)
        .then()
            .log().ifValidationFails()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics.findAll { it.code == 'E054' && it.params.reason == 'time-budget' }", hasSize(1))
            .body("diagnostics.find { it.code == 'E054' }.params.budgetMs", is(expectedBudgetMs));
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    @Test
    void signedCallerKeepsProcessBudgetWhileUnsignedGetsAnonymousBudget() throws Exception {
        assertEquals(ParseBudget.DEFAULT_BUDGET, ParseBudget.configured());
        String body = body();
        ParseBudgetTestHooks.slowChecks(PolicyCompileParseBudgetTest.SLOW_STEP);

        Map<String, String> signed = InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY,
            "POST", PATH, "", body, TENANT, "member", "user-489", List.of());
        long signedMs = compileAndReportedBudget(body, signed, "2000");
        assertTrue(signedMs >= 2000, "验签调用方按进程级 2s 预算，不应提前中止，实际 " + signedMs + "ms");

        long anonMs = compileAndReportedBudget(body, Map.of("X-Tenant-Id", TENANT), "500");
        assertTrue(anonMs < 2000, "未签名调用方按匿名额度中止，实际 " + anonMs + "ms");
    }
}
