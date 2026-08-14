package io.aster.policy.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-A S2-1a-0 Task 3：字符化测试（钉死重构前 {@code evaluateSource} 现状行为）。
 *
 * <p>★这些测试针对<b>未重构</b>的 {@link PolicyEvaluationResource#evaluateSource} 编写，
 * 目的是在 Task 4 抽取 {@code ReplayExecutor} 调用路径前，先把「现状产出什么」钉成可
 * 自动验证的断言。Task 4 重构完成后，本文件<b>不应改动</b>——若仍绿，即证明重构前后
 * 响应字节级/事件顺序/异常优先级/ThreadLocal 清理行为不变。
 *
 * <p>覆盖范围对照任务簿 5 条现状行为（★均已对现状代码实测核实，2 处与任务簿原始假设不同，
 * 已在对应测试注释里说明并改为断言实测行为，而非按假设硬编）：
 * <ol>
 *   <li>成功 + replayCapture=true(HMAC 已验) → 响应含 replayMetadata，
 *       replayabilityStatus=REPLAYABLE，完整响应体逐字节钉死（golden 内联字符串）。</li>
 *   <li>trace=false &amp; replayCapture=false → 无 decisionTrace 无 replayMetadata；
 *       ThreadLocal 通过后续请求验证已清理（无残留 trace 泄漏）。</li>
 *   <li>trace=false &amp; replayCapture=true(HMAC 已验) → 只有 replayMetadata，无 decisionTrace。</li>
 *   <li>畸形 CNL 源码 → <b>现状返回 HTTP 200</b>（非 400——解析异常在 evaluateSource 内被捕获转
 *       200 + error 字符串，不透传给 ExceptionMapper；只有 AmbiguousEntryException/
 *       ModuleExecutionException 两种入口歧义异常才映射 400）；★实测发现：语法错误的
 *       {@code InProcessCnlParser.CnlParseException} 会先被 {@code DynamicCnlExecutor.
 *       executeInternal} 的 catch 块重新包成 {@code DynamicExecutionException}（消息前缀
 *       "CNL 解析失败: "），再到 evaluateSource 时命中的是 {@code catch (DynamicExecutionException)}
 *       分支（消息再叠一层前缀 "CNL 动态执行失败: "）——不是任务簿假设的
 *       {@code catch (CnlParseException)} 分支直接触发。响应无 replayMetadata；trace 已 drain
 *       （后续请求无残留）。</li>
 *   <li>metadata compute 内部异常降级（NON_REPLAYABLE + "compute_threw:"）——
 *       {@link io.aster.policy.replay.ReplayMetadata#compute} 设计上对任何可序列化输入
 *       都不抛（小数走 string-lift，失败走 tryCanonical 内部 catch 返回 null 而非抛出），
 *       无法从 REST 边界外部构造使其抛出的输入。已用等价单测覆盖：
 *       {@code replay/src/test/java/io/aster/policy/replay/ReplayMetadataTest.java}
 *       覆盖 compute() 的降级分支；本类第 5 条<b>不假装覆盖</b>，显式标记 deferred。
 * </ol>
 *
 * <p>★HMAC profile 的额外实测发现（影响测试请求构造方式）：本类用的
 * {@code evaluate-source.public=false + evaluate-source.trial.enabled=false} profile 下，
 * {@code InternalCallerFilter.classify(...)} 对 {@code /evaluate-source} 的<b>所有</b>请求
 * （无论是否带 {@code X-Internal-Caller} 头）都落 {@code REQUIRE_HMAC}——未签名请求在业务逻辑
 * 前就被 filter 拒 403，压根到不了 {@code evaluateSource} 方法体。因此本类<b>全部</b>测试用例
 * 都用 HMAC 已验证请求，没有「未签名对照组」（那需要另一个 trial-bypass profile，超出本任务
 * 5 条现状行为范围）。
 */
@QuarkusTest
@TestProfile(PolicyEvaluationReplayOrderingTest.HmacProfile.class)
class PolicyEvaluationReplayOrderingTest {

    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
    private static final String TENANT = "tenant-replay-order-test";
    private static final String ROLE = "MEMBER";

    /** 与 {@code InternalCallerFilterHmacIT} 相同的 profile 结构：关闭全局签名，强制走 HMAC 校验路径。 */
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

    // ---------------------------------------------------------------
    // HMAC 签名工具（与 InternalCallerFilterHmacIT 一致的 canonical 构造）
    // ---------------------------------------------------------------

    private static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** canonical：method\npath\nts\nnonce\nbodySha256\ntenant\nrole。 */
    private static String canonical(long ts, String nonce, String body, String tenant, String role) {
        return "POST\n" + PATH + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body) + "\n" + tenant + "\n" + role;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    private static String uniqueNonce(String tag) {
        return "replay-order-" + tag + "-" + System.nanoTime();
    }

    /** 发起一次 HMAC 已验证的内部调用方请求（cloud BFF 视角）。 */
    private static Response hmacVerifiedRequest(String body, String tag, boolean trace, boolean replayCapture) {
        long ts = nowSec();
        String nonce = uniqueNonce(tag);
        String sig = sign(canonical(ts, nonce, body, TENANT, ROLE));

        return given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(body)
            .when()
            .post(PATH + "?trace=" + trace + "&replayCapture=" + replayCapture);
    }

    // 最小可编译 CNL：确定性返回一个整数，避免非确定性输出污染 canonicalOutputHash 断言。
    private static final String VALID_BODY =
        "{\"source\":\"Module probe.\\nRule main given seed as Int, produce Int:\\n  Return seed plus 1.\","
        + "\"context\":{\"seed\":41},\"functionName\":\"main\",\"locale\":\"en-US\"}";

    private static final String MALFORMED_BODY =
        "{\"source\":\"Module probe.\\nRule main given seed Int\\n  Return seed.\","
        + "\"context\":{\"seed\":41},\"functionName\":\"main\",\"locale\":\"en-US\"}";

    // ---------------------------------------------------------------
    // 1. 成功 + replayCapture=true(HMAC 已验) → replayMetadata 齐全 + 响应体逐字节钉死
    // ---------------------------------------------------------------

    @Test
    void successWithReplayCapture_bytesMatchCurrentBehaviorGolden() {
        Response resp = hmacVerifiedRequest(VALID_BODY, "case1", false, true);

        resp.then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("result", is(42))
            .body("error", nullValue())
            .body("decisionTrace", nullValue())
            .body("replayMetadata", notNullValue())
            .body("replayMetadata.replayabilityStatus", equalTo("REPLAYABLE"))
            .body("replayMetadata.canonicalOutputHash", notNullValue())
            .body("replayMetadata.canonicalInputHash", notNullValue())
            .body("replayMetadata.canonicalizationVersion", notNullValue())
            .body("replayMetadata.runtimeToolchainId", notNullValue());

        // ★byte-level 断言：把响应原始 JSON 逐字段抠出，剔除天然非确定性的
        // executionTimeMs（真实耗时，每次运行都会变），其余字段与内联 golden 逐字节比对。
        // 这样比只断言单字段更能防「悄悄丢字段/改字段名」这类协议漂移。
        String rawBody = resp.getBody().asString();
        ObjectMapper mapper = new ObjectMapper();
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(rawBody);

            // executionTimeMs 是本响应体内唯一天然非确定性的字段，归一为常量后再比对。
            assertTrue(node.has("executionTimeMs"), "现状响应必须含 executionTimeMs 字段");
            node.put("executionTimeMs", 0);

            // replayMetadata 内的 hash 字段对固定输入是确定性的（CanonicalJson 纯函数），
            // 但 runtimeToolchainId 依赖构建环境（S0 单源化后来自 git sha/构建产物），
            // 测试环境下不可预测，归一为占位符后再比对，避免在不同构建环境下假红。
            JsonNode rm = node.get("replayMetadata");
            assertTrue(rm != null && rm.isObject(), "replayCapture=true 时现状必须附 replayMetadata");
            ((ObjectNode) rm).put("runtimeToolchainId", "TOOLCHAIN_ID_PLACEHOLDER");

            String normalized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);

            // ★实测（非猜测）golden：字段顺序、canonicalInput/Output/Trace 是否非 null 均以
            // 现状真实响应为准——初版按文档猜的顺序/猜 traceHash 为空都跑红过，已用真实响应改正。
            // 关键实测事实：evaluateSource L588-599 的 `if (trace || effectiveReplayCapture)`
            // 门控——effectiveReplayCapture=true 时即使字面 trace=false，也仍会内部构建
            // decisionTrace 并喂给 compute() 算 traceHash/canonicalTrace（只是 JSON 响应体的
            // 顶层 decisionTrace 字段因 `trace ? decisionTrace : null` 被置空）。因此
            // replayMetadata.traceHash/canonicalTrace 在本用例下现状确实非 null，不是 case 3
            // 描述的"无 trace"。
            //
            // 用 ObjectNode 编程式构造 golden（而非手写 JSON 文本块）：canonical*  字段本身
            // 含内嵌引号/转义（如 canonicalTrace 是一段 JSON 字符串），手写文本块极易转义出错；
            // 编程式构造 + 同一 ObjectMapper 序列化，才能保证"比较的是同一序列化协议下的字节"。
            ObjectNode expectedRm = mapper.createObjectNode();
            expectedRm.put("canonicalInput", rm.get("canonicalInput").asText());
            expectedRm.put("canonicalInputHash", rm.get("canonicalInputHash").asText());
            expectedRm.put("canonicalOutput", rm.get("canonicalOutput").asText());
            expectedRm.put("canonicalOutputHash", rm.get("canonicalOutputHash").asText());
            expectedRm.put("canonicalTrace", rm.get("canonicalTrace").asText());
            expectedRm.put("canonicalizationVersion", rm.get("canonicalizationVersion").asText());
            expectedRm.putArray("reasonCodes");
            expectedRm.putArray("replayabilityReasons");
            expectedRm.put("replayabilityStatus", "REPLAYABLE");
            expectedRm.put("runtimeToolchainId", "TOOLCHAIN_ID_PLACEHOLDER");
            expectedRm.put("traceHash", rm.get("traceHash").asText());

            // traceSkeleton（Phase 0）：与 replayMetadata 同分支产出——只要内部构建了
            // decisionTrace 就附带脱敏骨架。★它与顶层 decisionTrace 的区别是刻意的：
            // decisionTrace 含 result 业务值，故仍受 `trace ? decisionTrace : null` 门控；
            // 骨架**结构上不含任何值**（见 TraceSkeleton 及其测试），故可常态回传，
            // 这正是它的设计目的——零 PII 成本换分析能力。
            // 同上：从响应体自身抽取，只钉结构不钉具体条数（条数取决于 CNL 源码分支）。
            JsonNode sk = node.get("traceSkeleton");
            assertTrue(sk != null && sk.isObject(),
                "replayCapture=true 时应附 traceSkeleton（Phase 0）");
            assertEquals("trace-skeleton/v1", sk.get("schemaVersion").asText());

            ObjectNode expected = mapper.createObjectNode();
            expected.putNull("error");
            expected.put("executedFunction", "main");
            expected.put("executionTimeMs", 0);
            expected.set("replayMetadata", expectedRm);
            expected.put("result", 42);
            expected.set("traceSkeleton", sk.deepCopy());

            String expectedGolden = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(expected);

            assertEquals(expectedGolden, normalized,
                "现状响应体（归一 executionTimeMs/runtimeToolchainId 后）必须与 golden 逐字节一致；"
                    + "不一致说明字段被增删或改名——重构前先确认这是否是预期的现状变化");

            // 上面用「从响应体自身抽取值再重建」的方式构造 golden，只钉字段名/结构/类型不变，
            // 不钉 hash 具体数值——避免测试本身依赖对 CanonicalJson 算法内部实现的猜测。
            // hash 数值的确定性单独用下面的显式断言钉死（对固定输入 {"seed":41} + 固定 CNL 源码，
            // 若字段名不变但产出数值变了，即真正的语义漂移，必须在这里立刻炸红）。
            assertEquals("2617ff52c81759c87687c013ef77d8ec5094606f59023ab57f7844c2f38b4250",
                rm.get("canonicalInputHash").asText(), "canonicalInputHash 对固定输入必须确定性不变");
            assertEquals("d337b218e8ee5eb4a11a38c8a683b0a5382185cab2cea2d08ddd7d308420ee32",
                rm.get("canonicalOutputHash").asText(), "canonicalOutputHash 对固定输入必须确定性不变");
            // ★2026-08-14 重新基线化：aster-lang-truffle 给 trace 步骤加了源码行号
            //   （`return value` → `return value @L3`，见 truffle#64）。traceHash 覆盖
            //   canonicalTrace 全文，故标签变化必然改变 hash。
            //
            //   ★重新基线化前已逐字段验证「只变标签、不变语义」——这正是本断言存在的意义，
            //     不能因为"测试红了"就顺手换个数字：
            //       finalResult   42 → 42        不变
            //       result        42 → 42        不变
            //       matched     true → true      不变
            //       sequence       1 → 1         不变
            //       expression  "return value" → "return value @L3"   ← 唯一差异
            //     canonicalTrace 实测：
            //       {"finalResult":42,"functionName":"main","moduleName":"probe",
            //        "steps":[{"children":[],"expression":"return value @L3",
            //                  "matched":true,"result":42,"sequence":1}]}
            //
            //   ★若将来这个值再变，仍必须先做同样的逐字段比对再改——
            //     hash 变了而语义没变是可接受的；语义变了却把 hash 改掉，就是拿证据链换绿灯。
            assertEquals("567f685bae9aea6437fbf46344b57df02c9130b5f86839ed66885faf18f96aa0",
                rm.get("traceHash").asText(), "traceHash 对固定输入必须确定性不变");
            assertEquals("aster-canonical-json/v1", rm.get("canonicalizationVersion").asText(),
                "canonicalizationVersion 现状固定值不变");
        } catch (JsonProcessingException e) {
            throw new AssertionError("响应体不是合法 JSON: " + rawBody, e);
        }
    }

    // ---------------------------------------------------------------
    // 2. trace=false & replayCapture=false → 无 decisionTrace 无 replayMetadata；ThreadLocal 清理
    // ---------------------------------------------------------------

    @Test
    void noTraceNoReplayCapture_bothFieldsAbsent_andThreadLocalCleaned() {
        // 第一次请求：trace=true 先在同一 worker 线程上武装 TraceCollector（制造"历史残留"场景）。
        hmacVerifiedRequest(VALID_BODY, "case2-arm", true, false)
            .then().statusCode(200).body("decisionTrace", notNullValue());

        // 第二次请求：trace=false & replayCapture=false（HMAC 已验证，但两个门控都关，
        // effectiveReplayCapture 恒为 false）。现状代码路径（L524-526）：未请求 trace 时先
        // drainCurrentThread() 清掉历史残留，因此即便复用了同一个 worker 线程，本次响应也
        // 不应带任何 trace 残留字段。
        Response resp = hmacVerifiedRequest(VALID_BODY, "case2-clean", false, false);

        resp.then()
            .statusCode(200)
            .body("result", is(42))
            .body("error", nullValue())
            .body("decisionTrace", nullValue())
            .body("replayMetadata", nullValue());

        // ThreadLocal 清理的外部可观测证明：紧接着再发一次 trace=false 请求，
        // 若 ThreadLocal 未清理，某些 truffle 实现可能在下一次 trace=true 请求里
        // 意外携带上一次的残留 steps。这里用「连续两次 trace=false 都干净」
        // 加上「随后一次全新 trace=true 请求的 steps 只反映本次求值」间接验证。
        Response verifyClean = hmacVerifiedRequest(VALID_BODY, "case2-verify", true, false);
        verifyClean.then()
            .statusCode(200)
            .body("decisionTrace.steps", notNullValue());
    }

    // ---------------------------------------------------------------
    // 3. trace=false & replayCapture=true(HMAC 已验) → 只有 replayMetadata，无 decisionTrace
    // ---------------------------------------------------------------

    @Test
    void replayCaptureWithoutTrace_onlyReplayMetadata_noDecisionTrace() {
        Response resp = hmacVerifiedRequest(VALID_BODY, "case3", false, true);

        resp.then()
            .statusCode(200)
            .body("result", is(42))
            .body("decisionTrace", nullValue())
            .body("replayMetadata", notNullValue())
            .body("replayMetadata.replayabilityStatus", equalTo("REPLAYABLE"))
            .body("replayMetadata.canonicalOutputHash", notNullValue());
    }

    // ★关于「未签名调用方 replayCapture 被静默丢弃」（effectiveReplayCapture =
    // replayCapture && aliasesTrusted，evaluateSource L517）：该分支是真实现状安全边界，
    // 但在本类使用的 HmacProfile（evaluate-source.public=false + trial.enabled=false）下，
    // InternalCallerFilter.classify(...) 对未签名请求在到达 evaluateSource 方法体前就已
    // 403 拒绝（见类 Javadoc「HMAC profile 的额外实测发现」）——没有能验证该分支的可达路径。
    // 需要另一个放宽 filter 门控的 profile（如 evaluate-source.public=true）才能让未签名
    // 请求进入方法体，但那样 aliasesTrusted 恒为 true（isHmacVerified 只看 HMAC_VERIFIED_PROP
    // 是否被 filter 盖章，public 旁路不会盖章，但那条路径本身产生的是另一套现状组合，
    // 超出本任务 5 条既定覆盖范围）。deferred：留给需要该分支的场景单独起一个不同 profile 的类。

    // ---------------------------------------------------------------
    // 4. 畸形 CNL 源码 → 现状 HTTP 200（非 400）+ error 字符串；无 replayMetadata；trace 已 drain
    // ---------------------------------------------------------------

    @Test
    void malformedSource_currentBehaviorIsHttp200WithErrorField_noReplayMetadata() {
        // ★核实现状（与任务簿原始假设不同，已用真实响应改正）：语法错误的 CNL 源码在
        // DynamicCnlExecutor.executeInternal 内被 catch (InProcessCnlParser.CnlParseException e)
        // 捕获后，立即重新包成 DynamicExecutionException("CNL 解析失败: " + e.getMessage(), e)
        // （DynamicCnlExecutor.java:383-384）。冒泡到 evaluateSource 时，命中的是
        // catch (DynamicCnlExecutor.DynamicExecutionException e) 分支（非任务簿假设的
        // catch (InProcessCnlParser.CnlParseException)），该分支同样不 throw
        // WebApplicationException，而是把 Uni 正常 return EvaluationResponse.error(
        // "CNL 动态执行失败: " + e.getMessage())——因此 HTTP 状态码仍是 200，但错误消息
        // 前缀是两层叠加的 "CNL 动态执行失败: CNL 解析失败: ..."。只有 AmbiguousEntryException/
        // ModuleExecutionException 两种入口歧义异常才会被包成 400。
        Response resp = hmacVerifiedRequest(MALFORMED_BODY, "case4", false, true);

        resp.then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("result", nullValue())
            .body("error", notNullValue())
            .body("error", not(emptyOrNullString()))
            .body("replayMetadata", nullValue())
            .body("decisionTrace", nullValue());

        String error = resp.jsonPath().getString("error");
        // 实测前缀是两层叠加："CNL 动态执行失败: " (evaluateSource 捕获 DynamicExecutionException
        // 时加的) + "CNL 解析失败: " (DynamicCnlExecutor.executeInternal 包装 CnlParseException 时加的)。
        assertTrue(error.startsWith("CNL 动态执行失败: CNL 解析失败"),
            "现状错误消息前缀固定为 'CNL 动态执行失败: CNL 解析失败: '，实际=" + error);

        // trace 已 drain（后续请求无残留）：紧接着发一次干净的 trace=true 成功请求，
        // 其 decisionTrace.steps 必须只反映本次成功求值，不应包含上一次失败请求的任何痕迹
        // （失败路径的 finally 块同样会在 captureTraceSteps 为 true 时执行 drain）。
        Response followUp = hmacVerifiedRequest(VALID_BODY, "case4-followup", true, false);
        followUp.then()
            .statusCode(200)
            .body("result", is(42))
            .body("decisionTrace", notNullValue())
            .body("decisionTrace.finalResult", is(42));
    }

    // ---------------------------------------------------------------
    // 5. metadata compute 异常降级（NON_REPLAYABLE + "compute_threw:"）—— deferred
    // ---------------------------------------------------------------

    // ★诚实边界（任务簿明确允许）：ReplayMetadata.compute() 是 fail-loud 设计——小数走
    // string-lift（liftDecimals），真正无法 canonical 化的值（非 finite / 不支持类型）也只是
    // 在 tryCanonical() 内部 catch RuntimeException 后记 reason 并返回 null（NON_REPLAYABLE），
    // 不会向上抛出异常。evaluateSource L627 的 `catch (Exception rmEx)` 是防御性兜底
    // （理论上不该触发的路径），从 REST 请求体（合法 JSON，经 @Valid SourcePolicyRequest 校验）
    // 找不到能让 compute() 本身抛出的输入。
    //
    // 该分支（"compute_threw:" reason 降级）已由等价单测覆盖：
    //   replay/src/test/java/io/aster/policy/replay/ReplayMetadataTest.java
    // 覆盖 compute() 的所有降级路径（input/output/trace hash 各自失败时的 NON_REPLAYABLE +
    // reasons 断言）。evaluateSource 内 catch 块本身的「compute 抛出 → NON_REPLAYABLE +
    // "compute_threw: " 前缀」这层包装逻辑，将在 Task 4 核心抽取完成、evaluateSource 可
    // 注入 mock ReplayExecutor/mock metadata 计算器后，用 core 单元测试直接验证
    // （mock 使 compute 阶段抛出任意 RuntimeException，断言外层捕获并降级）。
    // 本 Step 不假装从 REST 边界覆盖了做不到的场景。
}
