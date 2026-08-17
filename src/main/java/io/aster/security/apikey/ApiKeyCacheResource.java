package io.aster.security.apikey;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
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
 * API key 缓存失效回调
 *
 * cloud 在以下场景调用：
 *   - DUN-4 auto-downgrade 把 apiKeys.revokedAt 写入后
 *   - 用户主动撤销 API key
 *   - subscription 被 Stripe 删除
 *
 * 安全：复用 PlanGate HMAC 共享密钥（与 PlanCacheResource 同套）。
 */
@Path("/api/internal/apikey-cache")
public class ApiKeyCacheResource {

    private static final Logger LOG = Logger.getLogger(ApiKeyCacheResource.class);

    @Inject
    ApiKeyVerifierService verifier;

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @DELETE
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response invalidate(
        @PathParam("userId") String userId,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature
    ) {
        // ★2026-07-29 审计修复：原为 `if (hmacKey.isPresent())`——**缺密钥即关闭鉴权**
        // 而非拒绝。aster.plan-gate.hmac-key 未配、或 pod 先于 secret 挂载启动时，
        // DELETE /api/internal/apikey-cache/{userId} 完全无鉴权：/api/internal/* 已被
        // RequestSignatureFilter:42 与 TenantFilter:124 双双豁免，没有任何其他层兜底。
        // 反复刷缓存会把每个请求打成一次 cloud verify 往返；cloud 不可达时
        // doSlowPathVerify 返回 verify_unavailable → 该用户的合法 key 全部 401。
        //
        // 同仓所有同类校验器在同一配置下都是 fail-closed（InternalCallerFilter:295、
        // AdminHmacVerifier:76），本处当时是其中之一，现予对齐。
        //
        // ★订正（2026-08-17 审计）：本注释此前断言「本处是唯一的例外」，该断言当时**为假**——
        // SnapshotPushResource 与 PlanCacheResource 携带完全相同的 fail-open 模式，
        // 当时未做全仓回扫故被漏掉，两者已于本次一并修复。
        // 现已用正则 `(hmacKey|hmac|secret|signingKey|apiKey)\.(isPresent|isEmpty)\(\)`
        // 扫描 src/main，10 处命中全部核对为 fail-closed，population 闭合。
        // 后续新增同类校验器时请重跑该正则，勿再仅凭单点判断。
        if (hmacKey.isEmpty()) {
            LOG.warn("apikey-cache invalidate called without HMAC key configured; rejecting");
            return Response.status(403).entity("hmac_not_configured").build();
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
        String path = "/api/internal/apikey-cache/" + userId;
        String expected = sign(hmacKey.get(), "DELETE\n" + path + "\n" + ts);
        if (!constantTimeEquals(expected, signature)) {
            return Response.status(401).entity("invalid signature").build();
        }

        int n = verifier.invalidateForUser(userId);
        LOG.infof("apikey cache invalidated for user=%s, count=%d", userId, n);
        return Response.ok("{\"invalidated\":\"" + userId + "\",\"keys\":" + n + "}").build();
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
