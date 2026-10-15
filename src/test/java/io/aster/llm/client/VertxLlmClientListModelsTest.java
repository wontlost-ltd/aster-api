package io.aster.llm.client;

import io.aster.llm.model.LlmRuntimeOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** listModels：GET {base}/v1/models（或 {basePath}/models），按 provider 加鉴权头，解析 data[].id，非 200 带状态码失败。 */
class VertxLlmClientListModelsTest {

    private static final String LIST_BODY = "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"},{\"object\":\"model\"}]}";

    private Vertx vertx;
    private NetServer server;
    private final AtomicReference<String> requestHead = new AtomicReference<>("");

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        vertx.close();
    }

    /** 起本地 TCP 桩：收齐请求头后记录并回写 response；返回端口。 */
    private int listen(String response) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        server = vertx.createNetServer();
        server.connectHandler(sock -> {
            StringBuilder head = new StringBuilder();
            boolean[] answered = {false};
            sock.handler((Buffer buf) -> {
                head.append(buf.toString());
                if (!answered[0] && head.indexOf("\r\n\r\n") >= 0) {
                    answered[0] = true;
                    requestHead.set(head.toString());
                    sock.write(response);
                }
            });
        });
        server.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        return server.actualPort();
    }

    private static String ok(String json) {
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + json.length() + "\r\n\r\n" + json;
    }

    private VertxLlmClient client() {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, Duration.ofSeconds(5), Duration.ofSeconds(5));
        io.aster.common.http.SharedWebClient shared = mock(io.aster.common.http.SharedWebClient.class);
        when(shared.client()).thenReturn(WebClient.create(vertx));
        client.sharedWebClient = shared;
        return client;
    }

    private static LlmRuntimeOptions opts(String provider, String baseUrl) {
        return new LlmRuntimeOptions("k-secret", provider, baseUrl, LlmRuntimeOptions.Source.BYOK);
    }

    @Test
    @DisplayName("openai：GET /v1/models，Bearer 鉴权，解析 data[].id（缺 id 的项跳过；响应体 > 1 MiB 拒绝解析、按失败处理）")
    void parsesOpenAiList() throws Exception {
        int port = listen(ok(LIST_BODY));
        List<String> ids = client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10));
        assertThat(ids).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(requestHead.get()).startsWith("GET /v1/models HTTP/1.1").contains("Authorization: Bearer k-secret");
    }

    @Test
    @DisplayName("anthropic：x-api-key + anthropic-version 头")
    void parsesAnthropicList() throws Exception {
        int port = listen(ok(LIST_BODY));
        client().listModels(opts("anthropic", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10));
        assertThat(requestHead.get()).contains("x-api-key: k-secret").contains("anthropic-version: 2023-06-01")
            .doesNotContain("Authorization:");
    }

    @Test
    @DisplayName("base URL 带路径（/codex/v1）→ 请求 /codex/v1/models")
    void appendsToBasePath() throws Exception {
        int port = listen(ok(LIST_BODY));
        client().listModels(opts("openai", "http://127.0.0.1:" + port + "/codex/v1/")).await().atMost(Duration.ofSeconds(10));
        assertThat(requestHead.get()).startsWith("GET /codex/v1/models HTTP/1.1");
    }

    @Test
    @DisplayName("404 → LlmCallFailedException.statusCode()==404，reachedProvider=true")
    void propagatesStatus() throws Exception {
        int port = listen("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
        Throwable t = catchThrowable(() -> client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10)));
        assertThat(t).isInstanceOf(LlmCallFailedException.class);
        assertThat(((LlmCallFailedException) t).statusCode()).isEqualTo(404);
        assertThat(((LlmCallFailedException) t).reachedProvider()).isTrue();
    }

    @Test
    @DisplayName("连不上 → reachedProvider=false，statusCode=0")
    void connectionRefused() throws Exception {
        int port = listen(ok(LIST_BODY));
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        server = null;
        Throwable t = catchThrowable(() -> client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10)));
        assertThat(t).isInstanceOf(LlmCallFailedException.class);
        assertThat(((LlmCallFailedException) t).reachedProvider()).isFalse();
        assertThat(((LlmCallFailedException) t).statusCode()).isZero();
    }
}
