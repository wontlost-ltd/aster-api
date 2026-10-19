package io.aster.llm.client;

import io.vertx.core.json.JsonObject;

import java.util.regex.Pattern;

/**
 * provider（Anthropic / OpenAI 兼容）非 2xx 响应的统一分类器。
 *
 * <p>流式、非流式 chat、模型列表三条路径共用同一套规则，避免「任何 400 都当成模型被拒」之类的误判。
 * 纯函数、无状态，便于直接单测。规则按顺序匹配，先中先得（ADR 0048 §6 修订）：
 * <ol>
 *   <li>401/403 或 error.type ∈ {authentication_error, permission_error} → provider_auth_failed；</li>
 *   <li>404 或 not_found_error 或 error.code == model_not_found → provider_rejected_model（仅当请求带了显式 model，否则 provider_bad_request）；</li>
 *   <li>429 或 rate_limit_error（insufficient_quota 属额度问题，归 provider_bad_request） → provider_rate_limited；</li>
 *   <li>≥500、529、overloaded_error/api_error → provider_unavailable；</li>
 *   <li>其余 4xx → provider_bad_request。</li>
 * </ol>
 */
public final class ProviderErrorClassifier {

    public static final String AUTH_FAILED = "provider_auth_failed";
    public static final String REJECTED_MODEL = "provider_rejected_model";
    public static final String RATE_LIMITED = "provider_rate_limited";
    public static final String UNAVAILABLE = "provider_unavailable";
    public static final String BAD_REQUEST = "provider_bad_request";

    static final int MAX_MESSAGE_CHARS = 300;

    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern OTHER_KEYS = Pattern.compile(
        "gsk_[A-Za-z0-9]{16,}|xai-[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,}");
    /** 32+ 字符的 key 形长令牌；要求同时含字母与数字，避免误伤普通长单词。 */
    private static final Pattern LONG_TOKEN = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+");
    /** 「弃用/不支持」类措辞；与 temperature 同现于 message 即视为该模型拒绝此参数。 */
    private static final Pattern REJECTION_WORDING = Pattern.compile(
        "deprecated|unsupported|not\\s+supported|not\\s+allowed", Pattern.CASE_INSENSITIVE);
    /** OpenAI 表示「参数/取值不被该模型支持」的 error.code。 */
    private static final java.util.Set<String> UNSUPPORTED_CODES =
        java.util.Set.of("unsupported_parameter", "unsupported_value");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ProviderErrorClassifier() {
    }

    /**
     * 分类结果。
     *
     * @param code              结构化错误码（见常量）
     * @param providerMessage   脱敏、截断后的 provider 说明；无则 null
     * @param providerErrorType provider 给出的 error.type（缺省取 error.code）；无则 null
     */
    public record Classified(String code, String providerMessage, String providerErrorType) {
        /** 是否属于「请求本身被 provider 判定不可用」的 4xx 客户端类错误（complete 回 400 的集合）。 */
        public boolean isClientError() {
            return AUTH_FAILED.equals(code) || REJECTED_MODEL.equals(code) || BAD_REQUEST.equals(code);
        }
    }

    /**
     * @param status        provider 返回的 HTTP 状态
     * @param body          原始错误体，可为 null / 非 JSON
     * @param explicitModel 请求是否携带 BYOK 显式 model
     */
    public static Classified classify(int status, String body, boolean explicitModel) {
        String type = null;
        String code = null;
        String message = null;
        JsonObject error = parseError(body);
        if (error != null) {
            type = str(error, "type");
            code = str(error, "code");
            message = str(error, "message");
        }
        String errorType = type != null ? type : code;
        return new Classified(
            resolveCode(status, type, code, explicitModel),
            sanitize(message),
            errorType);
    }

    /**
     * provider 是否因「模型不接受 temperature」而拒绝了请求（调用方据此去掉该字段重试一次）。
     *
     * <p>成立条件：请求体确实带了 temperature，状态为 400（网关可能给 422），且错误体满足其一：
     * <ul>
     *   <li>message 同时提到 temperature 与 deprecated / unsupported / not supported / not allowed（大小写不敏感）；</li>
     *   <li>OpenAI 的 {@code error.param == "temperature"}，并且 {@code code} ∈ {unsupported_parameter, unsupported_value}
     *       或 message 含上述拒绝措辞。仅凭 param 不够：取值越界（如 decimal_above_max_value）同样指向 temperature，
     *       但去掉该字段会掩盖真实错误，还会错误地记住该模型。</li>
     * </ul>
     * 解析复用 {@link #parseError}，使用未脱敏截断的原始 message，避免关键词被 300 字符截断吞掉。
     */
    public static boolean isTemperatureUnsupported(int status, String body, boolean requestHadTemperature) {
        if (!requestHadTemperature || (status != 400 && status != 422)) return false;
        JsonObject error = parseError(body);
        if (error == null) return false;
        String message = str(error, "message");
        boolean rejectionWording = message != null && REJECTION_WORDING.matcher(message).find();
        if ("temperature".equalsIgnoreCase(str(error, "param"))) {
            String code = str(error, "code");
            return rejectionWording || (code != null && UNSUPPORTED_CODES.contains(code));
        }
        return rejectionWording && message.toLowerCase(java.util.Locale.ROOT).contains("temperature");
    }

    private static String resolveCode(int status, String type, String code, boolean explicitModel) {
        if (status == 401 || status == 403
            || "authentication_error".equals(type) || "permission_error".equals(type)) {
            return AUTH_FAILED;
        }
        if (status == 404 || "not_found_error".equals(type) || "model_not_found".equals(code)) {
            return explicitModel ? REJECTED_MODEL : BAD_REQUEST;
        }
        // OpenAI 的 insufficient_quota 也是 429，但属账单/额度问题，重试无益 → 按请求不可用上报
        if ("insufficient_quota".equals(code) || "insufficient_quota".equals(type)) {
            return BAD_REQUEST;
        }
        if (status == 429 || "rate_limit_error".equals(type)) {
            return RATE_LIMITED;
        }
        if (status >= 500 || "overloaded_error".equals(type) || "api_error".equals(type)) {
            return UNAVAILABLE;
        }
        return BAD_REQUEST;
    }

    /**
     * 归一化三种常见形状为 {type, code, message} 对象：
     * Anthropic/OpenAI 的 {"error":{...}}；网关的 {"error":"text"}；顶层 {"message":"text"}。解析失败视为无详情。
     */
    private static JsonObject parseError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject root = new JsonObject(body);
            Object error = root.getValue("error");
            if (error instanceof JsonObject obj) return obj;
            if (error instanceof String text) return new JsonObject().put("message", text);
            return root.getValue("message") instanceof String ? root : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 取字符串字段；非字符串（如数字 code）按 toString 处理，缺省返回 null。 */
    private static String str(JsonObject obj, String key) {
        Object v = obj.getValue(key);
        return v == null ? null : v.toString();
    }

    /** 脱敏（key / Bearer 令牌）、折叠空白、截断到 300 字符；空串视为无。 */
    static String sanitize(String message) {
        if (message == null) return null;
        String s = API_KEY.matcher(message).replaceAll("[redacted]");
        s = BEARER.matcher(s).replaceAll("[redacted]");
        s = OTHER_KEYS.matcher(s).replaceAll("[redacted]");
        s = LONG_TOKEN.matcher(s).replaceAll(m -> {
            String t = m.group();
            boolean mixed = t.chars().anyMatch(Character::isDigit) && t.chars().anyMatch(Character::isLetter);
            return mixed ? "[redacted]" : java.util.regex.Matcher.quoteReplacement(t);
        });
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();
        if (s.isEmpty()) return null;
        return s.length() > MAX_MESSAGE_CHARS ? s.substring(0, MAX_MESSAGE_CHARS) + "…" : s;
    }
}
