package io.aster.billing.snapshot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * {@code accept-legacy-signature=false} 时 v1 签名必须被拒（2026-08-17 安全审计收尾）。
 *
 * <p>v1 canonical 只签 {@code method\npath\nts}——签名与请求体无关、也无一次性凭证。
 * 截获任意一条合法签名后，攻击者可在时间窗内<b>替换请求体</b>
 * （把 {@code role} 改成 ADMIN、把 {@code apiCallsLimit} 改成无限）或<b>原样重放</b>，
 * 签名依然通过。而本端点写入的正是 aster-api 鉴权决策所依赖的数据。
 *
 * <p>迁移期默认 {@code accept-legacy-signature=true} 同时接受 v1/v2，避免跨仓发版顺序
 * 造成 snapshot 同步中断。两侧都上线后置 {@code false} 硬切。
 *
 * <p>★本用例存在的理由：硬切开关的<b>enforcing 分支此前零覆盖</b>——
 * 也就是说「关掉兼容窗口后 v1 真的被拒」从未被验证过。一个从未执行过的安全控制
 * 不能算已落地。这里把它钉死：
 * <ul>
 *   <li>flag=false 时 v1 → 401，且响应指明缺 nonce（不是笼统的 invalid_signature）</li>
 *   <li>flag=false 时 v2 → <b>不得</b>被误伤（否则硬切会打断所有 snapshot 下发，
 *       而 cloud 侧是 fail-open 只打日志，故障会非常安静）</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(SnapshotLegacySignatureRejectedIT.StrictProfile.class)
class SnapshotLegacySignatureRejectedIT {

    private static final String HMAC_KEY = "it-shared-key-32bytes-minimum-len";

    /** 硬切态：拒绝一切 v1 签名。 */
    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.snapshot.accept-legacy-signature", "false"
            );
        }
    }

    private static String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final String USER_PATH = "/api/internal/snapshot/user/user-legacy-probe";
    private static final String BODY = "{\"planTier\":\"PRO\",\"apiCallsLimit\":100}";

    @Test
    void legacyV1SignatureIsRejectedWhenHardCutIsOn() {
        long now = System.currentTimeMillis() / 1000;
        // v1：只签 method\npath\nts，且不带 X-Aster-Nonce 头
        String sig = hmacHex("POST\n" + USER_PATH + "\n" + now);

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Signature", sig)
            .header("Content-Type", "application/json")
            .body(BODY)
            .when()
            .post(USER_PATH)
            .then()
            .statusCode(401)
            // 必须明确指出缺 nonce，而不是笼统 invalid_signature——
            // 否则运维排查硬切故障时会误以为是密钥不匹配。
            .body(containsString("missing_nonce"));
    }

    @Test
    void v2SignatureStillAcceptedWhenHardCutIsOn() {
        // ★同等重要的一半：硬切不得误伤 v2。若这条红了，置 false 会打断
        //   全部 snapshot 下发，而 cloud 侧 fail-open 只打日志 —— 故障非常安静。
        long now = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        String sig = hmacHex("POST\n" + USER_PATH + "\n" + now + "\n" + nonce + "\n" + sha256Hex(BODY));

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Nonce", nonce)
            .header("X-Aster-Signature", sig)
            .header("Content-Type", "application/json")
            .body(BODY)
            .when()
            .post(USER_PATH)
            .then()
            // 关注点是"没被签名校验拦下"，故只断言不是 401。
            // 后续业务处理的结果不属于本用例的契约。
            .statusCode(org.hamcrest.Matchers.not(401));
    }

    @Test
    void v2WithTamperedBodyIsRejectedWhenHardCutIsOn() {
        // v2 的核心价值就是绑 body：签名照原样发，但请求体被换成提权载荷。
        // 这正是 v1 挡不住、v2 必须挡住的攻击。
        //
        // ★这条用例的鉴别力有限（对抗性审查实测指出）：body 一改签名必然对不上，
        //   401 恒成立——它实际测的是「HMAC 对输入敏感」（数学上恒真），
        //   而非「服务端把 body 绑进了 canonical」。真正锁住逐项绑定的是
        //   下面的 serverMustBindBodyAndNonceIntoCanonical（逐项差分）。
        //   此条保留作端到端冒烟。
        long now = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        String sig = hmacHex("POST\n" + USER_PATH + "\n" + now + "\n" + nonce + "\n" + sha256Hex(BODY));

        String tampered = "{\"planTier\":\"ENTERPRISE\",\"apiCallsLimit\":999999999}";

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Nonce", nonce)
            .header("X-Aster-Signature", sig)
            .header("Content-Type", "application/json")
            .body(tampered)
            .when()
            .post(USER_PATH)
            .then()
            .statusCode(401)
            .body(containsString("invalid_signature"));
    }

    /**
     * 用给定 canonical 签名发一次 v2 形态的请求（nonce 与 body 均如实发送），断言被拒。
     *
     * <p>★{@code why} 必须进入断言消息（终审发现）：此前它只出现在形参列表、
     * 未传入任何 assert，四条断言若失败一律报 {@code Expected 401 but was 200}，
     * 看不出是哪一项漏绑；且四条同处一个 {@code @Test} 顺序执行，
     * 第一条失败即中止，会掩盖后续项的状态。
     */
    private void expectRejected(String canonical, String why) {
        long now = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        // canonical 里的占位符在此实例化，确保时间戳/nonce 与请求头一致
        String message = canonical
            .replace("{ts}", String.valueOf(now))
            .replace("{nonce}", nonce)
            .replace("{sha}", sha256Hex(BODY));

        int status = given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Nonce", nonce)
            .header("X-Aster-Signature", hmacHex(message))
            .header("Content-Type", "application/json")
            .body(BODY)
            .when()
            .post(USER_PATH)
            .then()
            .extract()
            .statusCode();

        org.junit.jupiter.api.Assertions.assertEquals(401, status,
            "签名未覆盖该项时必须被拒 —— " + why);
    }

    /**
     * 用「结构正确的 5 段 canonical，但 sha 取自**另一个 body**」发请求，断言被拒。
     *
     * <p>★这条锁住的是终审实测漏掉的 M3：服务端「绑了 body 但算错输入」
     * （例如对空串算 sha 而忽略真实 body）。前面的逐项差分抓不到它——
     * 那些签名段数少于 5，与 5 段 canonical 必然不匹配、401 恒成立；
     * 而本条签名段数、顺序、nonce 全部正确，<b>唯一变量就是 sha 的输入</b>：
     * 服务端若对空串（或任何非真实 body）算 sha，就会与本签名匹配 → 200 → 用例红。
     */
    private void expectRejectedWithForeignBodySha(String foreignBody, String why) {
        long now = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        String message = "POST\n" + USER_PATH + "\n" + now + "\n" + nonce
            + "\n" + sha256Hex(foreignBody);

        int status = given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Nonce", nonce)
            .header("X-Aster-Signature", hmacHex(message))
            .header("Content-Type", "application/json")
            .body(BODY)          // 发的是 BODY，签名却基于 foreignBody
            .when()
            .post(USER_PATH)
            .then()
            .extract()
            .statusCode();

        org.junit.jupiter.api.Assertions.assertEquals(401, status,
            "服务端必须对**真实 body** 计算 sha —— " + why);
    }

    @Test
    void serverMustBindBodyAndNonceIntoCanonical() {
        // ★逐项差分（2026-08-18 复评重写）：签名覆盖「正确 v2 **减去恰好一项**」。
        //
        //   上一版只发 v1 三段签名，复评实测其鉴别力**远弱于命名承诺**：
        //   只要服务端 canonical 比三段多任何一段，签名就必然对不上、401 恒成立。
        //   于是「漏绑 bodySha」（M2）、「对空串算 sha」（M3）、「漏绑 nonce」（M5）、
        //   「nonce/sha 顺序颠倒」（M7）四个变异**它一个都抓不到**——
        //   而注释里恰恰点名说能抓 M2/M3。这正是本轮要修的「注释声称 ≠ 实现」。
        //
        //   逐项差分让每一项的缺失都成为唯一变量：
        //     缺 bodySha → 服务端若真绑了 body，签名必不匹配 → 401（否则用例红）
        //     缺 nonce   → 同理
        //     顺序颠倒   → canonical 是有序拼接，换序即不同消息
        String path = USER_PATH;
        expectRejected("POST\n" + path + "\n{ts}\n{nonce}",
            "缺 bodySha：服务端必须把 body 算进 canonical");
        expectRejected("POST\n" + path + "\n{ts}\n{sha}",
            "缺 nonce：服务端必须把 nonce 算进 canonical");
        expectRejected("POST\n" + path + "\n{ts}\n{sha}\n{nonce}",
            "nonce 与 sha 顺序颠倒：canonical 是有序拼接");
        expectRejected("POST\n" + path + "\n{ts}",
            "整体退化成 v1 三段");
        expectRejected("POST\n" + path + "\n{nonce}\n{sha}",
            "缺 ts：时间戳同样必须进 canonical");

        // ★M3：结构完全正确、仅 sha 的**输入**不同 —— 上面四条都抓不到它
        //   （它们段数少于 5，与 5 段 canonical 必然不匹配、401 恒成立）。
        expectRejectedWithForeignBodySha("",
            "服务端对空串算 sha、忽略真实 body（终审实测的 M3）");
        expectRejectedWithForeignBodySha(
            "{\"planTier\":\"ENTERPRISE\",\"apiCallsLimit\":999999999}",
            "服务端对另一个 body 算 sha");
    }
}
