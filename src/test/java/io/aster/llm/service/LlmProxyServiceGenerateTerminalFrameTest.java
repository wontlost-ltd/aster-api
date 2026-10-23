package io.aster.llm.service;

import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * generate 流必须以终帧（final 或 error）收尾（生产 E2E F3：编辑器面板看到了流式代码却没有校验徽标，
 * 说明 final 帧没有到达）。校验器 / 修复环节抛异常时也不能静默结束。
 */
class LlmProxyServiceGenerateTerminalFrameTest {

    private static final String VALIDATING = "{\"type\":\"validating\"}";

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
        when(service.config.validation().maxAttempts()).thenReturn(2);
        service.validator = mock(PolicyCompileValidator.class);
        when(service.validator.cleanLlmOutput(anyString())).thenAnswer(inv -> inv.getArgument(0));
        service.usageReporter = mock(AiUsageReporter.class);
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
        when(service.optionsResolver.resolve(anyString(), any()))
            .thenReturn(new LlmRuntimeOptions("k", "openai", "http://127.0.0.1", LlmRuntimeOptions.Source.PLATFORM));
        when(service.promptComposer.buildGenerateContext(anyString(), any())).thenAnswer(inv -> new PromptContext().model("m"));
        when(client.streamChat(any(), any())).thenAnswer(inv ->
            Multi.createFrom().items(LlmStreamEvent.reached(), LlmStreamEvent.delta("Rule r")));
    }

    private List<String> generate() {
        return service.streamGenerate("t", new GeneratePolicyRequest("g", null, null, null, null), null, "r")
            .collect().asList().await().atMost(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("★校验器抛异常：仍以 final(validated=false, diagnostics) 收尾，代码照常给出")
    void validatorThrows() {
        when(service.validator.validate(anyString(), any())).thenThrow(new IllegalStateException("parser crashed"));

        List<String> frames = generate();

        assertThat(frames).contains(VALIDATING);
        assertThat(frames).last().isEqualTo("{\"type\":\"final\",\"data\":\"Rule r\",\"validated\":false,"
            + "\"truncated\":false,\"diagnostics\":[\"" + LlmProxyService.VALIDATION_UNAVAILABLE + "\"]}");
        assertThat(String.join("", frames)).doesNotContain("parser crashed");
    }

    @Test
    @DisplayName("★修复环节抛异常（组装修复 prompt 失败）：同样以 final 收尾")
    void repairThrows() {
        when(service.validator.validate(anyString(), any())).thenReturn(ValidationResult.failure("E1"));
        when(service.promptComposer.buildRepairContext(any(), anyString(), any(), any()))
            .thenThrow(new IllegalStateException("template missing"));

        assertThat(generate()).last().asString()
            .startsWith("{\"type\":\"final\",\"data\":\"Rule r\",\"validated\":false");
    }

    @Test
    @DisplayName("validating 保活帧先于终帧；次数用尽的 final 带编译错误作为 diagnostics")
    void exhaustedAttemptsCarryDiagnostics() {
        when(service.validator.validate(anyString(), any())).thenReturn(ValidationResult.failure("E1"));
        when(service.promptComposer.buildRepairContext(any(), anyString(), any(), any()))
            .thenAnswer(inv -> new PromptContext().model("m"));

        List<String> frames = generate();

        assertThat(frames.indexOf(VALIDATING)).isLessThan(frames.size() - 1);
        assertThat(frames).last().isEqualTo("{\"type\":\"final\",\"data\":\"Rule r\",\"validated\":false,"
            + "\"truncated\":false,\"diagnostics\":[\"E1\"]}");
        verify(client, times(2)).streamChat(any(), any());
    }

    @Test
    @DisplayName("provider 报错后不再校验、不触发修复重试")
    void providerErrorStopsWithoutRepair() {
        when(client.streamChat(any(), any())).thenAnswer(inv -> Multi.createFrom().items(
            LlmStreamEvent.reached(), LlmStreamEvent.delta("Rule"), LlmStreamEvent.error("流式响应异常")));

        List<String> frames = generate();

        assertThat(frames).last().isEqualTo("{\"type\":\"error\",\"error\":\"流式响应异常\"}");
        assertThat(frames).doesNotContain(VALIDATING);
        verify(client, times(1)).streamChat(any(), any());
    }
}
