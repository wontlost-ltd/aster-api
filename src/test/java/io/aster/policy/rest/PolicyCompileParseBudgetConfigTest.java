package io.aster.policy.rest;

import aster.core.embed.ParseBudget;
import aster.core.embed.ParseBudgetTestHooks;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code aster.parse.time-budget} 确实接到了解析器（aster-cloud/aster-lang-core#254）：用非默认值启动，
 * 编译返回的 E054 带该预算（budgetMs=750，而不是默认的 2000），且不早于该预算中止。
 * {@code aster.parse.anonymous-time-budget} 同样接到了匿名编译端点（aster-api#489）：budgetMs=650，而不是默认的 500。
 */
@QuarkusTest
@RedisEnabledTest
@TestProfile(PolicyCompileParseBudgetConfigTest.ShortBudgetProfile.class)
public class PolicyCompileParseBudgetConfigTest {

    public static class ShortBudgetProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("aster.parse.time-budget", "750ms", "aster.parse.anonymous-time-budget", "650ms");
        }
    }

    @jakarta.inject.Inject
    io.aster.policy.compiler.PolicyCompiler policyCompiler;

    @AfterEach
    void restoreSlowChecks() {
        ParseBudgetTestHooks.slowChecks(null);
    }

    @Test
    void configuredAnonymousBudgetIsAppliedToCompileEndpoint() throws Exception {
        assertEquals(Duration.ofMillis(750), ParseBudget.configured(), "启动时按配置值设定进程级预算");
        String source = PolicyCompileParseBudgetTest.slowSource();
        ParseBudgetTestHooks.slowChecks(PolicyCompileParseBudgetTest.SLOW_STEP);

        // 不量上限：配置值是否生效由返回的 budgetMs 判定（默认 2000，这里必须是 750）。停顿让解析必然超出预算，
        // 负载只会让它更慢，所以这里的结论与机器快慢无关；下限只是「没有提前中止」，同样不受负载影响。
        long t0 = System.nanoTime();
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .log().ifValidationFails()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics.findAll { it.code == 'E054' && it.params.reason == 'time-budget' }", hasSize(1))
            .body("diagnostics.find { it.code == 'E054' }.params.budgetMs", is("650"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsed >= 650, "匿名额度到点之前不应中止，实际 " + elapsed + "ms");
    }

    @Test
    void configuredProcessBudgetIsAppliedOutsideAnonymousEndpoints() {
        String source = PolicyCompileParseBudgetTest.slowSource();
        ParseBudgetTestHooks.slowChecks(PolicyCompileParseBudgetTest.SLOW_STEP);
        long t0 = System.nanoTime();
        io.aster.policy.compiler.CompilationResult result = policyCompiler.compile(source, "en-US", null);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals("750", result.getDiagnostics().stream()
            .filter(d -> "E054".equals(d.code())).findFirst().orElseThrow().params().get("budgetMs"));
        assertTrue(elapsed >= 750, "预算到点之前不应中止，实际 " + elapsed + "ms");
    }
}
