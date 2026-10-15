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

/** ADR 0048 §6：流式 400 的错误码判定在 client（唯一拿得到状态码的地方）。 */
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

    @Test
    @DisplayName("★流式：BYOK 指定 model + provider 400 → error 帧为 provider_rejected_model")
    void streamRejectedModel() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.OPENAI_NATIVE, false, "nope-1");

        List<LlmStreamEvent> events = client().streamChat(request(), opts)
            .collect().asList().await().atMost(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(ev -> {
            assertThat(ev.type()).isEqualTo(LlmStreamEvent.Type.ERROR);
            assertThat(ev.error()).isEqualTo("provider_rejected_model");
        });
    }

    @Test
    @DisplayName("流式：未指定 model 的 400 → 保持原文案 LLM API 错误 (400)")
    void streamPlain400() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.BYOK);

        List<LlmStreamEvent> events = client().streamChat(request(), opts)
            .collect().asList().await().atMost(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(ev -> assertThat(ev.error()).isEqualTo("LLM API 错误 (400)"));
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
