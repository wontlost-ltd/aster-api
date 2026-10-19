package io.aster.llm.client;

import io.aster.llm.client.ProviderErrorClassifier.Classified;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ProviderErrorClassifierTest {

    /** 2026-10-19 生产事故的 Anthropic 400 原文（key 未绑定 workspace）。 */
    public static final String WORKSPACE_MESSAGE = "This API key is not scoped to a workspace, so this request must include "
        + "the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an API key "
        + "that is scoped to a workspace.";
    public static final String INCIDENT_BODY = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
        + "\"message\":\"" + WORKSPACE_MESSAGE + "\"},\"request_id\":\"req_011CUabc\"}";

    private static String anthropic(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    @Test
    @DisplayName("★事故：workspace 未绑定的 400（带显式 model）→ provider_bad_request，原文完整保留")
    void workspaceScopeIsBadRequest() {
        Classified c = ProviderErrorClassifier.classify(400, INCIDENT_BODY, true);
        assertThat(c.code()).isEqualTo("provider_bad_request");
        assertThat(c.providerMessage()).isEqualTo(WORKSPACE_MESSAGE);
        assertThat(c.providerErrorType()).isEqualTo("invalid_request_error");
        assertThat(c.isClientError()).isTrue();
    }

    @Test
    @DisplayName("401 / 403 → provider_auth_failed")
    void authStatuses() {
        assertThat(ProviderErrorClassifier.classify(401, anthropic("authentication_error", "invalid x-api-key"), true).code())
            .isEqualTo("provider_auth_failed");
        assertThat(ProviderErrorClassifier.classify(403, null, false).code()).isEqualTo("provider_auth_failed");
    }

    @Test
    @DisplayName("type=authentication_error / permission_error 即使状态是 400 也归 auth")
    void authTypes() {
        assertThat(ProviderErrorClassifier.classify(400, anthropic("permission_error", "no"), true).code())
            .isEqualTo("provider_auth_failed");
        assertThat(ProviderErrorClassifier.classify(400, anthropic("authentication_error", "no"), false).code())
            .isEqualTo("provider_auth_failed");
    }

    @Test
    @DisplayName("★404 not_found_error：有显式 model → provider_rejected_model；无 → provider_bad_request")
    void notFoundDependsOnExplicitModel() {
        String body = anthropic("not_found_error", "model: claude-nope");
        assertThat(ProviderErrorClassifier.classify(404, body, true).code()).isEqualTo("provider_rejected_model");
        assertThat(ProviderErrorClassifier.classify(404, body, false).code()).isEqualTo("provider_bad_request");
    }

    @Test
    @DisplayName("OpenAI error.code=model_not_found（非 404 状态）→ rejected_model；providerErrorType 回落 code")
    void openAiModelNotFoundCode() {
        String body = "{\"error\":{\"code\":\"model_not_found\",\"message\":\"The model `x` does not exist\"}}";
        Classified c = ProviderErrorClassifier.classify(400, body, true);
        assertThat(c.code()).isEqualTo("provider_rejected_model");
        assertThat(c.providerErrorType()).isEqualTo("model_not_found");
    }

    @Test
    @DisplayName("429 / rate_limit_error → provider_rate_limited")
    void rateLimited() {
        assertThat(ProviderErrorClassifier.classify(429, null, true).code()).isEqualTo("provider_rate_limited");
        assertThat(ProviderErrorClassifier.classify(400, anthropic("rate_limit_error", "slow"), true).code())
            .isEqualTo("provider_rate_limited");
        assertThat(ProviderErrorClassifier.classify(429, null, true).isClientError()).isFalse();
    }

    @Test
    @DisplayName("529 overloaded / 5xx / api_error → provider_unavailable")
    void unavailable() {
        assertThat(ProviderErrorClassifier.classify(529, anthropic("overloaded_error", "Overloaded"), true).code())
            .isEqualTo("provider_unavailable");
        assertThat(ProviderErrorClassifier.classify(502, "<html>bad gateway</html>", false).code())
            .isEqualTo("provider_unavailable");
        assertThat(ProviderErrorClassifier.classify(200, anthropic("api_error", "boom"), false).code())
            .isEqualTo("provider_unavailable");
    }

    @Test
    @DisplayName("其它 4xx → provider_bad_request")
    void otherClientErrors() {
        assertThat(ProviderErrorClassifier.classify(422, "{}", true).code()).isEqualTo("provider_bad_request");
        assertThat(ProviderErrorClassifier.classify(413, null, false).code()).isEqualTo("provider_bad_request");
    }

    @Test
    @DisplayName("非 JSON / 空 / null / 缺 error 对象的 body → 无 message 与 type，仍按状态分类")
    void unparsableBodies() {
        for (String body : new String[] {"not json", "", null, "[]", "{\"error\":\"plain string\"}", "{\"foo\":1}"}) {
            Classified c = ProviderErrorClassifier.classify(400, body, true);
            assertThat(c.code()).isEqualTo("provider_bad_request");
            assertThat(c.providerMessage()).isNull();
            assertThat(c.providerErrorType()).isNull();
        }
    }

    @Test
    @DisplayName("★脱敏：sk-ant-… 与 Bearer 令牌被替换为 [redacted]")
    void redactsSecrets() {
        String body = anthropic("authentication_error",
            "invalid key sk-ant-api03-AbCdEf_123-xyz and header Bearer abc.def.ghi rejected");
        Classified c = ProviderErrorClassifier.classify(401, body, true);
        assertThat(c.providerMessage()).isEqualTo("invalid key [redacted] and header [redacted] rejected");
    }

    @Test
    @DisplayName("过短的 sk- 片段（<8 字符）不脱敏")
    void shortSkNotRedacted() {
        Classified c = ProviderErrorClassifier.classify(400, anthropic("invalid_request_error", "use sk-abc"), true);
        assertThat(c.providerMessage()).isEqualTo("use sk-abc");
    }

    @Test
    @DisplayName("★折叠空白并截断到 300 字符 + 省略号")
    void collapsesWhitespaceAndTruncates() {
        Classified spaced = ProviderErrorClassifier.classify(400,
            anthropic("invalid_request_error", "a   b\\n\\n c"), true);
        assertThat(spaced.providerMessage()).isEqualTo("a b c");

        Classified longMsg = ProviderErrorClassifier.classify(400,
            anthropic("invalid_request_error", "x".repeat(500)), true);
        assertThat(longMsg.providerMessage()).isEqualTo("x".repeat(300) + "…");

        Classified exact = ProviderErrorClassifier.classify(400,
            anthropic("invalid_request_error", "y".repeat(300)), true);
        assertThat(exact.providerMessage()).isEqualTo("y".repeat(300));
    }

    @Test
    @DisplayName("纯空白 message 视为无")
    void blankMessageIsNull() {
        assertThat(ProviderErrorClassifier.classify(400, anthropic("invalid_request_error", "   "), true)
            .providerMessage()).isNull();
    }
}
