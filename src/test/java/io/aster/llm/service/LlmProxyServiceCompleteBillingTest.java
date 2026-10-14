package io.aster.llm.service;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmChatResult;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import io.aster.llm.usage.AiUsageReporter;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 非流式补全的 {@code upstreamReached}（cloud#518）：cloud 只对 true 计入 AI 配额。
 *
 * <p>此前补全无论是否调到 provider 都返回 200 + 空补全，cloud 对任何 2xx 计费——
 * 未配置 key、连不上 provider 时也扣配额。
 */
class LlmProxyServiceCompleteBillingTest {

    private static final CompleteRequest REQ = new CompleteRequest("Rule r", null, null);

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
        service.validator = mock(PolicyCompileValidator.class);
        service.usageReporter = mock(AiUsageReporter.class);
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
        options("k");
    }

    private void options(String apiKey) {
        when(service.optionsResolver.resolve(anyString(), any()))
            .thenReturn(new LlmRuntimeOptions(apiKey, "openai", "http://127.0.0.1", LlmRuntimeOptions.Source.PLATFORM));
    }

    private CompleteResponse complete() {
        return service.complete("t", REQ, null, "r").await().atMost(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("★未配置 key：不调 provider，upstreamReached=false")
    void missingKeyNotReached() {
        options("");
        assertThat(complete().upstreamReached()).isFalse();
        verify(client, never()).chat(any(), any());
    }

    @Test
    @DisplayName("provider 正常返回：upstreamReached=true")
    void successReached() {
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().item(LlmChatResult.of("x")));
        CompleteResponse r = complete();
        assertThat(r.completion()).isEqualTo("x");
        assertThat(r.upstreamReached()).isTrue();
    }

    @Test
    @DisplayName("★已到达 provider 后失败（非 200 / 超时）：空补全但 upstreamReached=true，照样计费")
    void reachedFailureStillReached() {
        when(client.chat(any(), any()))
            .thenReturn(Uni.createFrom().failure(new LlmCallFailedException("LLM API 错误 (500)", true, null)));
        CompleteResponse r = complete();
        assertThat(r.completion()).isEmpty();
        assertThat(r.upstreamReached()).isTrue();
    }

    @Test
    @DisplayName("★连不上 provider：upstreamReached=false，不计费")
    void connectFailureNotReached() {
        when(client.chat(any(), any()))
            .thenReturn(Uni.createFrom().failure(new LlmCallFailedException("LLM 连接失败", false, null)));
        assertThat(complete().upstreamReached()).isFalse();
    }

    @Test
    @DisplayName("非 client 标注的异常（发出请求之前）：按没到达处理")
    void unknownFailureNotReached() {
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().failure(new IllegalStateException("boom")));
        assertThat(complete().upstreamReached()).isFalse();
    }
}
