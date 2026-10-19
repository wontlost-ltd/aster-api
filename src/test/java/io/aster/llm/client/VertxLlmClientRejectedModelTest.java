package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR 0048 §6（2026-10-19 修订）：provider 错误码判定在 client（唯一拿得到状态码与错误体的地方）。 */
class VertxLlmClientRejectedModelTest {

    private Vertx vertx;
    private NetServer server;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        vertx.close();
    }

    private static String http(int status, String reason, String body) {
        return "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
            + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static LlmRuntimeOptions anthropicByok(int port, String model) {
        return new LlmRuntimeOptions("k", "anthropic", "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.OPENAI_NATIVE, false, model);
    }

    private LlmStreamEvent firstError(LlmRuntimeOptions opts) {
        List<LlmStreamEvent> events = client().streamChat(request(), opts)
            .collect().asList().await().atMost(Duration.ofSeconds(10));
        return events.stream().filter(e -> e.type() == LlmStreamEvent.Type.ERROR).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★事故回归：流式 workspace 未绑定 400（带显式 model）→ provider_bad_request + 原文 + type")
    void streamWorkspaceScope400() throws Exception {
        int port = listen(sock -> sock.write(http(400, "Bad Request", ProviderErrorClassifierTest.INCIDENT_BODY)));
        LlmStreamEvent ev = firstError(anthropicByok(port, "claude-sonnet-4-5"));
        assertThat(ev.error()).isEqualTo("provider_bad_request");
        assertThat(ev.message()).isEqualTo(ProviderErrorClassifierTest.WORKSPACE_MESSAGE);
        assertThat(ev.providerErrorType()).isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("★I1 流式：平台 key（PLATFORM）失败 → 保留 code 与 providerErrorType，省略 message")
    void streamPlatformSourceOmitsMessage() throws Exception {
        int port = listen(sock -> sock.write(http(400, "Bad Request", ProviderErrorClassifierTest.INCIDENT_BODY)));
        LlmRuntimeOptions platform = new LlmRuntimeOptions("k", "anthropic", "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.PLATFORM);
        LlmStreamEvent ev = firstError(platform);
        assertThat(ev.error()).isEqualTo("provider_bad_request");
        assertThat(ev.providerErrorType()).isEqualTo("invalid_request_error");
        assertThat(ev.message()).isNull();
    }

    @Test
    @DisplayName("流式：404 not_found_error + 显式 model → provider_rejected_model")
    void streamNotFoundIsRejectedModel() throws Exception {
        int port = listen(sock -> sock.write(http(404, "Not Found",
            "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: nope-1\"}}")));
        LlmStreamEvent ev = firstError(anthropicByok(port, "nope-1"));
        assertThat(ev.error()).isEqualTo("provider_rejected_model");
        assertThat(ev.message()).isEqualTo("model: nope-1");
    }

    @Test
    @DisplayName("流式：401 → provider_auth_failed；空 body 的 400 → provider_bad_request 且无 message")
    void streamAuthAndEmptyBody() throws Exception {
        int port = listen(sock -> sock.write(http(401, "Unauthorized",
            "{\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}")));
        assertThat(firstError(anthropicByok(port, null)).error()).isEqualTo("provider_auth_failed");
        server.close();

        int port2 = listen(sock -> sock.write(http(400, "Bad Request", "{}")));
        LlmStreamEvent ev = firstError(anthropicByok(port2, "m"));
        assertThat(ev.error()).isEqualTo("provider_bad_request");
        assertThat(ev.message()).isNull();
        assertThat(ev.providerErrorType()).isNull();
    }

    @Test
    @DisplayName("流式：529 overloaded → provider_unavailable")
    void streamOverloaded() throws Exception {
        int port = listen(sock -> sock.write(http(529, "Overloaded",
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));
        assertThat(firstError(anthropicByok(port, null)).error()).isEqualTo("provider_unavailable");
    }

    @Test
    @DisplayName("非流式 chat：失败异常携带分类结果（statusCode 保留）")
    void chatCarriesClassification() throws Exception {
        int port = listen(sock -> sock.write(http(400, "Bad Request", ProviderErrorClassifierTest.INCIDENT_BODY)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client().chat(request(), anthropicByok(port, "m")).await().atMost(Duration.ofSeconds(10)))
            .isInstanceOfSatisfying(LlmCallFailedException.class, e -> {
                assertThat(e.statusCode()).isEqualTo(400);
                assertThat(e.providerError().code()).isEqualTo("provider_bad_request");
                assertThat(e.providerError().providerMessage()).isEqualTo(ProviderErrorClassifierTest.WORKSPACE_MESSAGE);
            });
    }

    @Test
    @DisplayName("listModels：失败异常携带分类结果；404 不因无 model 被当成 rejected_model")
    void listModelsCarriesClassification() throws Exception {
        int port = listen(sock -> sock.write(http(400, "Bad Request", ProviderErrorClassifierTest.INCIDENT_BODY)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client().listModels(anthropicByok(port, null)).await().atMost(Duration.ofSeconds(10)))
            .isInstanceOfSatisfying(LlmCallFailedException.class, e ->
                assertThat(e.providerError().code()).isEqualTo("provider_bad_request"));
    }

    private static LlmRequest request() {
        return new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, true);
    }

    private VertxLlmClient client() {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, Duration.ofSeconds(5), Duration.ofSeconds(5));
        io.aster.common.http.SharedWebClient shared = mock(io.aster.common.http.SharedWebClient.class);
        when(shared.client()).thenReturn(WebClient.create(vertx));
        client.sharedWebClient = shared;
        return client;
    }

    private int listen(Consumer<io.vertx.core.net.NetSocket> onRequest) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        server = vertx.createNetServer().connectHandler(sock -> {
            StringBuilder head = new StringBuilder();
            boolean[] answered = {false};
            sock.handler(buf -> {
                head.append(buf.toString());
                if (!answered[0] && head.indexOf("\r\n\r\n") >= 0) {
                    answered[0] = true;
                    onRequest.accept(sock);
                }
            });
        });
        server.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        return server.actualPort();
    }
}
