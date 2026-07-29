package io.aster.llm.safety;

import io.aster.security.internal.InternalCallSigner;
import io.aster.billing.PlanGateConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 把 prompt 守门拦截事件写到 aster-cloud 的 /api/internal/ai/usage
 *
 * 目的：让 cloud 侧 anomaly Signal 4（24h jailbreak ≥ 3 → 24h 自动封禁）
 * 能"看到"aster-api 这边的拦截。
 *
 * 设计：
 *   - 复用 PlanGateConfig 的 HMAC 共享密钥（生产强制；dev 缺省时跳过签名）
 *   - 调用 fire-and-forget（非阻塞）；失败仅日志，不影响业务路径
 *   - 写入字段：status='blocked_unsafe' + safetyFlags.jailbreak_attempt=true + blocked_reason=ruleId
 */
@ApplicationScoped
public class SafetyEventReporter {

    private static final Logger LOG = Logger.getLogger(SafetyEventReporter.class);

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    @Inject
    PlanGateConfig config;
    // P0-R19: WebClient DCL consolidated into SharedWebClient

    /**
     * @param tenantId 用作 userId（aster-cloud 端 recordAiUsage 接受 userId）
     * @param callKind generate / suggest / complete
     * @param ruleId   命中的 PG 规则 id
     */
    public void reportBlocked(String tenantId, String callKind, String ruleId) {
        if (!config.enabled()) {
            return;
        }
        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            int port = baseUri.getPort() == -1
                ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
                : baseUri.getPort();
            boolean ssl = "https".equals(baseUri.getScheme());
            String path = "/api/internal/ai/usage";

            JsonObject body = new JsonObject()
                .put("userId", tenantId)
                .put("callKind", callKind)
                .put("model", "blocked-no-llm-call")
                .put("promptTokens", 0)
                .put("completionTokens", 0)
                .put("usedByok", false)
                .put("status", "blocked_unsafe")
                .put("safetyFlags", new JsonObject()
                    .put("jailbreak_attempt", true)
                    .put("blocked_reason", ruleId));

            // v2 签名：绑定 nonce + bodyHash（见 InternalCallSigner）
            var signed = config.hmacKey()
                .map(k -> InternalCallSigner.sign(k, "POST", path, body.encode()))
                .orElse(new InternalCallSigner.Signed(
                    String.valueOf(System.currentTimeMillis() / 1000), "", ""));
            String timestamp = signed.timestamp();
            String signature = signed.signature();

            getClient()
                .post(port, baseUri.getHost(), path)
                .ssl(ssl)
                .timeout(config.requestTimeout().toMillis())
                .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
                .putHeader("X-Aster-Signature", signature)
                .putHeader("X-Aster-Nonce", signed.nonce())
                .putHeader("Content-Type", "application/json")
                .sendBuffer(body.toBuffer())
                .onFailure(err -> LOG.warnf(
                    "safety event report failed: tenant=%s, rule=%s, err=%s",
                    tenantId, ruleId, err.getMessage()));
        } catch (Exception e) {
            // fire-and-forget；任何异常仅 warn，不抛
            LOG.warnf("safety event report exception: tenant=%s, rule=%s, err=%s",
                tenantId, ruleId, e.getMessage());
        }
    }

    private WebClient getClient() {
        return sharedWebClient.client();
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

}
