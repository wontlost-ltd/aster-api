package io.aster.llm.api;

import io.aster.llm.api.dto.ModelsResponse;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.service.LlmProxyService;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.security.apikey.InternalCallerFilter;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /api/v1/ai/models 资源层：envelope 只信任已验签 body（ADR 0048 §3.3）。 */
class AiAssistantResourceModelsTest {

    private AiAssistantResource resource;

    private static LlmConfig enabledConfig() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "enabled" -> true;
            case "toString" -> "LlmConfigStub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (LlmConfig) Proxy.newProxyInstance(LlmConfig.class.getClassLoader(), new Class<?>[]{LlmConfig.class}, h);
    }

    @BeforeEach
    void setUp() {
        resource = new AiAssistantResource();
        resource.byokParser = new ByokEnvelopeParser();
        resource.llmProxyService = mock(LlmProxyService.class);
        resource.identityResolver = mock(RequestIdentityResolver.class);
        when(resource.identityResolver.tenantId()).thenReturn("t");
        resource.llmConfig = enabledConfig();
    }

    private static ContainerRequestContext ctx(boolean verified, String bodyJson) {
        Map<String, Object> props = Map.of(
            InternalCallerFilter.HMAC_VERIFIED_PROP, verified ? Boolean.TRUE : Boolean.FALSE,
            InternalCallerFilter.VERIFIED_BODY_PROP, bodyJson == null ? new byte[0] : bodyJson.getBytes(StandardCharsets.UTF_8));
        return (ContainerRequestContext) Proxy.newProxyInstance(ContainerRequestContext.class.getClassLoader(),
            new Class<?>[]{ContainerRequestContext.class},
            (proxy, method, args) -> "getProperty".equals(method.getName()) ? props.get((String) args[0]) : null);
    }

    @Test
    @DisplayName("已验签 body 带 _byok → 解析后交给 service.listModels；tenant 来自 identityResolver")
    void parsesEnvelopeFromVerifiedBody() {
        resource.requestContext = ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}");
        when(resource.llmProxyService.listModels(eq("t"), any())).thenReturn(Uni.createFrom().item(
            new ModelsResponse("openai", List.of("gpt-4o"), "gpt-4o-mini", "now")));
        resource.models("{}").await().atMost(Duration.ofSeconds(1));
        verify(resource.llmProxyService).listModels(eq("t"), argThat(b -> "openai".equals(b.provider())));
    }

    @Test
    @DisplayName("★未验签请求（公网/浏览器）→ _byok 被忽略，service 收到 null（由其回 400 invalid_byok）")
    void unverifiedBodyIsIgnored() {
        resource.requestContext = ctx(false, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}");
        when(resource.llmProxyService.listModels(eq("t"), isNull())).thenReturn(Uni.createFrom().item(
            new ModelsResponse("openai", null, "gpt-4o-mini", "now")));
        resource.models("{}").await().atMost(Duration.ofSeconds(1));
        verify(resource.llmProxyService).listModels(eq("t"), isNull());
    }

    @Test
    @DisplayName("★已验签但 envelope 非法（缺 apiKey）→ 资源层 400 invalid_byok，不调 service")
    void invalidVerifiedEnvelopeIs400() {
        resource.requestContext = ctx(true, "{\"_byok\":{\"provider\":\"openai\"}}");
        assertThatThrownBy(() -> resource.models("{}"))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                WebApplicationException wae = (WebApplicationException) e;
                assertThat(wae.getResponse().getStatus()).isEqualTo(400);
                assertThat(((Map<?, ?>) wae.getResponse().getEntity()).get("error")).isEqualTo("invalid_byok");
            });
        verify(resource.llmProxyService, never()).listModels(any(), any());
    }
}
