package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 非流式 {@code chat()} 失败时是否标明「已到达 provider」（cloud#518 计费口径）。
 *
 * <p>用真实本地服务器：要证明的是各种对端行为下 Vert.x WebClient 抛出的异常被正确归类。
 */
class VertxLlmClientChatReachedTest {

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
    @DisplayName("★连不上 provider（端口无人监听）：reachedProvider=false，不计费")
    void connectionRefusedNotReached() throws Exception {
        int port = listen(sock -> { });
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        server = null;

        Throwable t = chatFailure(port, Duration.ofSeconds(5));

        assertThat(t).isInstanceOf(LlmCallFailedException.class).hasMessage("LLM 连接失败");
        assertThat(((LlmCallFailedException) t).reachedProvider()).isFalse();
    }

    @Test
    @DisplayName("★provider 返回 500：已到达，reachedProvider=true")
    void providerErrorReached() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));

        Throwable t = chatFailure(port, Duration.ofSeconds(5));

        assertThat(t).isInstanceOf(LlmCallFailedException.class).hasMessage("LLM API 错误 (500)");
        assertThat(((LlmCallFailedException) t).reachedProvider()).isTrue();
    }

    @Test
    @DisplayName("★请求发出后 provider 沉默到超时：已到达，reachedProvider=true")
    void silentProviderReached() throws Exception {
        int port = listen(sock -> { });

        Throwable t = chatFailure(port, Duration.ofMillis(500));

        assertThat(t).isInstanceOf(LlmCallFailedException.class);
        assertThat(((LlmCallFailedException) t).reachedProvider()).isTrue();
    }

    @Test
    @DisplayName("provider 200 但响应体不是 JSON：已到达（解析失败）")
    void unparsableResponseReached() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 3\r\n\r\nnot"));

        Throwable t = chatFailure(port, Duration.ofSeconds(5));

        assertThat(t).isInstanceOf(LlmCallFailedException.class).hasMessage("LLM 响应解析失败");
        assertThat(((LlmCallFailedException) t).reachedProvider()).isTrue();
    }

    @Test
    @DisplayName("isConnectFailure 沿 cause 链识别被包装的连接失败")
    void connectFailureDetectedThroughCauseChain() {
        assertThat(LlmCallFailedException.isConnectFailure(
            new RuntimeException("wrap", new java.net.ConnectException("refused")))).isTrue();
        assertThat(LlmCallFailedException.isConnectFailure(new java.net.UnknownHostException("x"))).isTrue();
        assertThat(LlmCallFailedException.isConnectFailure(
            new java.util.concurrent.TimeoutException("read"))).isFalse();
        assertThat(LlmCallFailedException.isConnectFailure(null)).isFalse();
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

    private Throwable chatFailure(int port, Duration timeout) {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, timeout, timeout);
        io.aster.common.http.SharedWebClient shared = mock(io.aster.common.http.SharedWebClient.class);
        when(shared.client()).thenReturn(WebClient.create(vertx));
        client.sharedWebClient = shared;
        return catchThrowable(() -> client.chat(
                new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, false),
                new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port, LlmRuntimeOptions.Source.PLATFORM))
            .await().atMost(Duration.ofSeconds(20)));
    }
}
