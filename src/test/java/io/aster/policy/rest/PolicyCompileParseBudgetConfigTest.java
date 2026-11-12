package io.aster.policy.rest;

import io.aster.policy.parser.ParseBudget;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * 端点返回的 E054 带该预算，且中止时间随之变化。
 */
@QuarkusTest
@RedisEnabledTest
@TestProfile(PolicyCompileParseBudgetConfigTest.ShortBudgetProfile.class)
public class PolicyCompileParseBudgetConfigTest {

    public static class ShortBudgetProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("aster.parse.time-budget", "750ms");
        }
    }

    @Test
    void configuredBudgetIsAppliedToCompileEndpoint() throws Exception {
        assertEquals(Duration.ofMillis(750), ParseBudget.configured(), "启动时按配置值设定进程级预算");
        String source = Files.readString(Path.of("replay/src/test/resources/parse-budget/sll-pathological-types.aster"));

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
            .body("diagnostics.find { it.code == 'E054' }.params.budgetMs", is("750"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsed < 750 + 1500, "应在 750ms 预算附近返回（而非默认 2s），实际 " + elapsed + "ms");
    }
}
