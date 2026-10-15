package io.aster.llm.service;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmChatResult;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.LlmUsage;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.prompt.PromptContext;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import io.aster.llm.usage.AiUsageReporter;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0048：service 层把 BYOK 指定的 model 下推、按实际 model 记账、首帧 meta、模型被拒不伪装成功。 */
class LlmProxyServiceModelTest {

    private LlmProxyService service;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new LlmProxyService();
        client = mock(LlmClient.class);
        service.llmClient = client;
        PromptComposer composer = mock(PromptComposer.class);
        when(composer.buildCompleteContext(anyString(), any())).thenAnswer(inv ->
            new PromptContext().systemPrompt("s").userPrompt("u").model("gpt-4o-mini").temperature(0.1).maxTokens(10));
        when(composer.buildSuggestContext(anyString(), any())).thenAnswer(inv ->
            new PromptContext().systemPrompt("s").userPrompt("u").model("gpt-4o-mini").temperature(0.1).maxTokens(10));
        service.promptComposer = composer;
        service.config = mock(LlmConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(service.config.model()).thenReturn("gpt-4o-mini");
        when(service.config.anthropicDefaultModel()).thenReturn("claude-default");
        service.validator = mock(PolicyCompileValidator.class);
        service.usageReporter = mock(AiUsageReporter.class);
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
    }

    private void options(String provider, String model) {
        when(service.optionsResolver.resolve(anyString(), any())).thenReturn(
            new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.BYOK,
                LlmRuntimeOptions.inferWireFormat(provider, "https://x"), false, model));
    }

    @Test
    @DisplayName("complete：BYOK 指定 model → 请求 model 与记账 model 都是它，CompleteResponse.model 回传")
    void completeUsesByokModel() {
        options("openai", "gpt-4o");
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().item(new LlmChatResult("x", LlmUsage.ZERO)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.model()).isEqualTo("gpt-4o");
        verify(client).chat(org.mockito.ArgumentMatchers.argThat(req -> "gpt-4o".equals(req.model())), any());
        verify(service.usageReporter).reportUsage(eq("t"), eq("complete"), eq("gpt-4o"), any(), eq(true), eq("r"));
    }

    @Test
    @DisplayName("complete：BYOK anthropic 未指定 model → 记账/回传为 Anthropic 兜底模型（实际发出的）")
    void completeReportsEffectiveAnthropicModel() {
        options("anthropic", null);
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().item(new LlmChatResult("x", LlmUsage.ZERO)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.model()).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("★complete：BYOK 指定 model 且 provider 400 → 400 provider_rejected_model，不伪装为空补全")
    void completeRejectedModelIs400() {
        options("openai", "nope-1");
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (400)", true, null, 400)));
        assertThatThrownBy(() -> service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5)))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("complete：未指定 model 时 provider 400 → 保持今天的降级（空补全、upstreamReached=true）")
    void completeWithoutModelKeepsRecovery() {
        options("openai", null);
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (400)", true, null, 400)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.completion()).isEmpty();
        assertThat(res.upstreamReached()).isTrue();
    }

    @Test
    @DisplayName("suggest 流：首帧为 meta（实际 model），其后才是 upstream_reached/delta")
    void streamStartsWithMeta() {
        options("openai", "gpt-4o");
        when(client.streamChat(any(), any())).thenReturn(Multi.createFrom().items(
            LlmStreamEvent.reached(), LlmStreamEvent.delta("a"), LlmStreamEvent.done()));
        List<String> frames = service.streamSuggest("t", new SuggestRequest("Rule r", null, null, null), null, "r")
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(frames.get(0)).contains("\"type\":\"meta\"").contains("gpt-4o");
        assertThat(frames.get(1)).contains("upstream_reached");
    }
}
