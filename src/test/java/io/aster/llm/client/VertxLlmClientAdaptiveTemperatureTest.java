package io.aster.llm.client;

import io.aster.llm.model.LlmChatResult;
import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 模型拒绝 temperature 时 client 内部重试一次并记忆；重试对下游不可见。 */
class VertxLlmClientAdaptiveTemperatureTest {

    private static final String SUCCESS_SSE =
        "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
            + "data: [DONE]\n\n";

    private static final String SUCCESS_CHAT =
        "{\"choices\":[{\"message\":{\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";

    private Vertx vertx;
    private NetServer server;
    /** 服务端收到的各次请求体（按到达顺序）。 */
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        vertx.close();
    }

    private static String http(int status, String contentType, String body) {
        return "HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType + "\r\nConnection: close\r\nContent-Length: "
            + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static String json(int status, String body) {
        return http(status, "application/json", body);
    }

    private static String sse(String body) {
        return http(200, "text/event-stream", body);
    }

    private static LlmRuntimeOptions options(int port, String provider, String model) {
        return new LlmRuntimeOptions("k", provider, "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.OPENAI_NATIVE, false, model);
    }

    private static LlmRequest request(boolean stream) {
        return new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, stream);
    }

    private List<LlmStreamEvent> stream(VertxLlmClient client, LlmRuntimeOptions opts) {
        return client.streamChat(request(true), opts).collect().asList().await().atMost(Duration.ofSeconds(10));
    }

    private static long count(List<LlmStreamEvent> events, LlmStreamEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    @Test
    @DisplayName("★流式：首次 400 温度被拒 → 第二次请求无 temperature，成功；仅 1 个 REACHED、无 ERROR、usage 来自第二次")
    void streamRetriesWithoutTemperature() throws Exception {
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY) : sse(SUCCESS_SSE));
        VertxLlmClient client = client();

        List<LlmStreamEvent> events = stream(client, options(port, "openai", "gpt-x"));

        assertThat(received).hasSize(2);
        assertThat(received.get(0).containsKey("temperature")).isTrue();
        assertThat(received.get(1).containsKey("temperature")).isFalse();
        JsonObject expected = received.get(0).copy();
        expected.remove("temperature");
        assertThat(received.get(1)).isEqualTo(expected);
        assertThat(count(events, LlmStreamEvent.Type.REACHED)).isEqualTo(1);
        assertThat(count(events, LlmStreamEvent.Type.ERROR)).isZero();
        assertThat(events.stream().filter(e -> e.type() == LlmStreamEvent.Type.DELTA).map(LlmStreamEvent::delta))
            .containsExactly("he", "llo");
        assertThat(events.stream().filter(e -> e.type() == LlmStreamEvent.Type.USAGE).toList()).hasSize(1);
        assertThat(events.stream().filter(e -> e.type() == LlmStreamEvent.Type.USAGE).findFirst().orElseThrow().usage().promptTokens())
            .isEqualTo(7);
    }

    @Test
    @DisplayName("流式 Anthropic：事故原文同样触发重试")
    void streamAnthropicRetries() throws Exception {
        String anthropicSse = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"ok\"}}\n\n"
            + "data: {\"type\":\"message_stop\"}\n\n";
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY) : sse(anthropicSse));

        List<LlmStreamEvent> events = stream(client(), options(port, "anthropic", "claude-opus-5-5"));

        assertThat(received).hasSize(2);
        assertThat(received.get(1).containsKey("temperature")).isFalse();
        assertThat(received.get(1).getString("model")).isEqualTo("claude-opus-5-5");
        assertThat(count(events, LlmStreamEvent.Type.ERROR)).isZero();
        assertThat(count(events, LlmStreamEvent.Type.REACHED)).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.type() == LlmStreamEvent.Type.DELTA).map(LlmStreamEvent::delta))
            .containsExactly("ok");
    }

    @Test
    @DisplayName("★记忆：首次检测后，同 provider+model 的下一次请求首发即不带 temperature（仅 1 次 HTTP）")
    void memoSkipsFailedRoundTrip() throws Exception {
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.OPENAI_UNSUPPORTED_BODY) : sse(SUCCESS_SSE));
        VertxLlmClient client = client();
        LlmRuntimeOptions opts = options(port, "openai", "o-reasoning");

        stream(client, opts);
        assertThat(received).hasSize(2);

        received.clear();
        List<LlmStreamEvent> second = stream(client, opts);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).containsKey("temperature")).isFalse();
        assertThat(count(second, LlmStreamEvent.Type.ERROR)).isZero();
        assertThat(count(second, LlmStreamEvent.Type.REACHED)).isEqualTo(1);
    }

    @Test
    @DisplayName("记忆按 model 区分：另一个 model 仍先带 temperature")
    void memoIsPerModel() throws Exception {
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.OPENAI_UNSUPPORTED_BODY) : sse(SUCCESS_SSE));
        VertxLlmClient client = client();

        stream(client, options(port, "openai", "o-reasoning"));
        received.clear();
        stream(client, options(port, "openai", "gpt-4o"));

        assertThat(received.get(0).containsKey("temperature")).isTrue();
    }

    @Test
    @DisplayName("★重试仍失败（另一条 400）→ 按常规分类上报，恰 1 个 ERROR，共 2 次 HTTP，不再重试")
    void streamRetryAlsoFails() throws Exception {
        int port = listen(n -> n == 1
            ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY)
            : json(400, ProviderErrorClassifierTest.INCIDENT_BODY));

        List<LlmStreamEvent> events = stream(client(), options(port, "anthropic", "claude-opus-5-5"));

        assertThat(received).hasSize(2);
        assertThat(count(events, LlmStreamEvent.Type.ERROR)).isEqualTo(1);
        assertThat(count(events, LlmStreamEvent.Type.REACHED)).isEqualTo(1);
        LlmStreamEvent err = events.stream().filter(e -> e.type() == LlmStreamEvent.Type.ERROR).findFirst().orElseThrow();
        assertThat(err.error()).isEqualTo("provider_bad_request");
        assertThat(err.message()).isEqualTo(ProviderErrorClassifierTest.WORKSPACE_MESSAGE);
    }

    @Test
    @DisplayName("无关 400 不重试：只 1 次 HTTP，照常报错")
    void streamUnrelated400NoRetry() throws Exception {
        int port = listen(n -> json(400, ProviderErrorClassifierTest.INCIDENT_BODY));

        List<LlmStreamEvent> events = stream(client(), options(port, "anthropic", "m"));

        assertThat(received).hasSize(1);
        assertThat(count(events, LlmStreamEvent.Type.ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("请求体本就无 temperature（已记忆）却仍收到温度 400 → 不重试")
    void noRetryWhenBodyHadNoTemperature() throws Exception {
        int port = listen(n -> json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY));
        VertxLlmClient client = client();
        LlmRuntimeOptions opts = options(port, "openai", "weird");
        // 先让记忆生效：第 1 次 400、第 2 次（无 temperature）也 400 → 报错
        stream(client, opts);
        received.clear();

        List<LlmStreamEvent> events = stream(client, opts);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).containsKey("temperature")).isFalse();
        assertThat(count(events, LlmStreamEvent.Type.ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("★非流式 chat：首次 400 温度被拒 → 无 temperature 重试成功，返回内容与 usage")
    void chatRetriesWithoutTemperature() throws Exception {
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY) : json(200, SUCCESS_CHAT));
        VertxLlmClient client = client();

        LlmChatResult result = client.chat(request(false), options(port, "openai", "gpt-x"))
            .await().atMost(Duration.ofSeconds(10));

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.usage().promptTokens()).isEqualTo(7);
        assertThat(received).hasSize(2);
        assertThat(received.get(0).containsKey("temperature")).isTrue();
        JsonObject expected = received.get(0).copy();
        expected.remove("temperature");
        assertThat(received.get(1)).isEqualTo(expected);
    }

    @Test
    @DisplayName("非流式 chat：记忆生效后下一次首发即无 temperature（仅 1 次 HTTP）")
    void chatMemo() throws Exception {
        int port = listen(n -> n == 1 ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY) : json(200, SUCCESS_CHAT));
        VertxLlmClient client = client();
        LlmRuntimeOptions opts = options(port, "openai", "gpt-x");
        client.chat(request(false), opts).await().atMost(Duration.ofSeconds(10));
        received.clear();
        client.chat(request(false), opts).await().atMost(Duration.ofSeconds(10));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).containsKey("temperature")).isFalse();
    }

    @Test
    @DisplayName("非流式 chat：重试仍 400（别的原因）→ 分类异常，共 2 次 HTTP")
    void chatRetryAlsoFails() throws Exception {
        int port = listen(n -> n == 1
            ? json(400, ProviderErrorClassifierTemperatureTest.ANTHROPIC_INCIDENT_BODY)
            : json(400, ProviderErrorClassifierTest.INCIDENT_BODY));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client().chat(request(false), options(port, "anthropic", "m")).await().atMost(Duration.ofSeconds(10)))
            .isInstanceOfSatisfying(LlmCallFailedException.class, e -> {
                assertThat(e.statusCode()).isEqualTo(400);
                assertThat(e.providerError().code()).isEqualTo("provider_bad_request");
                assertThat(e.providerError().providerMessage()).isEqualTo(ProviderErrorClassifierTest.WORKSPACE_MESSAGE);
            });
        assertThat(received).hasSize(2);
    }

    private VertxLlmClient client() {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, Duration.ofSeconds(5), Duration.ofSeconds(5));
        io.aster.common.http.SharedWebClient shared = mock(io.aster.common.http.SharedWebClient.class);
        when(shared.client()).thenReturn(WebClient.create(vertx));
        client.sharedWebClient = shared;
        client.llmMetrics = mock(io.aster.llm.metrics.LlmMetrics.class);
        return client;
    }

    /**
     * 起一个最小 HTTP 服务：解析请求体（Content-Length）记入 {@link #received}，按「第 n 次请求」返回整段响应并关闭连接。
     */
    private int listen(IntFunction<String> responder) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        int[] seq = {0};
        server = vertx.createNetServer().connectHandler(sock -> {
            Buffer acc = Buffer.buffer();
            boolean[] answered = {false};
            sock.handler(buf -> {
                acc.appendBuffer(buf);
                String text = acc.toString();
                int headEnd = text.indexOf("\r\n\r\n");
                if (answered[0] || headEnd < 0) return;
                int len = contentLength(text.substring(0, headEnd));
                byte[] raw = acc.getBytes();
                int bodyStart = text.substring(0, headEnd + 4).getBytes(StandardCharsets.UTF_8).length;
                if (raw.length - bodyStart < len) return;
                answered[0] = true;
                received.add(new JsonObject(new String(raw, bodyStart, len, StandardCharsets.UTF_8)));
                int n;
                synchronized (seq) {
                    n = ++seq[0];
                }
                sock.write(responder.apply(n)).onComplete(ar -> sock.close());
            });
        });
        server.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        return server.actualPort();
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }
}
