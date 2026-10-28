package io.aster.security.apikey;

import io.aster.policy.parser.GermanReproductions;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0051 §1 的四个复现经 /compile 与 /evaluate-source 端到端：德语源码 + 请求词汇编译成功、执行得到正确结果；
 * 名称解析错误（E8xx）以既有编译错误形态返回（code + 消息 + 位置）。复用 HMAC profile 避免额外重启。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
class PositionAwareVocabularyEndpointTest {

    static List<GermanReproductions.Repro> repros() {
        return GermanReproductions.ALL;
    }

    @ParameterizedTest
    @MethodSource("repros")
    void compile带词汇的复现编译成功(GermanReproductions.Repro repro) throws Exception {
        Response r = VocabularyBoundsEndpointTest.signedPost(VocabularyBoundsEndpointTest.COMPILE,
            compileBody(repro.source(), repro.vocabulary()));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isTrue();
        List<String> declared = new java.util.ArrayList<>(r.jsonPath().getList("module.types", String.class));
        declared.addAll(r.jsonPath().getList("module.functions", String.class));
        assertThat(declared).doesNotContainAnyElementsOf(repro.writtenNames());
    }

    @Test
    void compile名称解析错误返回编译错误形态() throws Exception {
        Response r = VocabularyBoundsEndpointTest.signedPost(VocabularyBoundsEndpointTest.COMPILE,
            compileBody(GermanReproductions.BARE_ENUM_MEMBER, GermanReproductions.ENUM_MEMBER_VS_RULE.vocabulary()));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).isFalse();
        assertThat(r.jsonPath().getString("error")).contains("E804");
        Map<String, Object> e804 = r.jsonPath().getList("diagnostics", Map.class).stream()
            .filter(d -> "E804".equals(d.get("code"))).findFirst().orElseThrow();
        assertThat(e804).containsEntry("severity", "error").containsEntry("startLine", 9);
        assertThat((String) e804.get("message")).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("repros")
    void evaluateSource带词汇的复现执行正确(GermanReproductions.Repro repro) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", repro.source());
        body.put("context", repro.context());
        body.put("locale", "de-DE");
        body.put("functionName", repro.entry());
        body.put("vocabulary", repro.vocabulary());
        Response r = VocabularyBoundsEndpointTest.signedPost(VocabularyBoundsEndpointTest.EVALUATE_SOURCE, body);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getString("error")).as(r.asString()).isNull();
        assertThat(r.jsonPath().<Object>get("result")).isEqualTo(repro.expected());
    }

    private static Map<String, Object> compileBody(String source, Map<String, Object> vocabulary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("locale", "de-DE");
        body.put("vocabulary", vocabulary);
        return body;
    }
}
