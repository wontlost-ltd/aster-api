package io.aster.security.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.rest.model.VocabularyBounds;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词汇表上限在 /compile 与 /evaluate-source 两个端点口径一致：每类越界均 400 vocabulary_too_large。
 * evaluate-source 只接受 HMAC 内部调用，故两端都用签名请求；复用 HMAC profile 避免额外重启。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
class VocabularyBoundsEndpointTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String COMPILE = "/api/v1/policies/compile";
    static final String EVALUATE_SOURCE = "/api/v1/policies/evaluate-source";
    static final String SOURCE = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x.";

    static Map<String, Object> base(String section, Object entries) {
        Map<String, Object> v = new HashMap<>(Map.of("id", "custom", "name", "Custom",
            "locale", "en-US", "version", "user"));
        v.put(section, entries);
        return v;
    }

    /** 每一类越界各一个样本。 */
    static Stream<Map<String, Object>> oversized() {
        int per = VocabularyBounds.MAX_ENTRIES_PER_SECTION;
        Map<String, Object> total = base("structs", Collections.nCopies(per, 1));
        total.put("fields", Collections.nCopies(per, 1));
        total.put("functions", List.of(1));
        Map<String, Object> wide = new HashMap<>();
        for (int i = 0; i <= VocabularyBounds.MAX_OBJECT_KEYS; i++) {
            wide.put("k" + i, "v");
        }
        return Stream.of(
            base("fields", Collections.nCopies(per + 1, 1)),
            total,
            base("fields", List.of(Map.of("canonical", "A",
                "aliases", Collections.nCopies(VocabularyBounds.MAX_NESTED_LIST_SIZE + 1, "x")))),
            base("fields", List.of(wide)),
            base("description", "x".repeat(VocabularyBounds.MAX_STRING_LENGTH + 1)),
            base("fields", List.of(Map.of("canonical", "A", "aliases", List.of(List.of("x"))))));
    }

    static Response signedPost(String path, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        Map<String, String> headers = InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY,
            "POST", path, "", json, "tenant-vocab-bounds", "member", "user-vocab", List.of());
        return given().contentType(ContentType.JSON).headers(headers).body(json).post(path);
    }

    static void assertRejected(Response r) {
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
        assertThat(r.jsonPath().getString("error")).isEqualTo(VocabularyBounds.ERROR_CODE);
    }

    @ParameterizedTest
    @MethodSource("oversized")
    void compile拒绝超限词汇(Map<String, Object> vocabulary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("locale", "en-US");
        body.put("vocabulary", vocabulary);
        assertRejected(signedPost(COMPILE, body));
    }

    @ParameterizedTest
    @MethodSource("oversized")
    void evaluateSource拒绝超限词汇(Map<String, Object> vocabulary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("context", Map.of("x", 1));
        body.put("locale", "en-US");
        body.put("functionName", "p");
        body.put("vocabulary", vocabulary);
        assertRejected(signedPost(EVALUATE_SOURCE, body));
    }

    @Test
    void compile匿名调用同样受限() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("source", SOURCE, "locale", "en-US",
            "vocabulary", base("fields", Collections.nCopies(VocabularyBounds.MAX_ENTRIES_PER_SECTION + 1, 1))));
        assertRejected(given().contentType(ContentType.JSON).header("X-Tenant-Id", "t")
            .body(json).post(COMPILE));
    }

    @Test
    void 上限内的词汇照常编译() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("locale", "en-US");
        body.put("vocabulary", base("fields", List.of(Map.of("canonical", "age", "localized", "alter",
            "kind", "field", "parent", "Driver"))));
        Response r = signedPost(COMPILE, body);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isTrue();
    }
}
