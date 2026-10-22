package io.aster.llm.service;

import io.aster.llm.api.dto.AssistantRequest;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.LlmUsage;
import io.aster.llm.model.ValidationResult;
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
import static org.mockito.Mockito.when;

/**
 * 终帧的截断标记：上游因输出上限停止时，流的最后一帧必须是带 {@code "truncated":true} 的 final，
 * 否则长回答在中途戛然而止、用户无从得知（生产 E2E F5）。
 *
 * <p>契约：final 帧顶层布尔 {@code truncated} 恒在；截断时另带 {@code "stopReason":"max_tokens"}。
 * suggest / assistant 的 final 不带 validated（问答类没有编译结论）。
 */
class LlmProxyServiceTruncationTest {

    private static final String META = "{\"type\":\"meta\",\"data\":\"m\"}";
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
        when(service.promptComposer.buildGenerateContext(anyString(), any())).thenAnswer(inv -> new PromptContext().model("m"));
        when(service.promptComposer.buildSuggestContext(anyString(), any())).thenAnswer(inv -> new PromptContext().model("m"));
        when(service.promptComposer.buildAssistantContext(anyString(), any())).thenAnswer(inv -> new PromptContext().model("m"));
    }

    private void providerEmits(LlmStreamEvent... events) {
        when(client.streamChat(any(), any())).thenReturn(Multi.createFrom().items(events));
    }

    private static List<String> collect(Multi<String> frames) {
        return frames.collect().asList().await().atMost(Duration.ofSeconds(10));
    }

    private List<String> suggest() {
        return collect(service.streamSuggest("t", new SuggestRequest("s", null, null, null), null, "r"));
    }

    @Test
    @DisplayName("★suggest：OpenAI finish_reason=length → final 带 truncated:true 与 stopReason")
    void suggestTruncatedByLength() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("Hello "), LlmStreamEvent.delta("wor"),
            LlmStreamEvent.stop(LlmStreamEvent.STOP_MAX_TOKENS), LlmStreamEvent.done());

        assertThat(suggest()).containsExactly(META, REACHED,
            "{\"type\":\"delta\",\"data\":\"Hello \"}",
            "{\"type\":\"delta\",\"data\":\"wor\"}",
            "{\"type\":\"final\",\"data\":\"Hello wor\",\"truncated\":true,\"stopReason\":\"max_tokens\"}");
    }

    @Test
    @DisplayName("★assistant：Anthropic 停止原因随 USAGE 到达，同样标记截断")
    void assistantTruncatedViaUsageEvent() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("答"),
            LlmStreamEvent.usage(new LlmUsage(0, 8192)).withStopReason(LlmStreamEvent.STOP_MAX_TOKENS));

        List<String> frames = collect(service.streamAssistant("t", mock(AssistantRequest.class), null, "r"));
        assertThat(frames).last().isEqualTo(
            "{\"type\":\"final\",\"data\":\"答\",\"truncated\":true,\"stopReason\":\"max_tokens\"}");
    }

    @Test
    @DisplayName("suggest：正常停止 → final 带 truncated:false 与原样停止原因")
    void suggestNormalStop() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("ok"), LlmStreamEvent.stop("stop"));

        assertThat(suggest()).last().isEqualTo(
            "{\"type\":\"final\",\"data\":\"ok\",\"truncated\":false,\"stopReason\":\"stop\"}");
    }

    @Test
    @DisplayName("★出现 error 帧时不再追加 final，避免把失败当成功收尾")
    void noFinalAfterError() {
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("x"), LlmStreamEvent.error("流式响应异常"));

        assertThat(suggest()).noneMatch(f -> f.contains("\"type\":\"final\""))
            .last().isEqualTo("{\"type\":\"error\",\"error\":\"流式响应异常\"}");
    }

    @Test
    @DisplayName("★generate：最终代码被截断时 final 同样带 truncated:true（与 validated 并存）")
    void generateTruncated() {
        when(service.validator.validate(anyString(), any())).thenReturn(ValidationResult.success("m", "f"));
        when(service.validator.cleanLlmOutput(anyString())).thenAnswer(inv -> inv.getArgument(0));
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("Rule r"),
            LlmStreamEvent.stop(LlmStreamEvent.STOP_MAX_TOKENS));

        List<String> frames = collect(service.streamGenerate("t",
            new GeneratePolicyRequest("g", null, null, null, null), null, "r"));
        assertThat(frames).last().isEqualTo(
            "{\"type\":\"final\",\"data\":\"Rule r\",\"validated\":true,\"truncated\":true,\"stopReason\":\"max_tokens\"}");
    }

    @Test
    @DisplayName("generate：未截断时 final 带 truncated:false，旧字段不变")
    void generateNotTruncated() {
        when(service.validator.validate(anyString(), any())).thenReturn(ValidationResult.success("m", "f"));
        when(service.validator.cleanLlmOutput(anyString())).thenAnswer(inv -> inv.getArgument(0));
        providerEmits(LlmStreamEvent.reached(), LlmStreamEvent.delta("Rule r"));

        List<String> frames = collect(service.streamGenerate("t",
            new GeneratePolicyRequest("g", null, null, null, null), null, "r"));
        assertThat(frames).last().isEqualTo(
            "{\"type\":\"final\",\"data\":\"Rule r\",\"validated\":true,\"truncated\":false}");
    }
}
