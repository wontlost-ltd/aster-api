package io.aster.security.internal;

import io.aster.policy.security.NonceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * cloud → aster-api 内部端点共用的 HMAC 签名校验器。
 *
 * <p>v2 canonical：{@code method\npath\nts\nnonce\nsha256hex(body)}，
 * 头为 {@code X-Aster-Timestamp / X-Aster-Nonce / X-Aster-Signature}，
 * 密钥为 {@code aster.plan-gate.hmac-key}。
 *
 * <p>校验顺序固定：密钥 → 头 → 时间戳 → 签名 → nonce。签名通过后才消费 nonce，
 * 避免未授权方用伪造签名刷爆 nonce 表。缺密钥一律 fail-closed（403）。
 *
 * <p>v1（不绑 body / nonce）仅在 {@link Policy#acceptLegacyV1()} 为 true 时接受。
 * 对端实现：{@code aster-cloud/src/lib/snapshot-pusher.ts} 的 {@code callAsterApi}，
 * 两侧 canonical 必须逐字节一致。
 */
@ApplicationScoped
public class InternalV2SignatureVerifier {

    private static final Logger LOG = Logger.getLogger(InternalV2SignatureVerifier.class);

    /** 时间戳允许的偏差（秒）。 */
    private static final long MAX_SKEW_SECONDS = 300;

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @Inject
    NonceService nonceService;

    /** 待校验的请求要素；{@code nonce} 为空白时视为 v1。 */
    public record Request(String method, String path, String timestamp, String signature,
                          String nonce, String body) {}

    /**
     * @param nonceScope    nonce 命名空间（不同端点互不干扰）
     * @param acceptLegacyV1 是否仍接受 v1 签名
     * @param logLabel      v1 告警里的调用方描述
     */
    public record Policy(String nonceScope, boolean acceptLegacyV1, String logLabel) {}

    /** 空 = 通过；否则为应原样返回给调用方的拒绝响应。 */
    public Optional<Response> verify(Request r, Policy p) {
        Optional<Response> basic = verifyPreconditions(r, p);
        if (basic.isPresent()) return basic;

        long ts = Long.parseLong(r.timestamp());
        String bodySha = sha256Hex(r.body() == null ? "" : r.body());
        String key = hmacKey.get();

        if (r.nonce() != null && !r.nonce().isBlank()) {
            String expected = sign(key, canonicalV2(r.method(), r.path(), ts, r.nonce(), bodySha));
            if (!constantTimeEquals(expected, r.signature())) {
                return Optional.of(invalidSignature());
            }
            try {
                nonceService.ensureFresh(p.nonceScope(), r.nonce(), bodySha);
            } catch (WebApplicationException replay) {
                return Optional.of(Response.status(409).entity("{\"error\":\"nonce_replay\"}").build());
            }
            return Optional.empty();
        }

        if (!p.acceptLegacyV1()) {
            return Optional.of(Response.status(401)
                .entity("{\"error\":\"missing_nonce\",\"detail\":\"v2 signature required\"}").build());
        }
        String expectedV1 = sign(key, canonicalV1(r.method(), r.path(), ts));
        if (!constantTimeEquals(expectedV1, r.signature())) {
            return Optional.of(invalidSignature());
        }
        LOG.warnf("%s accepted LEGACY v1 signature (no body/nonce binding): path=%s"
            + " — 该调用方尚未升级；升级完成后请置 aster.security.snapshot.accept-legacy-signature=false",
            p.logLabel(), r.path());
        return Optional.empty();
    }

    private Optional<Response> verifyPreconditions(Request r, Policy p) {
        // 缺密钥即关闭鉴权会让任何人可伪造内部调用，必须 fail-closed。
        if (hmacKey.isEmpty() || hmacKey.get().isBlank()) {
            LOG.warnf("%s called without HMAC key configured; rejecting", p.logLabel());
            return Optional.of(Response.status(403).entity("{\"error\":\"hmac_not_configured\"}").build());
        }
        if (r.timestamp() == null || r.signature() == null) {
            return Optional.of(Response.status(401).entity("missing signature headers").build());
        }
        long ts;
        try {
            ts = Long.parseLong(r.timestamp());
        } catch (NumberFormatException e) {
            return Optional.of(Response.status(401).entity("invalid timestamp").build());
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            return Optional.of(Response.status(401).entity("stale timestamp").build());
        }
        return Optional.empty();
    }

    private static Response invalidSignature() {
        return Response.status(401).entity("{\"error\":\"invalid_signature\"}").build();
    }

    /** v2 canonical 的唯一构造点；契约测试直接断言生产代码产出的串。 */
    public static String canonicalV2(String method, String path, long ts, String nonce, String bodySha) {
        return method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + bodySha;
    }

    /** v1 legacy canonical（不绑 body / nonce）。 */
    public static String canonicalV1(String method, String path, long ts) {
        return method + "\n" + path + "\n" + ts;
    }

    /** 请求体的 SHA-256 hex。 */
    public static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    /** HMAC-SHA256 hex。 */
    public static String sign(String key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
