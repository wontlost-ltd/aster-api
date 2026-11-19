package io.aster.policy.rest;

import aster.core.embed.PolicySourceCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.security.apikey.CompileStructuralAliasHmacTest;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.JingyesiPolicy;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 《静夜思》原诗即策略的 Java 一侧证明（ADR 0060 §8 证明第 2、3 条，计划 T7）。
 *
 * <p>输入见 {@link JingyesiPolicy}（与 core、ts 的诗测试逐字相同）。经 HMAC 签名的 {@code /compile} 与
 * {@code /evaluate-source}——cloud BFF 的真实入口——编译零 error、执行得到 {@code 静夜思}；同一编译入口产出的
 * Core IR 的 {@code positionFreeSha256} 与两引擎的期望值相同。依赖引擎 ≥ 1.0.52（别名规则 v2）：
 * 在 1.0.51 上四个别名被 v1 校验器拒绝，除未签名用例外全部失败。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
class JingyesiPolicyCompileTest {

    static final String COMPILE = "/api/v1/policies/compile";
    static final String EVALUATE_SOURCE = "/api/v1/policies/evaluate-source";
    static final String TENANT = "tenant-jingyesi";
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PolicyCompiler policyCompiler;

    static Map<String, Object> compileBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", JingyesiPolicy.SOURCE);
        body.put("locale", JingyesiPolicy.LOCALE);
        body.put("aliasSet", JingyesiPolicy.ALIAS_SET);
        body.put("allowStructural", true);
        body.put("vocabulary", JingyesiPolicy.vocabulary());
        return body;
    }

    static Response signedPost(String path, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        Map<String, String> headers = InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY,
            "POST", path, "", json, TENANT, "member", "user-jingyesi", List.of());
        return given().contentType(ContentType.JSON).headers(headers).body(json).post(path);
    }

    @Test
    void compileReturnsNoErrors() throws Exception {
        Response r = signedPost(COMPILE, compileBody());
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isTrue();
        assertThat(r.jsonPath().getList("diagnostics.severity", String.class)).doesNotContain("error");
        assertThat(r.jsonPath().getString("module.name")).isEqualTo("明月光");
        assertThat(r.jsonPath().getList("module.functions", String.class)).containsExactly(JingyesiPolicy.ENTRY);
    }

    @Test
    void evaluateSourceReturnsJingyesi() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", JingyesiPolicy.SOURCE);
        body.put("context", Map.of());
        body.put("locale", JingyesiPolicy.LOCALE);
        body.put("functionName", JingyesiPolicy.ENTRY);
        body.put("vocabulary", JingyesiPolicy.vocabulary());
        body.put("aliasSet", JingyesiPolicy.ALIAS_SET);
        Response r = signedPost(EVALUATE_SOURCE, body);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getString("error")).as(r.asString()).isNull();
        assertThat(r.jsonPath().<Object>get("result")).as(r.asString()).isEqualTo(JingyesiPolicy.OUTPUT);
    }

    @Test
    void unsignedCallerCannotUseTheStructuralAliases() throws Exception {
        // 未签名调用方传 allowStructural=true 被忽略：四个结构词别名按未授权处理，编译失败
        String json = MAPPER.writeValueAsString(compileBody());
        Response r = given().contentType(ContentType.JSON).header("X-Tenant-Id", TENANT)
            .body(json).post(COMPILE);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isFalse();
        assertThat(r.asString()).contains("FUNC_PRODUCE");
    }

    @Test
    void coreIrMatchesTs() throws Exception {
        // 与 /compile 同一编译入口、同一词汇构建口径（ReplayExecutionCore.buildVocabularyScopes）、已授权
        CompilationResult result = policyCompiler.compile(JingyesiPolicy.SOURCE, JingyesiPolicy.LOCALE,
            MAPPER.writeValueAsString(JingyesiPolicy.ALIAS_SET),
            ReplayExecutionCore.buildVocabularyScopes(JingyesiPolicy.vocabulary()), true);
        assertThat(result.isSuccess()).as(String.valueOf(result.getErrors())).isTrue();
        assertThat(result.getCoreJson()).contains(JingyesiPolicy.OUTPUT);
        assertThat(PolicySourceCompiler.positionFreeSha256(result.getCoreJson()))
            .isEqualTo(JingyesiPolicy.POSITION_FREE_SHA256);
    }
}
