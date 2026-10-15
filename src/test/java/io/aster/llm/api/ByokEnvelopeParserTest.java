package io.aster.llm.api;

import io.aster.llm.model.ByokOverride;
import io.aster.security.apikey.InternalCallerFilter;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ByokEnvelopeParser 单测（Phase 2 BYOK）：只对 HMAC 验签请求解析 _byok，未验签一律忽略。
 */
class ByokEnvelopeParserTest {

    private final ByokEnvelopeParser parser = new ByokEnvelopeParser();

    private ContainerRequestContext ctx(boolean verified, String bodyJson) {
        Map<String, Object> props = Map.of(
            InternalCallerFilter.HMAC_VERIFIED_PROP, verified ? Boolean.TRUE : Boolean.FALSE,
            InternalCallerFilter.VERIFIED_BODY_PROP, bodyJson == null ? new byte[0] : bodyJson.getBytes(StandardCharsets.UTF_8)
        );
        return (ContainerRequestContext) Proxy.newProxyInstance(
            ContainerRequestContext.class.getClassLoader(),
            new Class<?>[] { ContainerRequestContext.class },
            (proxy, method, args) -> "getProperty".equals(method.getName()) ? props.get((String) args[0]) : null
        );
    }

    @Test
    @DisplayName("已验签 + 合法 _byok → 解析出 ByokOverride")
    void parsesValidEnvelope() {
        String body = "{\"goal\":\"x\",\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"sk-user\"}}";
        ByokOverride byok = parser.parse(ctx(true, body));
        assertThat(byok).isNotNull();
        assertThat(byok.provider()).isEqualTo("openai");
        assertThat(byok.apiKey()).isEqualTo("sk-user");
        assertThat(byok.baseUrl()).isNull();
    }

    @Test
    @DisplayName("已验签 + _byok.baseUrl → 解析进 ByokOverride（后续 resolver 再校验）")
    void parsesOptionalBaseUrl() {
        String body = "{\"goal\":\"x\",\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"sk-user\","
            + "\"baseUrl\":\"https://llm-gateway.example.com/v1\"}}";
        ByokOverride byok = parser.parse(ctx(true, body));
        assertThat(byok).isNotNull();
        assertThat(byok.baseUrl()).isEqualTo("https://llm-gateway.example.com/v1");
    }

    @Test
    @DisplayName("★未验签请求即使带 _byok → 一律忽略（防浏览器/公网注入）")
    void ignoresEnvelopeWhenNotHmacVerified() {
        String body = "{\"goal\":\"x\",\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"sk-user\"}}";
        assertThat(parser.parse(ctx(false, body))).isNull();
    }

    @Test
    @DisplayName("已验签但 body 无 _byok → null（平台路径）")
    void nullWhenNoEnvelope() {
        assertThat(parser.parse(ctx(true, "{\"goal\":\"x\"}"))).isNull();
    }

    @Test
    @DisplayName("★_byok 存在但字段不全（缺 apiKey）→ 抛错 fail-closed（不静默回退平台）")
    void throwsWhenEnvelopePresentButInvalid() {
        String body = "{\"_byok\":{\"provider\":\"openai\"}}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(ctx(true, body)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("body 非 JSON → null，不抛异常")
    void nullWhenBodyNotJson() {
        assertThat(parser.parse(ctx(true, "not-json"))).isNull();
    }

    @Test
    @DisplayName("null context / 无 verified body → null")
    void nullWhenNoContextOrBody() {
        assertThat(parser.parse(null)).isNull();
        assertThat(parser.parse(ctx(true, null))).isNull();
    }

    @Test
    @DisplayName("_byok.model 解析并 trim；空白视为未指定")
    void parsesModel() {
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\" gpt-4o \"}}")).model())
            .isEqualTo("gpt-4o");
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"   \"}}")).model())
            .isNull();
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}")).model())
            .isNull();
        // 长度边界：128 可用，129 拒绝
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"" + "a".repeat(128) + "\"}}")).model())
            .hasSize(128);
    }

    @Test
    @DisplayName("★_byok.model 非法（超长/非法字符）→ IllegalArgumentException（fail-closed，不回退平台）")
    void parserTrimsAndRejectsBadModel() {
        String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> parser.parse(ctx(true,
            "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"" + tooLong + "\"}}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
        assertThatThrownBy(() -> parser.parse(ctx(true,
            "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"gpt 4o\\n\"}}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
    }
}
