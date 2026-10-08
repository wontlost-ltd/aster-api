package io.aster.security.apikey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * cloud→api 内部调用 HMAC canonical v3 的测试侧参考实现（ADR 0042 §4.1）。
 *
 * <p>所有自签内部调用的测试统一走这里，确保只存在一份 canonical 构造；cloud 侧
 * {@code signInternalCallerHeaders} 以此为准逐字节对齐。刻意不复用
 * {@link InternalCallerFilter} 的私有实现，使本类成为独立的对照。
 *
 * <p>canonical v3（以 {@code \n} 连接）：
 * {@code method, path, query, ts, nonce, sha256(body), tenant, role, userId, businessRoles}
 * <ul>
 *   <li>query：原始查询串，不含 {@code ?}，无则空串；</li>
 *   <li>businessRoles：trim、去空、去重、排序后以 {@code ,} 拼接；头里按调用方给的顺序发送，
 *       以证明服务端对头顺序不敏感。</li>
 * </ul>
 */
public final class InternalCallerSigning {

    private InternalCallerSigning() {
    }

    /**
     * 生成一组完整的内部调用头（时间戳取当前秒，nonce 每次唯一）。
     *
     * <p>tenant / role / userId / roles 为空时不发对应头（服务端按空串签名）。返回可变 Map，
     * 便于篡改类用例复制后替换单个头。
     */
    public static Map<String, String> headers(String key, String method, String path, String query,
                                              String body, String tenant, String role, String userId,
                                              List<String> roles) {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "sig-v3-" + System.nanoTime();
        String canonical = String.join("\n", method, path, nz(query), Long.toString(ts), nonce,
            sha256Hex(nz(body)), nz(tenant), nz(role), nz(userId), String.join(",", normalize(roles)));

        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Internal-Caller", "cloud-bff");
        h.put("X-Aster-Timestamp", Long.toString(ts));
        h.put("X-Aster-Nonce", nonce);
        h.put("X-Internal-Signature", hmac(key, canonical));
        putIfPresent(h, "X-Tenant-Id", tenant);
        putIfPresent(h, "X-User-Role", role);
        putIfPresent(h, "X-User-Id", userId);
        putIfPresent(h, "X-User-Business-Roles", roles == null ? null : String.join(",", roles));
        return h;
    }

    private static List<String> normalize(List<String> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream().filter(Objects::nonNull).map(String::trim)
            .filter(r -> !r.isEmpty()).distinct().sorted().toList();
    }

    private static void putIfPresent(Map<String, String> h, String name, String value) {
        if (value != null && !value.isEmpty()) {
            h.put(name, value);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmac(String key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
