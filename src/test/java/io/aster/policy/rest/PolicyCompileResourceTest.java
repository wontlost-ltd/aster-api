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
            .body("diagnostics[0].message", not(emptyOrNullString()))
            // ADR 0051 §16 R56：语法诊断带共享码表的码与命名参数（改码前 code=null）
            .body("diagnostics[0].code", matchesPattern("E0[56]\\d"))
            .body("diagnostics[0].params", notNullValue());
    }

    @Test
    public void compileSyntaxError_zh_codeAndLocalizedMessage() {
        // 第 4 行「返回 x」缺句末标点：E059，文案取 zh-CN overlay，标点按作者写法显示
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "模块 m。\\n\\n规则 r 给定 x 作为 整数，产出 整数：\\n  返回 x\\n",
                  "locale": "zh-CN" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics.find { it.code == 'E059' }.startLine", is(4))
            .body("diagnostics.find { it.code == 'E059' }.message", is("语句末尾应为 '。'"))
            .body("diagnostics.find { it.code == 'E059' }.params.token", is("'.'"));
    }

    @Test
    public void compileLiteralOutOfRange_isCodedSourceError_notInternalFailure() {
        // AstBuilder 抛出的 ParseDiagnosticException（E053）是用户源码错误，不是 COMPILE_INTERNAL_ERROR
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return 99999999999999999999.",
                  "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics", hasSize(1))
            .body("diagnostics[0].code", is("E053"))
            .body("diagnostics[0].startLine", is(4))
            .body("diagnostics[0].params.literal", is("99999999999999999999"));
    }

    // ---------------------------------------------------------------- 三阶段带码异常出口（aster-api#458 ②）
    //
    // core 的 ParseDiagnosticException 可能在词法 fill（E054 嵌套深度守卫）、语法分析、构建 AST（E053 字面量
    // 越界、E065 位置不对）三个阶段抛出；InProcessCnlParser 三处都经 codedFailure 返回带码诊断。
    // 这里锁住端点契约：HTTP 200、success=false、恰一条 error 级阻断诊断带码与 1-based 行列、不是内部故障码。

    /** 按 core 的 AsterCustomLexer.MAX_NESTING_DEPTH（1.0.43 起为 64 且 public）多套一层括号。 */
    private static final int NESTING_LIMIT = aster.core.parser.AsterCustomLexer.MAX_NESTING_DEPTH;

    private static String nested(int depth) {
        return "(".repeat(depth) + "1" + ")".repeat(depth);
    }

    /** 带码的用户源码错误：恰一条 error 诊断，码 / 行号如期，列号 1-based，端点没有退回内部故障路径。 */
    private io.restassured.response.ValidatableResponse assertCodedSourceError(String source, String locale,
                                                                              String code, int line) {
        return given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", locale))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(false))
            .body("error", not(emptyOrNullString()))
            .body("$", not(hasKey("module")))
            .body("diagnostics", hasSize(1))
            .body("diagnostics[0].code", is(code))
            .body("diagnostics[0].severity", is("error"))
            // 语法错误阻断保存门（与 E012 同口径），不是 W600 式的 warning
            .body("diagnostics[0].blocking", is(true))
            .body("diagnostics[0].featureId", nullValue())
            .body("diagnostics[0].startLine", is(line))
            .body("diagnostics[0].endLine", is(line))
            .body("diagnostics[0].startColumn", greaterThanOrEqualTo(1))
            .body("diagnostics[0].endColumn", is(notNullValue()))
            .body("diagnostics[0].message", not(emptyOrNullString()))
            .body("diagnostics.findAll { it.code == '" + io.aster.policy.rest.model.CompileDiagnostic.INTERNAL_FAILURE_CODE
                + "' }", hasSize(0));
    }

    @Test
    public void compileNestingTooDeep_lexerStage_isCodedE054() {
        // 词法阶段：第 301 个 '(' 触发守卫；列号落在第 4 行之内即可（lexer 报的是守卫触发时的位置，不是契约）
        String line4 = "  Return " + nested(NESTING_LIMIT + 1) + ".";
        String source = "Module M.\n\nRule p given x as Int, produce Int:\n" + line4;
        assertCodedSourceError(source, "en-US", "E054", 4)
            .body("diagnostics[0].startColumn", allOf(greaterThan(0), lessThanOrEqualTo(line4.length())))
            .body("diagnostics[0].params.limit", is(String.valueOf(NESTING_LIMIT)))
            .body("diagnostics[0].message", is("Nesting is too deep (limit " + NESTING_LIMIT + ")"));
    }

    @Test
    public void compileModerateNesting_isNotRejected() {
        // 对照：守卫只拒绝越过上限的输入，普通深度的括号照常编译（否则上面的用例只是在测「括号多了就报错」）。
        // 不取 299：低于守卫上限但在 CI 栈上已 StackOverflowError → 500，见 aster-api#472（真实缺陷，待 core 调阈值）
        String source = "Module M.\n\nRule p given x as Int, produce Int:\n  Return " + nested(32) + ".";
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .log().ifValidationFails()
            .statusCode(200)
            .body("success", is(true))
            .body("diagnostics.findAll { it.code == 'E054' }", hasSize(0));
    }

    @Test
    public void compileNestingTooDeep_zh_messageFromOverlay() {
        // 同一出口按请求 locale 取 zh-CN overlay 文案，params 不随语言变化
        String source = "模块 m。\n\n规则 f 给定 x 作为 整数，产出 整数：\n  返回 " + nested(NESTING_LIMIT + 1) + "。";
        assertCodedSourceError(source, "zh-CN", "E054", 4)
            .body("diagnostics[0].params.limit", is(String.valueOf(NESTING_LIMIT)))
            .body("diagnostics[0].message", is("嵌套层级过深（上限 " + NESTING_LIMIT + "）"));
    }

    @Test
    public void compileLiteralOutOfRange_astStage_isCodedE053WithKindAndLiteral() {
        // 构建 AST 阶段：Int 字面量越界，params 带 kind / literal；位置在字面量所在行
        String source = "Module M.\n\nRule p given x as Int, produce Int:\n  Return 99999999999.";
        assertCodedSourceError(source, "en-US", "E053", 4)
            .body("diagnostics[0].params.literal", is("99999999999"))
            .body("diagnostics[0].params.kind", not(emptyOrNullString()))
            .body("diagnostics[0].message", containsString("99999999999"));
    }

    @Test
    public void compileDecimalTooManyDigits_astStage_isCodedE053() {
        String source = "Module M.\n\nRule p, produce Decimal:\n  Return "
            + "1111111111111111111111111111111111111111111111m.";
        assertCodedSourceError(source, "en-US", "E053", 4)
            .body("diagnostics[0].params.literal", not(emptyOrNullString()));
    }

    @Test
    public void compileOtherwiseNotLastInBlock_astStage_isCodedE065() {
        // 构建 AST 阶段：Otherwise 后面还有语句 → E065；params 原样带 kw.* / token.* key（按码渲染的契约），
        // message 里已回译成作者语言的关键字拼写与名词
        String source = "Module M.\n\nRule p given x as Int, produce Text:\n  Otherwise allow.\n  Return \"x\".";
        assertCodedSourceError(source, "en-US", "E065", 4)
            .body("diagnostics[0].params.construct", is("kw.otherwise"))
            .body("diagnostics[0].params.position", is("token.lastInBlock"))
            .body("diagnostics[0].message", allOf(containsString("Otherwise"), containsString("last in its block")))
            .body("diagnostics[0].message", not(containsString("kw.")))
            .body("diagnostics[0].message", not(containsString("token.")));
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

    // 领域词汇（ADR 0014 线C）：保存门禁带词汇编译，本地化术语翻成规范名后通过；
    // 不带词汇时同一源码仍按改造前编译失败。body 形同 cloud 发送的 DomainVocabulary JSON。
    private static final String VOCAB_SOURCE =
        "Module insurance.custom.\\n\\nDefine Driver has age as Int.\\n\\n"
        + "Rule evaluate given driver as Fahrer, produce Int:\\n  Return driver.alter.";
    private static final String VOCAB_JSON = """
        { "id": "insurance.custom", "name": "Custom", "locale": "en-US", "version": "user",
          "structs": [ { "canonical": "Driver", "localized": "Fahrer", "kind": "struct" } ],
          "fields": [ { "canonical": "age", "localized": "alter", "kind": "field", "parent": "Driver" } ],
          "functions": [], "enumValues": [] }""";

    @Test
    public void compileLocalizedSource_withVocabulary_succeeds() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"" + VOCAB_SOURCE + "\", \"locale\": \"en-US\", \"vocabulary\": "
                + VOCAB_JSON + " }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true));
    }

    @Test
    public void compileLocalizedSource_withoutVocabulary_failsAsBefore() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"" + VOCAB_SOURCE + "\", \"locale\": \"en-US\" }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics", not(empty()));
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
            "Rule r given x as Int produce Int:",
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

    // 服务端类型检查（生产 E2E 2026-10-23 G2）：字段类型与所赋枚举值不符 → success=false，
    // E012 带 1-based 行列与 blocking=true 原样到达 REST 调用方（cloud 保存门与 Validated 徽章依赖它）。
    // core#237（1.0.40）起未写类型的字段视为未知类型、不再报错，所以 status 必须显式声明为 Text。
    @Test
    public void compileMistypedEnumField_returnsBlockingE012() {
        String source = String.join("\n",
            "Module M.",
            "Define Status as one of Approved, Rejected.",
            "Define Decision has status as Text, reason.",
            "Rule r given x as Int, produce Decision:",
            "  Return Decision with status set to Approved, reason set to \"ok\".");

        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(false))
            .body("diagnostics.find { it.code == 'E012' }", notNullValue())
            .body("diagnostics.find { it.code == 'E012' }.severity", is("error"))
            .body("diagnostics.find { it.code == 'E012' }.blocking", is(true))
            .body("diagnostics.find { it.code == 'E012' }.startLine", is(5));
    }

    @Test
    public void compileCleanSource_hasNoW601Warning() {
        // 负向断言：误报会让业务人员忽略整个功能
        String source = String.join("\n",
            "Module M.",
            "",
            "Rule r given x as Int produce Int:",
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

    @Test
    public void compileUnregisteredControl_returnsW704AndRegistryVersion() {
        // ADR 0045 §3：未登记控制点只给 W704 warning，编译仍成功，并回带注册表版本
        String source = String.join("\n",
            "Module governance.probe.", "",
            "@id(\"GP-001\")", "@control(\"EU_AI_ACT:ART14\")", "@control(\"ACME:ART1\")",
            "Rule decide given amount, produce Verdict:",
            "  If amount at least 100:", "    Return Verdict.require_approval(\"Reviewer\", \"large\").",
            "  Return Verdict.allow().", "");

        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("controlsRegistryVersion", is("1.1.0"))
            .body("diagnostics.findAll { it.code == 'W704' }", hasSize(1))
            .body("diagnostics.find { it.code == 'W704' }.severity", is("warning"))
            .body("diagnostics.find { it.code == 'W704' }.message", containsString("ACME:ART1"))
            .body("diagnostics.find { it.code == 'W704' }.blocking", is(false));
    }

    @Test
    public void compileDuplicateRuleId_returnsE701AsWarning() {
        // cloud 保存门按 severity==='error' 拒绝：治理诊断在编译通道必须是 warning
        String source = String.join("\n",
            "Module governance.dup.", "",
            "@id(\"R-1\")",
            "Rule first given amount, produce Verdict:",
            "  Return Verdict.allow().", "",
            "@id(\"R-1\")",
            "Rule second given amount, produce Verdict:",
            "  Return Verdict.allow().", "");
        assertGovernanceWarning(source, "E701");
    }

    @Test
    public void compileBlankRuleId_returnsE702AsWarning() {
        String source = String.join("\n",
            "Module governance.blank.", "",
            "@id(\"\")",
            "Rule decide given amount, produce Verdict:",
            "  Return Verdict.allow().", "");
        assertGovernanceWarning(source, "E702");
    }

    @Test
    public void compileUnknownProfile_returnsE705AsErrorAndProfile() {
        // ADR 0046 §4：档案违规保留 error，cloud 保存门据此拒绝；编译本身仍成功
        String source = String.join("\n",
            "Module governance.nope.",
            "Profile \"nope\".", "",
            "@id(\"P-1\")",
            "Rule decide given amount, produce Verdict:",
            "  Return Verdict.allow().", "");
        compile(source)
            .body("success", is(true))
            .body("profile", is("nope"))
            .body("diagnostics.findAll { it.code == 'E705' }", hasSize(1))
            .body("diagnostics.find { it.code == 'E705' }.severity", is("error"))
            .body("diagnostics.find { it.code == 'E705' }.blocking", is(false));
    }

    @Test
    public void compileGovernedProfileViolation_returnsE706ErrorAndW700Warning() {
        String source = String.join("\n",
            "Module governance.gov.",
            "Profile \"governed\".", "",
            "Rule decide given amount, produce Verdict:",
            "  Return Verdict.allow().", "");
        compile(source)
            .body("success", is(true))
            .body("profile", is("governed"))
            .body("module.name", is("governance.gov"))
            .body("diagnostics.findAll { it.code == 'E706' }", not(empty()))
            .body("diagnostics.findAll { it.code == 'E706' }.severity", everyItem(is("error")))
            .body("diagnostics.findAll { it.code == 'W700' }", not(empty()))
            .body("diagnostics.findAll { it.code == 'W700' }.severity", everyItem(is("warning")));
    }

    @Test
    public void compileWithoutProfile_omitsProfileField() {
        String source = String.join("\n",
            "Module governance.plain.", "",
            "Rule decide given amount, produce Verdict:",
            "  Return Verdict.allow().", "");
        compile(source)
            .body("success", is(true))
            .body("$", not(hasKey("profile")))
            .body("diagnostics.findAll { it.severity == 'error' }", hasSize(0));
    }

    @Test
    public void compileModuleSummary_listsRulesAndTypesFromCoreIr() {
        String source = String.join("\n",
            "Module summary.probe.", "",
            "Define Applicant has age, income.", "",
            "Rule decide given applicant, produce Int:",
            "  Return 1.", "",
            "Rule score given x as Int, produce Int:",
            "  Return x times 2.", "");
        compile(source)
            .body("success", is(true))
            .body("module.name", is("summary.probe"))
            .body("module.functions", containsInAnyOrder("decide", "score"))
            .body("module.types", contains("Applicant"));
    }

    private io.restassured.response.ValidatableResponse compile(String source) {
        return given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200);
    }

    private void assertGovernanceWarning(String source, String code) {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("diagnostics.findAll { it.code == '" + code + "' }", not(empty()))
            .body("diagnostics.findAll { it.severity == 'error' }", hasSize(0))
            .body("diagnostics.findAll { it.code == '" + code + "' }.severity", everyItem(is("warning")))
            .body("diagnostics.findAll { it.code == '" + code + "' }.blocking", everyItem(is(false)));
    }
}
