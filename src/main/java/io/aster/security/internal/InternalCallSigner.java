package io.aster.security.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * aster-api → aster-cloud 内部调用的**签名单一实现**（2026-07-29 审计修复）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>此前 5 个调用点（{@code ApiKeyVerifierService}、{@code ApiQuotaGuard} ×2、
 * {@code AiUsageReporter}、{@code SafetyEventReporter}）各自手写签名，canonical
 * 一律是 {@code method\npath\ntimestamp}——<b>不绑定 body、不绑定 query、无 nonce</b>。
 *
 * <p>后果：攻击者只要拿到任意一次签名（代理日志、SSRF、镜像流量），就能在 300s
 * 时间戳窗口内<b>换掉 body 无限重放</b>。打 {@code /api/internal/api/usage} 可为任意
 * userId 伪造用量记录、篡改计费归属；打 {@code /api/internal/apikey/verify} 可枚举 key。
 *
 * <p>对照：本仓<b>接收</b>方向的 {@code InternalCallerFilter} 早已加固为 7 字段
 * canonical（含 nonce + bodySha256 + tenant + role），而<b>发出</b>方向从未同步——
 * 加固只做了一半。
 *
 * <h2>v2 canonical</h2>
 *
 * <pre>{@code method\npath\ntimestamp\nnonce\nbodySha256Hex}</pre>
 *
 * <p>与 aster-cloud {@code verifyInternalSignature} 的 v2 分支逐字对齐。
 * GET 等无 body 的请求，bodySha256 取空串的 SHA-256（而非留空），
 * 保证「没有 body」本身也被签名覆盖，不能被替换成有 body 的请求。
 *
 * <h2>★上线顺序（三步，缺一不可）</h2>
 * <ol>
 *   <li>aster-cloud 先发布 <b>双接受</b>（v2 优先、v1 兼容）——已在 cloud 侧完成；</li>
 *   <li>aster-api 切到 v2（本类）；</li>
 *   <li>观察 cloud 侧 {@code usedLegacyCanonical} 归零后，置
 *       {@code ASTER_INTERNAL_ALLOW_LEGACY_SIG=false} 下线 v1。</li>
 * </ol>
 * <p>跳过第 1 步直接发本类，跨服务认证会在部署瞬间全断。
 */
public final class InternalCallSigner {

    private InternalCallSigner() {
    }

    /** 一次调用所需的签名材料；调用方把三者作为请求头发出。 */
    public record Signed(String timestamp, String nonce, String signature) {
    }

    /**
     * 按 v2 canonical 签名。
     *
     * @param hmacKey 共享密钥
     * @param method  HTTP 方法（大写）
     * @param path    请求路径（不含 query）
     * @param body    请求体；无 body 传空串
     */
    public static Signed sign(String hmacKey, String method, String path, String body) {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString();
        String bodyHash = sha256Hex(body == null ? "" : body);
        String canonical = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + bodyHash;
        return new Signed(String.valueOf(ts), nonce, hmac(hmacKey, canonical));
    }

    private static String hmac(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
