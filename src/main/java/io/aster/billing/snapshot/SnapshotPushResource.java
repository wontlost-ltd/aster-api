package io.aster.billing.snapshot;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Snapshot 推送接收端（cloud → aster-api）
 *
 * cloud 在以下场景调用：
 *   - user.plan / subscriptionStatus / aiBannedUntil 变更
 *   - apiKey 创建 / 撤销
 *   - DUN-4 auto-downgrade
 *
 * 安全：HMAC 签名（共享 ASTER_PLAN_GATE_HMAC_KEY）
 */
@Path("/api/internal/snapshot")
public class SnapshotPushResource {

    private static final Logger LOG = Logger.getLogger(SnapshotPushResource.class);

    @Inject
    LocalQuotaSnapshotService snapshot;

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    /**
     * 迁移开关：是否仍接受不绑 body/nonce 的 v1 签名。
     *
     * <p>默认 {@code true}，让 aster-api 可以先于 aster-cloud 发版而不中断 snapshot 同步
     * （否则 cloud 未升级期间 plan / apiKey 状态会停止下发）。
     * cloud 侧完成 v2 改造并发版后，置为 {@code false} 完成硬切。
     */
    @ConfigProperty(name = "aster.security.snapshot.accept-legacy-signature", defaultValue = "true")
    boolean acceptLegacySignature;

    /** nonce 一次性校验（复用全局 NonceService，与 InternalCallerFilter 同一套存储）。 */
    @Inject
    io.aster.policy.security.NonceService nonceService;

    @POST
    @Path("/user/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response pushUser(
        @PathParam("userId") String userId,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature,
        @HeaderParam("X-Aster-Nonce") String nonce,
        String bodyJson
    ) {
        Response auth = verify("POST", "/api/internal/snapshot/user/" + userId,
            timestamp, signature, nonce, bodyJson);
        if (auth != null) return auth;

        try {
            JsonObject json = new JsonObject(bodyJson);
            UserSnapshot s = new UserSnapshot(
                userId,
                json.getString("plan", "free"),
                json.getLong("apiCallsLimit", 0L),
                json.getString("subscriptionStatus", null),
                json.getLong("aiBannedUntilEpochMs", null),
                json.getLong("gracePeriodEndsEpochMs", null)
            );
            snapshot.setUser(s);
            LOG.infof("snapshot user pushed: userId=%s plan=%s limit=%d",
                userId, s.plan(), s.apiCallsLimit());
            return Response.ok("{\"ok\":true,\"userId\":\"" + userId + "\"}").build();
        } catch (Exception e) {
            LOG.warnf("pushUser failed: %s", e.getMessage());
            return Response.status(400).entity("{\"error\":\"bad_payload\"}").build();
        }
    }

    @POST
    @Path("/apikey/{keyHash}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response pushApiKey(
        @PathParam("keyHash") String keyHash,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature,
        @HeaderParam("X-Aster-Nonce") String nonce,
        String bodyJson
    ) {
        Response auth = verify("POST", "/api/internal/snapshot/apikey/" + keyHash,
            timestamp, signature, nonce, bodyJson);
        if (auth != null) return auth;

        if (keyHash.length() != 64) {
            return Response.status(400).entity("{\"error\":\"bad_keyhash\"}").build();
        }

        try {
            JsonObject json = new JsonObject(bodyJson);
            boolean valid = json.getBoolean("valid", false);
            ApiKeySnapshot s;
            if (valid) {
                s = new ApiKeySnapshot(
                    true, null,
                    json.getString("apiKeyId"),
                    json.getString("userId"),
                    json.getString("tenantId"),
                    json.getString("plan"),
                    json.getString("role"),
                    json.getLong("revokedAtEpochMs", null)
                );
            } else {
                s = ApiKeySnapshot.invalid(json.getString("reason", "invalid"));
            }
            snapshot.setApiKey(keyHash, s);
            LOG.infof("snapshot apikey pushed: hash=%.8s... valid=%s", keyHash, s.valid());
            return Response.ok("{\"ok\":true}").build();
        } catch (Exception e) {
            LOG.warnf("pushApiKey failed: %s", e.getMessage());
            return Response.status(400).entity("{\"error\":\"bad_payload\"}").build();
        }
    }

    /**
     * 校验内部 snapshot 推送的 HMAC 签名。
     *
     * <h2>v2 canonical（当前）</h2>
     * <pre>
     *   method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + sha256hex(body)
     * </pre>
     *
     * <p>★为什么必须绑 body 与 nonce（2026-08-17 审计）：v1 只签
     * {@code method\npath\nts}，签名与请求体无关，也无一次性凭证。后果是——
     * 截获任意一条合法签名后，攻击者可在 5 分钟时间窗内
     * <b>替换请求体</b>（例如把 {@code role} 改成 ADMIN、把 {@code apiCallsLimit}
     * 改成无限）或<b>原样重放</b>，签名依然通过。而本端点写入的正是鉴权决策所依赖的数据。
     *
     * <h2>迁移</h2>
     *
     * <p>{@code aster.security.snapshot.accept-legacy-signature} 默认 {@code true}，
     * 在 aster-cloud 完成 v2 改造发版前同时接受 v1，避免跨仓发版顺序造成 snapshot
     * 同步中断（plan / apiKey 状态将停止下发）。cloud 发版后置为 {@code false} 硬切。
     * v1 路径每次命中都打 warn，便于确认流量已迁完。
     */
    private Response verify(String method, String path, String timestamp,
                            String signature, String nonce, String body) {
        Response basic = verifyPreconditions(timestamp, signature);
        if (basic != null) return basic;

        long ts = Long.parseLong(timestamp);
        String bodySha = sha256Hex(body == null ? "" : body);

        // v2：绑定 nonce + body。nonce 必填才能走 v2。
        if (nonce != null && !nonce.isBlank()) {
            String expectedV2 = sign(hmacKey.get(), canonicalV2(method, path, ts, nonce, bodySha));
            if (!constantTimeEquals(expectedV2, signature)) {
                return Response.status(401).entity("{\"error\":\"invalid_signature\"}").build();
            }
            // 签名通过后才消费 nonce：避免未授权方用伪造签名刷爆 nonce 表。
            try {
                nonceService.ensureFresh("snapshot-push", nonce, bodySha);
            } catch (jakarta.ws.rs.WebApplicationException replay) {
                return Response.status(409).entity("{\"error\":\"nonce_replay\"}").build();
            }
            return null;
        }

        // v1 legacy：不绑 body、无 nonce，可被替换 body / 重放。仅迁移窗口内接受。
        if (!acceptLegacySignature) {
            return Response.status(401)
                .entity("{\"error\":\"missing_nonce\",\"detail\":\"v2 signature required\"}").build();
        }
        String expectedV1 = sign(hmacKey.get(), canonicalV1(method, path, ts));
        if (!constantTimeEquals(expectedV1, signature)) {
            return Response.status(401).entity("{\"error\":\"invalid_signature\"}").build();
        }
        LOG.warnf("snapshot push accepted LEGACY v1 signature (no body/nonce binding): path=%s"
            + " — 该调用方尚未升级；升级完成后请置 aster.security.snapshot.accept-legacy-signature=false", path);
        return null;
    }

    /** 时间戳/签名头的基础校验（两个版本共用）。 */
    private Response verifyPreconditions(String timestamp, String signature) {
        // ★安全审计修复：原为 `if (hmacKey.isEmpty()) return null;`——**缺密钥即关闭鉴权**。
        // 本端点写入的正是鉴权决策所依赖的数据：pushApiKey 用请求体自带的 tenantId/role
        // 构造 ApiKeySnapshot(valid=true) 并写入缓存，随后被 ApiKeyVerifierService 当作
        // 合法验证结果返回。故密钥缺失时放行 = 任何人可自选租户与 ADMIN 角色通过鉴权。
        //
        // /api/internal/* 已被 RequestSignatureFilter 与 TenantFilter:124 双双豁免
        // （后者给出的理由正是「自带 HMAC 验签」——该前提在缺密钥时不成立），
        // 且 aster.plan-gate.hmac-key 默认值为空、启动时不强制校验，故此路径可由配置到达。
        //
        // 与同仓其余校验器对齐为 fail-closed：InternalCallerFilter:295、
        // AdminHmacVerifier:76、LexiconAdminResource:466、ApiKeyCacheResource:60。
        if (hmacKey.isEmpty() || hmacKey.get().isBlank()) {
            LOG.warn("snapshot push called without HMAC key configured; rejecting");
            return Response.status(403).entity("{\"error\":\"hmac_not_configured\"}").build();
        }
        if (timestamp == null || signature == null) {
            return Response.status(401).entity("missing signature headers").build();
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Response.status(401).entity("invalid timestamp").build();
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > 300) {
            return Response.status(401).entity("stale timestamp").build();
        }
        return null;
    }

    /**
     * v2 canonical 的<b>唯一</b>构造点。
     *
     * <p>包级可见是为了让契约测试断言**生产代码产出的串**，而不是在测试里
     * 再抄一遍算法——「测试自己复刻算法」是本仓已确认过的假绿模式
     * （那样测的是 HMAC 原语对输入敏感，数学上恒真，而非生产代码真的这么拼）。
     *
     * <p>对端实现：{@code aster-cloud/src/lib/snapshot-pusher.ts} 的 {@code callAsterApi}。
     * 两侧必须逐字节一致，任何一侧改格式都要同步改另一侧。
     */
    static String canonicalV2(String method, String path, long ts, String nonce, String bodySha) {
        return method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + bodySha;
    }

    /** v1 legacy canonical（不绑 body/nonce）。仅迁移窗口内使用。 */
    static String canonicalV1(String method, String path, long ts) {
        return method + "\n" + path + "\n" + ts;
    }

    /** 请求体的 SHA-256 hex，用于把 body 绑进签名。 */
    static String sha256Hex(String body) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
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
