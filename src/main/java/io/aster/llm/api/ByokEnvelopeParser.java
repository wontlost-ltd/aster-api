package io.aster.llm.api;

import io.aster.llm.model.ByokOverride;
import io.aster.security.apikey.InternalCallerFilter;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

/**
 * 从【已 HMAC 验签】的内部请求 body 顶层解析 {@code _byok} envelope（Phase 2 BYOK）。
 *
 * <p>envelope 形如 {@code {"_byok": {"provider": "openai", "apiKey": "sk-...", "baseUrl": "https://..."}}}，
 * 由 aster-cloud 在解密用户 BYOK key 后注入 body、并在注入<b>之后</b>签名（签名覆盖含 envelope 的最终 body）。
 *
 * <p>安全铁律：
 * <ul>
 *   <li>只对 {@link InternalCallerFilter#isHmacVerified} 为真的请求解析——未验签的 public/trial
 *       旁路请求即使 body 里带 {@code _byok} 也一律忽略（浏览器/公网不可注入 BYOK）。</li>
 *   <li>从 {@link InternalCallerFilter#VERIFIED_BODY_PROP}（已验签 body 字节）解析，不重新读流。</li>
 *   <li>解析出的 {@link ByokOverride} 只传给 service/resolver，<b>不进业务 DTO、不进 PromptComposer、
 *       不进审计 vault、不打日志</b>。</li>
 * </ul>
 */
@ApplicationScoped
public class ByokEnvelopeParser {

    private static final Logger LOG = Logger.getLogger(ByokEnvelopeParser.class);

    /**
     * 解析本次请求的 BYOK 覆盖。
     *
     * <ul>
     *   <li>未验签 / body 非 JSON / <b>无</b> {@code _byok} → 返回 null（平台路径，absent）。</li>
     *   <li>{@code _byok} <b>存在但字段无效</b>（provider/apiKey 空）→ 抛 {@link IllegalArgumentException}
     *       （fail-closed：已验签 body 里明确带了 BYOK 意图但无效，不应静默回退平台 key）。</li>
     * </ul>
     */
    public ByokOverride parse(ContainerRequestContext ctx) {
        if (ctx == null || !InternalCallerFilter.isHmacVerified(ctx)) {
            // 未经 HMAC 验签的请求一律不接受 _byok（红队：防浏览器/公网注入）
            return null;
        }
        Object raw = ctx.getProperty(InternalCallerFilter.VERIFIED_BODY_PROP);
        if (!(raw instanceof byte[] bodyBytes) || bodyBytes.length == 0) {
            return null;
        }
        JsonObject env;
        try {
            JsonObject body = new JsonObject(new String(bodyBytes, StandardCharsets.UTF_8));
            env = body.getJsonObject("_byok");
        } catch (Exception e) {
            // body 非 JSON：按无 BYOK 处理（不泄漏内容）
            LOG.debug("body 非 JSON，按无 BYOK 处理");
            return null;
        }
        if (env == null) {
            return null; // absent → 平台路径
        }
        // present：已验签 body 明确带 BYOK 意图，无效则 fail-closed（不回退平台）
        ByokOverride override = new ByokOverride(env.getString("provider"), env.getString("apiKey"),
            env.getString("baseUrl"), normalizeModel(env.getString("model")));
        if (!override.isValid()) {
            throw new IllegalArgumentException("_byok envelope 字段无效（provider/apiKey 缺失）");
        }
        return override;
    }

    /** 允许的模型 id 形状（与 cloud 保存时校验一致）：1–128 字符，字母数字与 . _ : / -。 */
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    /** 空白 → null（未指定）；非法形状 → 抛错，不把垃圾塞进 provider 请求。 */
    static String normalizeModel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!MODEL_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("_byok.model 非法（长度 1–128，仅 A-Z a-z 0-9 . _ : / -）");
        }
        return trimmed;
    }

    /**
     * 从【已 HMAC 验签】的 body 顶层解析 {@code _usage.requestId}（issue #185）——cloud 生成、注入、
     * 签名后传来，aster-api 成功后带同一 requestId 回填真实 token。未验签 / 无 envelope / 非 JSON
     * 返回 null（该次不做精确回填，cloud 侧占位 0/0 仍在）。
     */
    public String parseRequestId(ContainerRequestContext ctx) {
        if (ctx == null || !InternalCallerFilter.isHmacVerified(ctx)) {
            return null;
        }
        Object raw = ctx.getProperty(InternalCallerFilter.VERIFIED_BODY_PROP);
        if (!(raw instanceof byte[] bodyBytes) || bodyBytes.length == 0) {
            return null;
        }
        try {
            JsonObject usage = new JsonObject(new String(bodyBytes, StandardCharsets.UTF_8))
                .getJsonObject("_usage");
            if (usage == null) {
                return null;
            }
            String requestId = usage.getString("requestId");
            return (requestId == null || requestId.isBlank()) ? null : requestId;
        } catch (Exception e) {
            LOG.debug("解析 _usage envelope 失败，跳过 token 回填");
            return null;
        }
    }
}
