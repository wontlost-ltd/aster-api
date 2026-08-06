package io.aster.policy.rest;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST 契约测试 — POST /api/v1/policies/compile（匿名只读源码编译）。
 *
 * 覆盖：匿名访问、成功编译、语法错误结构化诊断（1-based 行列）、依赖别名的源码、
 * 空源码 400、源码超 16KB 400、超限 aliasSet 拒绝。这是 cloud 保存前校验的公共契约。
 */
@QuarkusTest
@RedisEnabledTest
public class PolicyCompileResourceTest {

    @Test
    public void compileValidSource_returnsSuccessNoDiagnostics() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times 3.",
                  "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(true));
    }

    @Test
    public void compileSyntaxError_returnsStructuredDiagnostics1Based() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times .",
                  "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics", not(empty()))
            .body("diagnostics[0].severity", is("error"))
            .body("diagnostics[0].startLine", greaterThanOrEqualTo(1))
            .body("diagnostics[0].startColumn", greaterThanOrEqualTo(1))
            .body("diagnostics[0].message", not(emptyOrNullString()));
    }

    @Test
    public void compileAliasDependentSource_compilesWithAliasSet() {
        // 依赖用户别名的源码：带 aliasSet 编译应成功（否则会被误判解析错误）。
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x multiplied by 3.",
                  "locale": "en-US",
                  "aliasSet": { "TIMES": ["multiplied by"] } }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true));
    }

    @Test
    public void compileBlankSource_returns400() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"\", \"locale\": \"en-US\" }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400);
    }

    @Test
    public void compileOversizeSource_returns400() {
        // 源码超 16KB 匿名上限 → 400（@Size 校验，防算法复杂度 DoS）。
        String huge = "// " + "x".repeat(17_000) + "\nModule M.";
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"" + huge + "\", \"locale\": \"en-US\" }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400);
    }

    @Test
    public void compileOversizeAliasSet_returns400() {
        // 单别名短语超 256 字符 → 400（防负载塞 aliasSet 绕过源码 16KB 上限）。
        String longPhrase = "x".repeat(300);
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times 3.",
                  "locale": "en-US",
                  "aliasSet": { "TIMES": ["%s"] } }
                """.formatted(longPhrase))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400)
            .body("error", is("alias_set_too_large"));
    }

    // ★Phase 2 端到端接线：规则冲突提示必须真的出现在 HTTP 响应里。
    //
    // 单测只能证明 PolicyCompiler 返回了诊断；这条证明它穿过 REST 映射层
    // （CompileDiagnostic.from）到达了调用方。分析器写完却无人调用是四轮
    // 交叉审查点名的交付缺口——"接线"必须验到用户能看见为止。
    @Test
    public void compileWithRuleConflict_returnsW601WarningAndStillSucceeds() {
        // 内层 x < 50 与外层 x > 100 矛盾 —— 真恒假
        String source = String.join("\n",
            "Module M.",
            "",
            "Rule r given x as Number produce Number:",
            "  If x is greater than 100:",
            "    If x is less than 50:",
            "      Return 1.",
            "    Return 2.",
            "  Return 0.");

        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            // ★恒不阻断：矛盾条件只是提示，编译照常成功
            .body("success", is(true))
            .body("diagnostics.find { it.code == 'W601' }", notNullValue())
            .body("diagnostics.find { it.code == 'W601' }.severity", is("warning"))
            .body("diagnostics.find { it.code == 'W601' }.blocking", is(false));
    }

    @Test
    public void compileCleanSource_hasNoW601Warning() {
        // 负向断言：误报会让业务人员忽略整个功能
        String source = String.join("\n",
            "Module M.",
            "",
            "Rule r given x as Number produce Number:",
            "  If x is greater than 100:",
            "    Return 1.",
            "  Return 0.");

        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("diagnostics.findAll { it.code == 'W601' }", hasSize(0));
    }
}
