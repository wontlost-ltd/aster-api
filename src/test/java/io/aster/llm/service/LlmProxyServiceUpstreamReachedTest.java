package io.aster.llm.service;

import io.aster.llm.api.dto.AssistantRequest;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.prompt.PromptContext;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import io.aster.llm.usage.AiUsageReporter;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发给 cloud 的 {@code upstream_reached} 帧（cloud#470）。
 *
 * <p>cloud 凭这一帧判定「本次调用已到达第三方 AI、计入配额」。所以：连上 provider 就必须转发，
 * 哪怕随后只有错误、没有任何内容；没连上（key 缺失 / BYOK 非法）就绝不能出现。
 */
class LlmProxyServiceUpstreamReachedTest {

    private static final String REACHED = "{\"type\":\"upstream_reached\"}";

    private LlmProxyService service;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new LlmProxyService();
        client = mock(LlmClient.class);
        service.llmClient = client;
        service.promptComposer = mock(PromptComposer.class, Answers.RETURNS_MOCKS);
        service.config = mock(LlmConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(service.config.model()).thenReturn("m");
        when(service.config.validation().maxAttempts()).thenReturn(1);
        service.validator = mock(PolicyCompileValidator.class);
        service.usageReporter = mock(AiUsageReporter.class);
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
        when(service.optionsResolver.resolve(anyString(), any()))
            .thenReturn(new LlmRuntimeOptions("k", "openai", "http://127.0.0.1", LlmRuntimeOptions.Source.PLATFORM));
        when(service.promptComposer.buildGenerateContext(anyString(), any())).thenReturn(mock(PromptContext.class));
    }

    private void providerEmits(LlmStreamEvent... events) {
        when(client.streamChat(any(), any())).thenReturn(Multi.createFrom().items(events));
    }

    private static List<String> collect(Multi<String> frames) {
        return frames.collect().asList().await().atMost(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("★generate：连上 provider 后 provider 报错——先转发 upstream_reached，再转发 error")
    void generateForwardsReachedBeforeProviderError() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.error("LLM API 错误 (500)"));

        List<String> frames = collect(service.streamGenerate("t", new GeneratePolicyRequest("g", null, null, null, null), null, "r"));

        assertThat(frames).containsExactly(REACHED, "{\"type\":\"error\",\"error\":\"LLM API 错误 (500)\"}");
    }

    @Test
    @DisplayName("★suggest：provider 只返回错误也要转发 upstream_reached（错误本身照旧不转发）")
    void suggestForwardsReachedWithoutContent() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.error("流式响应异常"));

        assertThat(collect(service.streamSuggest("t", new SuggestRequest("s", null, null, null), null, "r")))
            .containsExactly(REACHED);
    }

    @Test
    @DisplayName("suggest：upstream_reached 在内容之前，内容照常转发")
    void suggestOrdersReachedBeforeContent() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("x"), LlmStreamEvent.done());

        assertThat(collect(service.streamSuggest("t", new SuggestRequest("s", null, null, null), null, "r")))
            .containsExactly(REACHED, "{\"type\":\"delta\",\"data\":\"x\"}");
    }

    @Test
    @DisplayName("★assistant：连上后 provider 无任何产出，仍转发 upstream_reached")
    void assistantForwardsReached() {
        providerEmits(LlmStreamEvent.reached());

        AssistantRequest req = mock(AssistantRequest.class);
        assertThat(collect(service.streamAssistant("t", req, null, "r"))).containsExactly(REACHED);
    }

    @Test
    @DisplayName("★连不上 provider：只有 error，绝无 upstream_reached")
    void connectionFailureHasNoReached() {
        providerEmits(LlmStreamEvent.error("LLM 连接失败"));

        assertThat(collect(service.streamGenerate("t", new GeneratePolicyRequest("g", null, null, null, null), null, "r")))
            .containsExactly("{\"type\":\"error\",\"error\":\"LLM 连接失败\"}");
    }

    @Test
    @DisplayName("★未配置 key：根本不调 provider，不发 upstream_reached")
    void missingKeyNeverReachesProvider() {
        when(service.optionsResolver.resolve(anyString(), any()))
            .thenReturn(new LlmRuntimeOptions("", "openai", "http://127.0.0.1", LlmRuntimeOptions.Source.PLATFORM));

        assertThat(collect(service.streamSuggest("t", new SuggestRequest("s", null, null, null), null, "r")))
            .containsExactly("{\"type\":\"error\",\"error\":\"未配置 LLM API Key\"}");
        verify(client, never()).streamChat(any(), any());
    }
}
