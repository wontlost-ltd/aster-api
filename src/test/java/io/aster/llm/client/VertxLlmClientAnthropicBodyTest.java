package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VertxLlmClient.buildAnthropicBody 单测（Phase 2 BYOK）：Anthropic Messages API 结构正确——
 * system 是 top-level（不塞进 messages），messages 只 user/assistant。
 */
class VertxLlmClientAnthropicBodyTest {

    private final VertxLlmClient client = newClient();

    private static VertxLlmClient newClient() {
        VertxLlmClient c = new VertxLlmClient();
        c.config = new LlmClientTestSupport.StubConfig(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        return c;
    }

    @Test
    @DisplayName("system/developer 消息合并到 top-level system，不进 messages")
    void systemGoesTopLevel() {
        LlmRequest req = new LlmRequest("claude-x", List.of(
            LlmRequest.Message.system("你是策略助手"),
            LlmRequest.Message.developer("务必输出 CNL"),
            new LlmRequest.Message("user", "生成一个贷款策略")
        ), 0.2, 1000, true);

        JsonObject body = client.buildAnthropicBody(req);

        // top-level system 合并了 system + developer
        assertThat(body.getString("system")).contains("你是策略助手").contains("务必输出 CNL");

        // messages 只剩 user，且没有 role=system/developer
        JsonArray messages = body.getJsonArray("messages");
        assertThat(messages).hasSize(1);
        JsonObject m0 = messages.getJsonObject(0);
        assertThat(m0.getString("role")).isEqualTo("user");
        assertThat(m0.getString("content")).isEqualTo("生成一个贷款策略");
    }

    @Test
    @DisplayName("assistant 角色保留，未知角色归一为 user")
    void assistantKeptUnknownMappedToUser() {
        LlmRequest req = new LlmRequest("claude-x", List.of(
            new LlmRequest.Message("assistant", "上一轮回答"),
            new LlmRequest.Message("weird", "奇怪角色")
        ), 0.0, 500, false);

        JsonArray messages = client.buildAnthropicBody(req).getJsonArray("messages");
        assertThat(messages.getJsonObject(0).getString("role")).isEqualTo("assistant");
        assertThat(messages.getJsonObject(1).getString("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("无 system 消息时不加 top-level system 字段")
    void noSystemFieldWhenAbsent() {
        LlmRequest req = new LlmRequest("claude-x", List.of(
            new LlmRequest.Message("user", "hi")
        ), 0.0, 100, false);
        assertThat(client.buildAnthropicBody(req).containsKey("system")).isFalse();
    }

    @Test
    @DisplayName("claude 系模型透传；max_tokens / stream 透传")
    void passesClaudeModelAndParams() {
        LlmRequest req = new LlmRequest("claude-sonnet", List.of(
            new LlmRequest.Message("user", "hi")
        ), 0.3, 2048, true);
        JsonObject body = client.buildAnthropicBody(req);
        assertThat(body.getString("model")).isEqualTo("claude-sonnet");
        assertThat(body.getInteger("max_tokens")).isEqualTo(2048);
        assertThat(body.getBoolean("stream")).isTrue();
    }

    @Test
    @DisplayName("★非 claude 模型（如平台默认 gpt-4o-mini）→ 兜底为 Anthropic 默认模型")
    void nonClaudeModelFallsBackToAnthropicDefault() {
        LlmRequest req = new LlmRequest("gpt-4o-mini", List.of(
            new LlmRequest.Message("user", "hi")
        ), 0.0, 500, false);
        assertThat(client.buildAnthropicBody(req).getString("model"))
            .as("★兜底取配置的 Anthropic 默认模型（aster-api#360：写死的旧模型下线后全部 400）")
            .isEqualTo(LlmClientTestSupport.ANTHROPIC_DEFAULT_MODEL);
    }

    @Test
    @DisplayName("请求未指定模型（null）→ 同样兜底为配置的 Anthropic 默认模型")
    void nullModelFallsBackToAnthropicDefault() {
        LlmRequest req = new LlmRequest(null, List.of(new LlmRequest.Message("user", "hi")), 0.0, 500, false);
        assertThat(client.buildAnthropicBody(req).getString("model"))
            .isEqualTo(LlmClientTestSupport.ANTHROPIC_DEFAULT_MODEL);
    }

    // ==================== #185: stream_options.include_usage 条件 ====================

    private io.aster.llm.model.LlmRuntimeOptions opts(String provider, String baseUrl) {
        return new io.aster.llm.model.LlmRuntimeOptions("k", provider, baseUrl,
            io.aster.llm.model.LlmRuntimeOptions.Source.PLATFORM);
    }

    @Test
    @DisplayName("#185：native OpenAI 流式 → 加 stream_options.include_usage")
    void nativeOpenAiStreamGetsStreamOptions() {
        LlmRequest req = new LlmRequest("gpt-4o", List.of(new LlmRequest.Message("user", "hi")), 0.0, 100, true);
        var body = client.buildRequestBody(req, opts("openai", "https://api.openai.com"));
        assertThat(body.getJsonObject("stream_options").getBoolean("include_usage")).isTrue();
    }

    @Test
    @DisplayName("★#185：OpenAI 兼容代理(rightcode) 流式 → 不加 stream_options（防严格代理 400）")
    void compatProxyStreamNoStreamOptions() {
        LlmRequest req = new LlmRequest("gpt-5.2", List.of(new LlmRequest.Message("user", "hi")), 0.0, 100, true);
        var body = client.buildRequestBody(req, opts("rightcode", "https://right.codes/codex/v1"));
        assertThat(body.containsKey("stream_options")).isFalse();
    }

    @Test
    @DisplayName("#185：非流式 → 不加 stream_options（本就不需要，用响应根部 usage）")
    void nonStreamNoStreamOptions() {
        LlmRequest req = new LlmRequest("gpt-4o", List.of(new LlmRequest.Message("user", "hi")), 0.0, 100, false);
        var body = client.buildRequestBody(req, opts("openai", "https://api.openai.com"));
        assertThat(body.containsKey("stream_options")).isFalse();
    }

    @Test
    @DisplayName("★BYOK 指定 model → Anthropic body 原样发出（不兜底）")
    void byokModelKeptInAnthropicBody() {
        LlmRequest req = new LlmRequest("gpt-4o-mini", List.of(new LlmRequest.Message("user", "hi")), 0.0, 500, false);
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "anthropic", "https://api.anthropic.com",
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.ANTHROPIC_NATIVE, false, "my-alias");
        assertThat(client.buildAnthropicBody(req, opts).getString("model")).isEqualTo("my-alias");
    }
}
