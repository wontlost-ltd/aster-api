package io.aster.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 「模型不接受 temperature」的纯判定：只在 400 + 请求确实带了 temperature 时成立。 */
class ProviderErrorClassifierTemperatureTest {

    /** 2026-10-20 生产事故原文：Anthropic 新模型弃用 temperature。 */
    static final String ANTHROPIC_INCIDENT_BODY =
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"`temperature` is deprecated for this model.\"},\"request_id\":\"req_011CUxyz\"}";

    /** OpenAI 推理模型的 unsupported parameter 报文，param 指向 temperature。 */
    static final String OPENAI_UNSUPPORTED_BODY =
        "{\"error\":{\"message\":\"Unsupported parameter: 'temperature' is not supported with this model.\","
            + "\"type\":\"invalid_request_error\",\"param\":\"temperature\",\"code\":\"unsupported_parameter\"}}";

    @Test
    @DisplayName("★事故原文：`temperature` is deprecated → true")
    void incidentBody() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, ANTHROPIC_INCIDENT_BODY, true)).isTrue();
    }

    @Test
    @DisplayName("OpenAI unsupported parameter（param=temperature）→ true")
    void openAiUnsupported() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, OPENAI_UNSUPPORTED_BODY, true)).isTrue();
    }

    @Test
    @DisplayName("param=temperature + code=unsupported_value（message 无关键词）→ true")
    void paramWithUnsupportedCode() {
        String body = "{\"error\":{\"message\":\"Invalid value\",\"type\":\"invalid_request_error\","
            + "\"param\":\"temperature\",\"code\":\"unsupported_value\"}}";
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, body, true)).isTrue();
    }

    @Test
    @DisplayName("★param=temperature + code=decimal_above_max_value（取值越界）→ false，不掩盖真实错误")
    void paramWithRangeErrorIsNotUnsupported() {
        String body = "{\"error\":{\"message\":\"Invalid 'temperature': decimal above maximum value.\","
            + "\"type\":\"invalid_request_error\",\"param\":\"temperature\",\"code\":\"decimal_above_max_value\"}}";
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, body, true)).isFalse();
    }

    @Test
    @DisplayName("仅 param=temperature、无 code 无拒绝措辞 → false")
    void paramOnlyIsNotEnough() {
        String body = "{\"error\":{\"message\":\"Invalid value\",\"type\":\"invalid_request_error\",\"param\":\"temperature\"}}";
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, body, true)).isFalse();
    }

    @Test
    @DisplayName("网关常用的 422 同样识别；其他状态仍不识别")
    void status422() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(422, ANTHROPIC_INCIDENT_BODY, true)).isTrue();
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(404, ANTHROPIC_INCIDENT_BODY, true)).isFalse();
    }

    @Test
    @DisplayName("关键词大小写不敏感：not allowed / Unsupported / NOT SUPPORTED")
    void keywordVariants() {
        for (String m : new String[] {
            "Temperature is not allowed for this model", "UNSUPPORTED value: temperature", "TEMPERATURE NOT SUPPORTED"}) {
            String body = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + m + "\"}}";
            assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, body, true)).as(m).isTrue();
        }
    }

    @Test
    @DisplayName("无关的 400（workspace 事故原文）→ false")
    void unrelated400() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(
            400, ProviderErrorClassifierTest.INCIDENT_BODY, true)).isFalse();
    }

    @Test
    @DisplayName("提到 temperature 但无弃用/不支持语义 → false")
    void mentionsTemperatureOnly() {
        String body = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"temperature must be between 0 and 1\"}}";
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, body, true)).isFalse();
    }

    @Test
    @DisplayName("请求本就没带 temperature → false（不重试）")
    void requestWithoutTemperature() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, ANTHROPIC_INCIDENT_BODY, false)).isFalse();
    }

    @Test
    @DisplayName("非 400 状态 → false；空/非 JSON body → false")
    void otherStatusAndBadBodies() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(500, ANTHROPIC_INCIDENT_BODY, true)).isFalse();
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, null, true)).isFalse();
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, "", true)).isFalse();
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(400, "temperature is deprecated", true)).isFalse();
    }

    @Test
    @DisplayName("网关形状 {error:\"text\"} 也能识别")
    void gatewayStringError() {
        assertThat(ProviderErrorClassifier.isTemperatureUnsupported(
            400, "{\"error\":\"temperature is not supported by this model\"}", true)).isTrue();
    }
}
