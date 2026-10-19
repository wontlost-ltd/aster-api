package io.aster.llm.service;

import io.aster.llm.api.dto.ModelsResponse;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** POST /api/v1/ai/models 的 service 契约（ADR 0048 §3.3 / §6）。 */
class LlmProxyServiceModelsTest {

    private LlmProxyService service;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new LlmProxyService();
        client = mock(LlmClient.class);
        service.llmClient = client;
        service.config = mock(LlmConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(service.config.model()).thenReturn("gpt-4o-mini");
        when(service.config.anthropicDefaultModel()).thenReturn("claude-default");
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
        when(service.optionsResolver.resolve(anyString(), any())).thenReturn(
            new LlmRuntimeOptions("k", "openai", "https://api.openai.com", LlmRuntimeOptions.Source.BYOK));
    }

    private ModelsResponse call() {
        return service.listModels("t", new ByokOverride("openai", "k")).await().atMost(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("上游返回列表 → models 排序去重，defaultModel=平台该 provider 默认")
    void returnsModels() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(List.of("gpt-4o", "gpt-4o-mini", "gpt-4o")));
        ModelsResponse res = call();
        assertThat(res.models()).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(res.defaultModel()).isEqualTo("gpt-4o-mini");
        assertThat(res.fetchedAt()).isNotBlank();
    }

    @Test
    @DisplayName("★网关不提供 /models（404/405）→ 200 models=null，不是错误")
    void unsupportedListIsNotAnError() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (404)", true, null, 404)));
        assertThat(call().models()).isNull();
    }

    @Test
    @DisplayName("★大小边界：超过 128 字符或形状非法的 id 丢弃，最多 200 条（jsonb 上界 ≤ 200×128）")
    void boundsIds() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(List.of("ok-1", "a".repeat(129), "bad id", "ok-2")));
        assertThat(call().models()).containsExactly("ok-1", "ok-2");
    }

    @Test
    @DisplayName("上游 5xx/连接失败 → 502 models_unavailable")
    void upstreamFailureIs502() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM 连接失败", false, null, 0)));
        assertThatThrownBy(this::call).isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(502));
    }

    @Test
    @DisplayName("无 BYOK → 400 invalid_byok（本端点只服务 BYOK key 校验）")
    void requiresByok() {
        assertThatThrownBy(() -> service.listModels("t", null).await().atMost(Duration.ofSeconds(5)))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("列表上限 200 条")
    void capsAt200() {
        List<String> many = java.util.stream.IntStream.range(0, 300).mapToObj(i -> "m-" + String.format("%03d", i)).toList();
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(many));
        assertThat(call().models()).hasSize(200);
    }

    @Test
    @DisplayName("405 同 404 → models=null")
    void status405IsUnsupported() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (405)", true, null, 405)));
        assertThat(call().models()).isNull();
    }

    @Test
    @DisplayName("上游 500 → 502 models_unavailable")
    void status500Is502() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (500)", true, null, 500)));
        assertThatThrownBy(this::call).isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                var r = ((WebApplicationException) e).getResponse();
                assertThat(r.getStatus()).isEqualTo(502);
                assertThat(((java.util.Map<?, ?>) r.getEntity()).get("error")).isEqualTo("models_unavailable");
            });
    }

    private static LlmCallFailedException failure(int status, String body) {
        return new LlmCallFailedException("LLM API 错误 (" + status + ")", true, null, status,
            io.aster.llm.client.ProviderErrorClassifier.classify(status, body, false));
    }

    private void assertRejected422(String reason, String providerMessage, String providerErrorType) {
        assertThatThrownBy(this::call).isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                var r = ((WebApplicationException) e).getResponse();
                assertThat(r.getStatus()).isEqualTo(422);
                var entity = (java.util.Map<?, ?>) r.getEntity();
                assertThat(entity.get("error")).isEqualTo("provider_key_rejected");
                assertThat(entity.get("reason")).isEqualTo(reason);
                assertThat(entity.get("message")).isEqualTo(providerMessage);
                assertThat(entity.get("providerErrorType")).isEqualTo(providerErrorType);
            });
    }

    @Test
    @DisplayName("★事故：workspace 未绑定的 400 → 422 provider_key_rejected(reason=provider_bad_request) + 原文")
    void workspaceScope400Is422() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            failure(400, io.aster.llm.client.ProviderErrorClassifierTest.INCIDENT_BODY)));
        assertRejected422("provider_bad_request",
            io.aster.llm.client.ProviderErrorClassifierTest.WORKSPACE_MESSAGE, "invalid_request_error");
    }

    @Test
    @DisplayName("401 → 422 provider_key_rejected(reason=provider_auth_failed)")
    void unauthorizedIs422() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(failure(401,
            "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));
        assertRejected422("provider_auth_failed", "invalid x-api-key", "authentication_error");
    }

    @Test
    @DisplayName("429 / 529 → 仍是 502 models_unavailable（限流不代表 key 坏）")
    void rateLimitedStays502() {
        for (int status : new int[] {429, 529}) {
            when(client.listModels(any())).thenReturn(Uni.createFrom().failure(failure(status, "{}")));
            assertThatThrownBy(this::call).satisfies(e ->
                assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(502));
        }
    }

    @Test
    @DisplayName("client 同步抛 IllegalArgumentException（端点校验）→ 400 invalid_byok")
    void syncEndpointRejectionIs400() {
        when(client.listModels(any())).thenThrow(new IllegalArgumentException("ssrf"));
        assertThatThrownBy(this::call).isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                var r = ((WebApplicationException) e).getResponse();
                assertThat(r.getStatus()).isEqualTo(400);
                assertThat(((java.util.Map<?, ?>) r.getEntity()).get("error")).isEqualTo("invalid_byok");
            });
    }
}
