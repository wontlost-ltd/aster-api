package io.aster.policy.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.CompileIdentity;
import io.aster.replay.core.ReplayExecutionCore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * source envelope 5 段协议（ADR 0051 §7）与跨仓共享向量的核对。
 *
 * <p>fixture 是 aster-lang-test {@code corpus/shared-vectors/envelope-vectors.json} 的逐字节副本，
 * 用 sha256 钉住：源文件变化时须重新复制并更新 {@link #VECTORS_SHA256}。cloud 用同一份向量，
 * 两侧对同一输入必须算出同一 envelope。
 */
class PolicyVersionEnvelopeTest {

    private static final String VECTORS_SHA256 = "cc743299e6f0bde27b650337f0ebb70652b9b8c80ed6278d764b8c1416b8c563";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] vectorsBytes() throws Exception {
        try (InputStream in = PolicyVersionEnvelopeTest.class.getResourceAsStream("/shared-vectors/envelope-vectors.json")) {
            assertThat(in).as("共享向量 fixture 缺失").isNotNull();
            return in.readAllBytes();
        }
    }

    private static JsonNode vectors() throws Exception {
        return JSON.readTree(vectorsBytes());
    }

    @Test
    void fixtureIsVerbatimCopy() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(vectorsBytes());
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(VECTORS_SHA256);
    }

    @Test
    void everySharedVectorMatchesExpectedSha256() throws Exception {
        JsonNode all = vectors();
        assertThat(all.size()).isGreaterThanOrEqualTo(3);
        for (JsonNode v : all) {
            String actual = PolicyVersion.computeSourceEnvelope(
                v.get("content").asText(), v.get("aliasSetJson").asText(), v.get("locale").asText(),
                v.get("toolchainId").asText(), v.get("scopesJson").asText());
            assertThat(actual).as("vector toolchainId=%s", v.get("toolchainId").asText())
                .isEqualTo(v.get("expectedSha256").asText());
        }
    }

    @Test
    void apiScopesJsonMatchesEveryVector() throws Exception {
        // api 的实际构建路径（请求词汇 Map → VocabularyScopes → scopesJson）必须与 TS 产出的 scopesJson 逐字节一致。
        int withVocabulary = 0;
        for (JsonNode v : vectors()) {
            JsonNode vocabulary = v.get("vocabulary");
            Map<String, Object> map = vocabulary.isNull() ? null
                : JSON.convertValue(vocabulary, new TypeReference<Map<String, Object>>() {});
            if (map != null) {
                withVocabulary++;
            }
            String scopesJson = CompileIdentity.scopesJson(ReplayExecutionCore.buildVocabularyScopes(map));
            assertThat(scopesJson).isEqualTo(v.get("scopesJson").asText());
        }
        assertThat(withVocabulary).isGreaterThanOrEqualTo(2);
    }

    @Test
    void noVocabularyFramesFifthSegmentAsZeroLength() throws Exception {
        String content = "Module M.";
        String expected = sha256("9:Module M.|" + "0:|" + "5:en-US|" + "2:tc|" + "0:|");
        assertThat(PolicyVersion.computeSourceEnvelope(content, null, "en-US", "tc", null)).isEqualTo(expected);
        assertThat(PolicyVersion.computeSourceEnvelope(content, null, "en-US", "tc", CompileIdentity.scopesJson(null)))
            .isEqualTo(expected);
    }

    @Test
    void vocabularyChangesEnvelope() {
        String bare = PolicyVersion.computeSourceEnvelope("Module M.", null, "en-US", "tc", "");
        String withScopes = PolicyVersion.computeSourceEnvelope("Module M.", null, "en-US", "tc", "{\"x\":1}");
        assertThat(withScopes).isNotEqualTo(bare);
    }

    @Test
    void emptyVocabularyIsNoVocabulary() {
        // 没有任何条目的词汇与无词汇等价：scopesJson 为空串，不序列化空 scopes 与 schemaVersion。
        Map<String, Object> empty = vocabulary(List.of());
        assertThat(CompileIdentity.scopesJson(ReplayExecutionCore.buildVocabularyScopes(empty))).isEmpty();
        assertThat(CompileIdentity.scopesJson(ReplayExecutionCore.buildVocabularyScopes(Map.of()))).isEmpty();
        assertThat(CompileIdentity.scopesHash(ReplayExecutionCore.buildVocabularyScopes(empty))).isEmpty();
    }

    @Test
    void literalOnlyVocabularyStillEntersEnvelope() {
        // ADR §7「有词汇时为 canonicalScopesJson()」：字面量宏会改变编译输入，必须进 envelope（与 TS 一致）。
        Map<String, Object> literalOnly = vocabulary(List.of(
            Map.of("canonical", "gold-tier", "localized", "goldstufe", "kind", "literal")));
        String scopesJson = CompileIdentity.scopesJson(ReplayExecutionCore.buildVocabularyScopes(literalOnly));
        assertThat(scopesJson)
            .isEqualTo("{\"enumMembers\":{},\"fields\":{},\"functions\":{},\"literals\":{\"goldstufe\":\"gold-tier\"},"
                + "\"schemaVersion\":1,\"types\":{}}");
    }

    private static Map<String, Object> vocabulary(List<Map<String, Object>> literals) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", "t");
        v.put("name", "t");
        v.put("locale", "de-DE");
        v.put("version", "1.0.0");
        v.put("structs", List.of());
        v.put("fields", List.of());
        v.put("functions", List.of());
        v.put("enumValues", List.of());
        v.put("literals", literals);
        return v;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
