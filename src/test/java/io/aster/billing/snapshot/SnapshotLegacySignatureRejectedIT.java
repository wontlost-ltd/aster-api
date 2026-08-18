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
        //   而非「服务端把 body 绑进了 canonical」。真正锁住 body 绑定的是
        //   下面的 serverMustBindBodyAndNonceIntoCanonical。此条保留作端到端冒烟。
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

    @Test
    void serverMustBindBodyAndNonceIntoCanonical() {
        // ★真正有鉴别力的那条：带上 nonce 头（走 v2 分支），但签名只覆盖
        //   v1 的三段 `method\npath\nts`——**不含 nonce、不含 bodySha**。
        //
        //   若服务端确实把 nonce+bodySha 绑进了 canonical，这个签名必然对不上 → 401。
        //   若服务端「漏绑」（例如 canonicalV2 少拼 bodySha、或对空串算 sha 而忽略真实
        //   body、或干脆拿 v1 canonical 去比），它就会**通过** → 用例变红。
        //
        //   与上一条的区别：上一条改的是 body（签名必错，恒 401，测不出绑没绑）；
        //   这一条改的是**签名覆盖的范围**，body 与 nonce 都如实发送——
        //   于是「服务端有没有把它们算进去」成为唯一变量。
        long now = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        String v1ShapedSig = hmacHex("POST\n" + USER_PATH + "\n" + now);

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Nonce", nonce)
            .header("X-Aster-Signature", v1ShapedSig)
            .header("Content-Type", "application/json")
            .body(BODY)
            .when()
            .post(USER_PATH)
            .then()
            .statusCode(401)
            .body(containsString("invalid_signature"));
    }
}
