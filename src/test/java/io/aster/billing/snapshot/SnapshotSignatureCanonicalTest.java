package io.aster.billing.snapshot;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Snapshot 推送签名的<b>跨仓 canonical 契约</b>（2026-08-17 安全审计）。
 *
 * <p>签名两端分处不同仓库：
 * <ul>
 *   <li>发送端：{@code aster-cloud/src/lib/snapshot-pusher.ts} 的 {@code callAsterApi}</li>
 *   <li>校验端：{@code SnapshotPushResource.verify}</li>
 * </ul>
 *
 * <p>两边的 canonical 必须逐字节一致，否则**所有** snapshot 推送会 401
 * （plan / apiKey 状态停止下发，且因 cloud 侧 fail-open 只打日志，故障会很安静）。
 * 本用例把 canonical 格式钉成可执行契约：任何一侧改格式而不改另一侧，这里就会红。
 *
 * <p>纯函数验证，不需要 Quarkus / 数据库上下文。
 */
class SnapshotSignatureCanonicalTest {

    private static final String KEY = "test-shared-secret";

    /**
     * 复刻 <b>aster-cloud</b> 侧 v2 签名（{@code snapshot-pusher.ts} 的 {@code callAsterApi}）。
     *
     * <p>这一份是<b>对端</b>实现的镜像，必须手写——它在另一个仓库、另一种语言里。
     * 而 aster-api 侧一律调用生产代码 {@link SnapshotPushResource#canonicalV2}，
     * 绝不在测试里复刻（「测试自己抄一遍算法」是本仓已确认的假绿模式）。
     */
    private static String cloudSideSignV2(String method, String path, long ts,
                                          String nonce, String body) {
        String bodySha = sha256Hex(body);
        String message = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + bodySha;
        return hmacHex(KEY, message);
    }

    @Test
    void cloudAndApiAgreeOnV2Canonical() {
        long ts = 1_755_000_000L;
        String nonce = "0f8e7d6c-1234-4a5b-8c9d-0e1f2a3b4c5d";
        String body = "{\"valid\":true,\"tenantId\":\"t-1\",\"role\":\"MEMBER\"}";
        String path = "/api/internal/snapshot/apikey/" + "a".repeat(64);

        // ★右侧调用**生产代码**的 canonical 构造器，不是测试里的副本。
        String apiSide = hmacHex(KEY,
            SnapshotPushResource.canonicalV2(
                "POST", path, ts, nonce, SnapshotPushResource.sha256Hex(body)));

        assertEquals(
            cloudSideSignV2("POST", path, ts, nonce, body),
            apiSide,
            "cloud 与 api 的 canonical 必须逐字节一致——不一致会让所有 snapshot 推送 401，"
                + "而 cloud 侧 fail-open 只打日志，故障非常安静"
        );
    }

    @Test
    void productionCanonicalHasExactlyFiveLines() {
        // 结构性断言：直接检查生产代码产出的串，防止「顺手加个字段」导致跨仓静默不兼容
        String canonical = SnapshotPushResource.canonicalV2(
            "POST", "/api/internal/snapshot/user/u-1", 1_755_000_000L, "n-1", "d".repeat(64));
        String[] lines = canonical.split("\n", -1);
        assertEquals(5, lines.length, "v2 canonical 必须恰好 5 行：method/path/ts/nonce/bodySha");
        assertEquals("POST", lines[0]);
        assertEquals("/api/internal/snapshot/user/u-1", lines[1]);
        assertEquals("1755000000", lines[2]);
        assertEquals("n-1", lines[3]);
        assertEquals("d".repeat(64), lines[4]);
    }

    @Test
    void productionSha256MatchesStandard() {
        // 空 body 的 SHA-256 是跨语言最容易出分歧的点（cloud 对 undefined 也须算空串摘要）
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SnapshotPushResource.sha256Hex(""),
            "空 body 摘要必须是标准 SHA-256(\"\")——两侧对空体的处理必须一致"
        );
    }

    @Test
    void signatureIsBoundToBody_tamperedBodyBreaksSignature() {
        long ts = 1_755_000_000L;
        String nonce = "n-1";
        String path = "/api/internal/snapshot/apikey/" + "a".repeat(64);

        String honest = "{\"valid\":true,\"tenantId\":\"t-1\",\"role\":\"MEMBER\"}";
        // 攻击形态：截获签名后把 role 提成 ADMIN、换个租户
        String tampered = "{\"valid\":true,\"tenantId\":\"victim\",\"role\":\"ADMIN\"}";

        assertNotEquals(
            cloudSideSignV2("POST", path, ts, nonce, honest),
            cloudSideSignV2("POST", path, ts, nonce, tampered),
            "★签名必须绑定 body：否则截获一条合法签名后可在时间窗内替换请求体，"
                + "把自己提权为任意租户的 ADMIN（本端点写入的正是鉴权决策数据）"
        );
    }

    @Test
    void signatureIsBoundToNonce_replayUsesDifferentSignature() {
        long ts = 1_755_000_000L;
        String body = "{\"valid\":true}";
        String path = "/api/internal/snapshot/user/u-1";

        assertNotEquals(
            cloudSideSignV2("POST", path, ts, "nonce-a", body),
            cloudSideSignV2("POST", path, ts, "nonce-b", body),
            "签名必须绑定 nonce，配合服务端一次性校验才能防重放"
        );
    }

    @Test
    void v1AndV2AreDistinguishable() {
        long ts = 1_755_000_000L;
        String path = "/api/internal/snapshot/user/u-1";
        String v1 = hmacHex(KEY, "POST\n" + path + "\n" + ts);
        String v2 = cloudSideSignV2("POST", path, ts, "n-1", "{}");
        assertNotEquals(v1, v2,
            "v1/v2 必须可区分，否则迁移窗口的 legacy 分支形同虚设");
    }

    private static String hmacHex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
