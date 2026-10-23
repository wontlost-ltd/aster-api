package io.aster.llm.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Anthropic 非流式响应的内容提取（生产 E2E 2026-10-23 G6）。
 *
 * <p>行内补全走非流式 chat；生产观察到 /api/v1/ai/complete 稳定返回空补全而 upstreamReached=true。
 * 此前只取 content[0].text：首块是 thinking 块、或 text 分成多块时，补全被读成空串。
 */
class VertxLlmClientAnthropicContentTest {

    private static JsonObject anthropic(JsonArray content) {
        return new JsonObject().put("stop_reason", "end_turn").put("content", content);
    }

    @Test
    void textAfterThinkingBlockIsReturned() {
        var body = anthropic(new JsonArray()
            .add(new JsonObject().put("type", "thinking").put("thinking", "..."))
            .add(new JsonObject().put("type", "text").put("text", " less than 580")));
        assertThat(VertxLlmClient.extractContent(body, "anthropic")).isEqualTo(" less than 580");
    }

    @Test
    void multipleTextBlocksAreConcatenatedInOrder() {
        var body = anthropic(new JsonArray()
            .add(new JsonObject().put("type", "text").put("text", "Return "))
            .add(new JsonObject().put("type", "text").put("text", "Low.")));
        assertThat(VertxLlmClient.extractContent(body, "anthropic")).isEqualTo("Return Low.");
    }

    @Test
    void legacyBlocksWithoutTypeStillRead() {
        var body = anthropic(new JsonArray().add(new JsonObject().put("text", "ok")));
        assertThat(VertxLlmClient.extractContent(body, "anthropic")).isEqualTo("ok");
    }

    @Test
    void noTextBlockYieldsEmptyString() {
        var body = anthropic(new JsonArray().add(new JsonObject().put("type", "tool_use").put("name", "x")));
        assertThat(VertxLlmClient.extractContent(body, "anthropic")).isEmpty();
        assertThat(VertxLlmClient.extractContent(new JsonObject().put("content", new JsonArray()), "anthropic")).isEmpty();
    }

    @Test
    void openAiShapeUnchanged() {
        var body = new JsonObject().put("choices", new JsonArray()
            .add(new JsonObject().put("message", new JsonObject().put("content", "hi"))));
        assertThat(VertxLlmClient.extractContent(body, "openai")).isEqualTo("hi");
    }
}
