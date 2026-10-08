package io.aster.replay.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0 权威 expected.json 生成器（P0-A S2-1a-2）。
 *
 * <p><b>权威对照 = aster-api 生产 {@code evaluateSource(replayCapture=true)}</b>（spec L133），
 * <b>非</b> runner 自产（避免 runner-vs-runner 自证）。本 @QuarkusTest 复用
 * {@link io.aster.policy.rest.PolicyEvaluationReplayOrderingTest} 已验证的 HMAC 已验证内部
 * 调用方 harness——即真实生产 REST 入口 {@code POST /api/v1/policies/evaluate-source}，走
 * 与线上 cloud BFF 完全一致的 replayCapture 路径（{@code effectiveReplayCapture = replayCapture
 * && aliasesTrusted}，HMAC 已验证故 aliasesTrusted=true）。响应体里的 {@code replayMetadata}
 * 即 {@link io.aster.policy.replay.ReplayMetadata}——把它逐字写到 {@code <name>.expected.json}，
 * 作为 runner（arm64 容器/stock-JRE）byte-identical 比对的权威基线。
 *
 * <p><b>驱动方式（复用的 harness）</b>：与 PolicyEvaluationReplayOrderingTest 同一 HmacProfile
 * （关全局签名 + 强制 HMAC 校验路径）+ 同一 canonical HMAC 构造。corpus 请求（schema ②：
 * tenantId/source/input/locale/functionName/aliasSet）映射为 REST {@code SourcePolicyRequest}
 * （source/context/locale/functionName），POST 到 {@code ?trace=false&replayCapture=true}，
 * 取响应 {@code replayMetadata} 写盘。<b>本类不新起 HTTP server、不复制评估逻辑</b>——直接打
 * 生产端点，确保 expected 就是 aster-api 生产路径的真实产出。
 *
 * <p><b>gen 门控</b>：仅当系统属性 {@code -Dparity.gen.expected=true} 时才写 expected.json
 * （由 gen-expected.sh 传入）。缺省（普通 {@code :test} 全跑）下该测试<b>短路跳过写盘</b>，
 * 避免污染工作区——但仍<b>驱动一次 corpus 并断言 REPLAYABLE</b>，保证 corpus 每个 fixture
 * 在 aster-api 生产路径下真能编译执行（双 oracle 之一：runner 集成测试是另一个）。
 *
 * <p><b>corpus 目录</b>：{@code -Dparity.corpus.dir} 指定 parity-corpus 目录绝对路径（由
 * gen-expected.sh 传入 runner 侧路径）。缺省回退到常规相对路径，便于本地手跑。
 */
@QuarkusTest
@TestProfile(GenExpectedCorpusTest.HmacProfile.class)
class GenExpectedCorpusTest {

    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String HMAC_KEY = "s2-1a-2-parity-corpus-hmac-key!!";
    private static final String ROLE = "MEMBER";

    /** ★静态复用：driveAuthority 抽为 package-private static（供 RunnerDistributionParityTest 复用），故 mapper 亦静态。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 与 PolicyEvaluationReplayOrderingTest.HmacProfile 同构：关全局签名，强制走 HMAC 校验路径
     * （InternalCallerFilter 对 /evaluate-source 落 REQUIRE_HMAC，只有 HMAC 已验证请求能进方法体，
     * 且 aliasesTrusted=true → effectiveReplayCapture 生效）。
     */
    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false"
            );
        }
    }

    /** 发一次 HMAC 已验证内部调用方请求（cloud BFF 视角，canonical v3 由共享助手签名），replayCapture=true。 */
    private static Response evaluateWithReplayCapture(String body, String tenant) {
        String query = "trace=false&replayCapture=true";
        return given()
            .header("Content-Type", "application/json")
            .headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, query, body, tenant, ROLE, "", List.of()))
            .body(body)
            .when()
            .post(PATH + "?" + query);
    }

    /**
     * 权威侧 A：把 corpus {@code .req.json}（schema ②：tenantId/source/input/locale/functionName/aliasSet）
     * 经 HMAC harness 打生产 {@code POST /evaluate-source?trace=false&replayCapture=true}，返回响应体里的
     * {@code replayMetadata} JsonNode。★package-private static——供 {@link RunnerDistributionParityTest}
     * 复用同一权威驱动，避免复制 HMAC canonical 构造（DRY）。
     *
     * <p>断言生产路径必须 200、无 error、含 replayMetadata（否则 corpus fixture 在生产路径下不可编译执行，直接失败）。
     * ★与 gen 路径同一映射：{@code context = req.input}（RunnerRequest.input ↔ REST context），tenant 由 fixture 名 + nanoTime 生成，
     * nonce 由签名助手每次唯一生成，保 HMAC 不 replay 拦截。
     *
     * @param reqJson corpus {@code .req.json} 原文
     * @return 生产 evaluateSource 的 {@code replayMetadata} JsonNode（权威基线）
     */
    static JsonNode driveAuthority(String reqJson) throws IOException {
        JsonNode req = MAPPER.readTree(reqJson);

        // schema ② 请求 → REST SourcePolicyRequest（source/context/locale/functionName）。
        ObjectNode body = MAPPER.createObjectNode();
        body.put("source", req.get("source").asText());
        body.set("context", req.get("input"));   // 原样透传 input map
        body.put("locale", req.get("locale").asText());
        if (req.hasNonNull("functionName")) {
            body.put("functionName", req.get("functionName").asText());
        }
        String bodyJson = MAPPER.writeValueAsString(body);

        String label = req.hasNonNull("tenantId") ? req.get("tenantId").asText() : "corpus";
        String tenant = "tenant-parity-" + label + "-" + System.nanoTime();
        Response resp = evaluateWithReplayCapture(bodyJson, tenant);

        assertEquals(200, resp.getStatusCode(),
            "driveAuthority evaluateSource 非 200（corpus fixture 在 aster-api 生产路径下不可编译执行）：body="
                + resp.getBody().asString());
        JsonNode root = MAPPER.readTree(resp.getBody().asString());
        assertTrue(root.get("error") == null || root.get("error").isNull(),
            "driveAuthority 生产路径返回 error（corpus fixture 编译执行失败）：" + root.path("error").asText());
        JsonNode rm = root.get("replayMetadata");
        assertNotNull(rm, "driveAuthority 响应缺 replayMetadata（replayCapture 未生效？检查 HMAC/aliasesTrusted）");
        assertTrue(rm.isObject() && !rm.isNull(), "driveAuthority replayMetadata 非对象");
        return rm;
    }

    /**
     * 驱动固定 corpus 经 aster-api 生产 evaluateSource → 产权威 expected.json（或普通跑时仅断言）。
     *
     * <p>★不用 @ParameterizedTest：QuarkusTest 单进程内一次读全 corpus，避免多参数化重启开销；
     * 每个 fixture 用独立 nonce/tenant 防 HMAC replay 拦截。
     */
    @Test
    void driveCorpusThroughEvaluateSourceAndWriteExpected() throws IOException {
        Path corpusDir = resolveCorpusDir();
        boolean gen = Boolean.getBoolean("parity.gen.expected");

        List<Path> reqFiles;
        try (var stream = Files.list(corpusDir)) {
            reqFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".req.json"))
                .sorted()
                .toList();
        }
        assertTrue(!reqFiles.isEmpty(),
            "parity-corpus 目录下必须有 *.req.json（corpus 缺失说明路径错或 Task Step 1 未执行）：" + corpusDir);

        List<String> generated = new ArrayList<>();
        for (Path reqFile : reqFiles) {
            String name = reqFile.getFileName().toString().replace(".req.json", "");

            // ★复用 driveAuthority（同一 HMAC 权威驱动）——不再复制 canonical 构造。
            JsonNode rm = driveAuthority(Files.readString(reqFile, StandardCharsets.UTF_8));

            // ★corpus 语义要求：parity 门只对可回放 fixture 有意义——权威侧必须 REPLAYABLE。
            assertEquals("REPLAYABLE", rm.get("replayabilityStatus").asText(),
                "[" + name + "] 权威 aster-api 侧 replayabilityStatus 非 REPLAYABLE，reasons="
                    + rm.path("replayabilityReasons"));

            if (gen) {
                // 权威 expected = 完整 replayMetadata 对象逐字写盘（含 toolchainId；byte-compare 时脚本排除）。
                Path out = corpusDir.resolve(name + ".expected.json");
                String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rm);
                Files.writeString(out, pretty + "\n", StandardCharsets.UTF_8);
                generated.add(out.getFileName().toString());
            }
        }

        if (gen) {
            System.out.println("[GenExpectedCorpusTest] 已由 aster-api evaluateSource 权威路径生成 expected.json："
                + generated);
        } else {
            System.out.println("[GenExpectedCorpusTest] 未开 -Dparity.gen.expected：仅驱动 corpus 断言 REPLAYABLE，"
                + "未写盘（共 " + reqFiles.size() + " 个 fixture 全部通过）。");
        }
    }

    /**
     * 解析 corpus 目录：优先系统属性 {@code -Dparity.corpus.dir}（gen-expected.sh 传绝对路径），
     * 缺省回退到 runner 侧的相对路径（便于本地在 aster-api 根目录直接手跑）。
     */
    static Path resolveCorpusDir() {
        String prop = System.getProperty("parity.corpus.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        return Path.of("runner", "src", "test", "resources", "parity-corpus")
            .toAbsolutePath();
    }
}
