package io.aster.llm.usage;

import io.aster.security.internal.InternalCallSigner;
import io.aster.billing.PlanGateConfig;
import io.aster.llm.model.LlmUsage;
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
 * 把 LLM 成功调用的真实 token 用量回填到 aster-cloud（issue #185）。
 *
 * <p>cloud 转发前生成 requestId 并注入 {@code _usage} envelope；aster-api 成功后带同一 requestId
 * + 真实 token POST 到 cloud {@code /api/internal/ai/usage}。cloud 用 requestId upsert 同一笔 usage
 * （占位 0/0 + 精确回填 = 一笔，不双记账）。
 *
 * <p>设计（复用 {@code SafetyEventReporter} 的 HMAC POST 模式）：
 *   - 复用 {@code PlanGateConfig} 的 HMAC 共享密钥；fire-and-forget，失败仅日志。
 *   - 只在 requestId 存在（cloud 注入了 _usage）且有真实 token 时上报。
 */
@ApplicationScoped
public class AiUsageReporter {

    private static final Logger LOG = Logger.getLogger(AiUsageReporter.class);

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    @Inject
    PlanGateConfig config;

    /**
     * @param userId    cloud 侧 userId（= aster-api tenantId）
     * @param callKind  generate / suggest / complete
     * @param model     模型名
     * @param usage     真实 token（null / 无 token 时不上报）
     * @param usedByok  是否用了 BYOK（与 cloud 占位记账口径一致）
     * @param requestId cloud 注入的关联 id（null 则不上报——无从关联占位笔）
     */
    public void reportUsage(String userId, String callKind, String model,
                            LlmUsage usage, boolean usedByok, String requestId) {
        if (!config.enabled()) {
            return;
        }
        if (requestId == null || requestId.isBlank() || usage == null || !usage.hasTokens()) {
            // 无 requestId 无从 upsert 关联；无 token 不必回填（cloud 占位已在）。
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
                .put("userId", userId)
                .put("callKind", callKind)
                .put("model", model != null ? model : "unknown")
                .put("promptTokens", usage.promptTokens())
                .put("completionTokens", usage.completionTokens())
                .put("usedByok", usedByok)
                .put("status", "success")
                .put("requestId", requestId);

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
                    "ai usage report failed: user=%s, requestId=%s, err=%s",
                    userId, requestId, err.getMessage()));
        } catch (Exception e) {
            LOG.warnf("ai usage report exception: user=%s, requestId=%s, err=%s",
                userId, requestId, e.getMessage());
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
