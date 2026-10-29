package io.aster.replay.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.rest.model.VocabularyBounds;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.replay.core.ReplayExecutionRequest;
import io.aster.policy.replay.ReplayMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR 0051：runner 必须接收并使用策略词汇，否则 parity 两侧一侧带词汇一侧不带，必然假不一致。
 * 权威侧以 {@link ReplayExecutionCore} 直接评估（与 aster-api 各入口同一口径）作为对照。
 */
class RunnerVocabularyParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 结构体用规范名定义，规则里用德语本地化术语引用——只有带词汇才能解析。 */
    private static final String SOURCE = "Module insurance.custom.\n"
        + "Define Driver has age as Int.\n"
        + "Rule evaluate given driver as Fahrer, produce Int:\n  Return driver.alter.\n";

    private static final Map<String, Object> VOCABULARY = Map.of(
        "id", "insurance.custom", "name", "Custom", "locale", "de-DE", "version", "user",
        "structs", List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
        "fields", List.of(Map.of("canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
        "functions", List.of(),
        "enumValues", List.of());

    private static final Map<String, Object> INPUT = Map.of("driver", Map.of("age", 30));

    private JsonNode runRunner(Map<String, Object> vocabulary) throws Exception {
        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("tenantId", "tenant-1");
        req.put("source", SOURCE);
        req.put("input", INPUT);
        req.put("locale", "en-US");
        req.put("functionName", "evaluate");
        req.put("aliasSet", null);
        if (vocabulary != null) {
            req.put("vocabulary", vocabulary);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunnerMain.run(new ByteArrayInputStream(mapper.writeValueAsBytes(req)), new PrintStream(out));
        String last = out.toString().strip().lines().reduce((a, b) -> b).orElseThrow();
        return mapper.readTree(last);
    }

    private ReplayMetadata authorityMetadata() {
        ReplayExecutionCore core = new ReplayExecutionCore();
        var phase = core.execute(new ReplayExecutionRequest("tenant-1", SOURCE, INPUT, "evaluate", "en-US",
            VOCABULARY, null, false, false, true, true), new io.aster.replay.runner.StandaloneReplayExecutor());
        var trace = core.buildDecisionTrace(phase.execResult(), phase.traceDrainResult(), true);
        return core.computeReplayMetadata(RunnerToolchainId.current(), INPUT,
            phase.execResult(), trace, phase.traceDrainResult());
    }

    @Test
    void germanVocabularyEvaluatesInRunnerAndMatchesAuthority() throws Exception {
        assertNull(VocabularyBounds.validate(VOCABULARY));
        JsonNode env = runRunner(VOCABULARY);
        assertEquals("SUCCESS", env.get("outcome").asText(), env.toString());
        JsonNode md = env.get("replayMetadata");
        ReplayMetadata expected = authorityMetadata();
        assertEquals(expected.canonicalOutputHash(), md.get("canonicalOutputHash").asText());
        assertEquals(expected.traceHash(), md.get("traceHash").asText());
        assertEquals(expected.canonicalInputHash(), md.get("canonicalInputHash").asText());
    }

    @Test
    void sameSourceWithoutVocabularyStillFailsNameResolution() throws Exception {
        JsonNode env = runRunner(null);
        assertEquals("ERROR", env.get("outcome").asText(), "无词汇时本地化引用必须解析失败（无词汇行为不变）");
    }

    @Test
    void oversizedVocabularyIsRejectedBeforeBuildingScopes() throws Exception {
        Map<String, Object> big = new java.util.HashMap<>();
        for (int i = 0; i < VocabularyBounds.MAX_OBJECT_KEYS + 1; i++) {
            big.put("k" + i, "v");
        }
        JsonNode env = runRunner(big);
        assertEquals("ERROR", env.get("outcome").asText());
        assertEquals("VOCABULARY", env.get("errorCode").asText());
        assertEquals("validate", env.get("phase").asText(), "上限校验发生在解析之前，phase 应为 validate");
    }
}
