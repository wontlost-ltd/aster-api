package io.aster.policy.rest;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST 契约 — POST /api/v1/policies/compile 对超深嵌套源码的出口（aster-api#472）。
 *
 * <p>无论 core 守卫是否先于线程栈溢出触发，端点都必须返回 200 + 带码 E054 的用户源码错误，
 * 绝不是 500（{@code StackOverflowError} 穿透 {@code catch (Exception)}）或内部故障码
 * {@code COMPILE_INTERNAL_ERROR}。深度取 core 守卫上限 +1、299（#472 的原始复现：低于 1.0.42 的
 * 守卫 300、在 1MB 栈上溢出）与一个远超值。
 */
@QuarkusTest
@RedisEnabledTest
public class PolicyCompileNestingDepthTest {

    /** core 的守卫上限（AsterCustomLexer.MAX_NESTING_DEPTH，1.0.43 起为 64 且 public）。 */
    private static final int CORE_NESTING_LIMIT = aster.core.parser.AsterCustomLexer.MAX_NESTING_DEPTH;

    private static String nestedSource(int depth) {
        return "Module M.\n\nRule p given x as Int, produce Int:\n  Return "
            + "(".repeat(depth) + "x" + ")".repeat(depth) + ".";
    }

    private static io.restassured.response.ValidatableResponse compile(int depth) {
        return given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", nestedSource(depth), "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .log().ifValidationFails()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("diagnostics.findAll { it.code == '" + io.aster.policy.rest.model.CompileDiagnostic.INTERNAL_FAILURE_CODE
                + "' }", hasSize(0));
    }

    @ParameterizedTest(name = "深度 {0}：超过守卫上限 → 200 + E054")
    @ValueSource(ints = {CORE_NESTING_LIMIT + 1, 5000})
    public void overLimit_isCodedE054_not500(int depth) {
        compile(depth)
            .body("success", is(false))
            .body("diagnostics", hasSize(1))
            .body("diagnostics[0].code", is("E054"))
            .body("diagnostics[0].severity", is("error"))
            .body("diagnostics[0].blocking", is(true))
            .body("diagnostics[0].startLine", is(4))
            .body("diagnostics[0].params.limit", not(emptyOrNullString()));
    }

    @ParameterizedTest(name = "深度 {0}：#472 原始复现（低于 1.0.42 守卫、1MB 栈上溢出）→ 绝不 500")
    @ValueSource(ints = {299})
    public void belowOldLimit_neverInternalError(int depth) {
        // 守卫下调（core 1.0.43）前：macOS 2MB 栈上编译成功，1MB 栈（CI / 生产）上溢出 → 必须归为 E054；
        // 下调后：守卫先触发 → E054。两种结果都合法，唯一不合法的是 500 / 内部故障码。
        compile(depth)
            .body("$", anyOf(hasEntry("success", true),
                hasEntry(equalTo("success"), equalTo(false))))
            .body("diagnostics.findAll { it.severity == 'error' && it.code != 'E054' }", hasSize(0));
    }
}
