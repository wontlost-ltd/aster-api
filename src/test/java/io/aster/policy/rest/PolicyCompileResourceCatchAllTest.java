package io.aster.policy.rest;

import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * POST /api/v1/policies/compile 的兜底失败分支：编译通道之外抛出的异常也必须带一条
 * error 诊断，否则只看 diagnostics 的 cloud 保存门会把「编译没做成」当成「没有错误」放行。
 */
@QuarkusTest
@RedisEnabledTest
class PolicyCompileResourceCatchAllTest {

    @InjectMock
    PolicyCompiler policyCompiler;

    @Test
    void unexpectedFailure_returnsSingleErrorDiagnosticAt1_1() {
        when(policyCompiler.compile(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.", "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics", hasSize(1))
            .body("diagnostics[0].severity", is("error"))
            .body("diagnostics[0].startLine", is(1))
            .body("diagnostics[0].startColumn", is(1))
            .body("diagnostics[0].code", nullValue())
            .body("diagnostics[0].message", not(emptyOrNullString()));
    }
}
