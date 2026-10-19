package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmStreamEvent.Type#REACHED}：请求是否已到达 provider（cloud#470 计费口径）。
 *
 * <p>规则：连上 provider 后的调用一律计入 AI 配额，连不上的不计。cloud 只凭这个标记判断，
 * 所以它必须在「连上」时恰好出现一次、在「连不上」时绝不出现，且先于任何内容事件。
 * 用真实的本地服务器验证——要证明的是 Vert.x 客户端在各种对端行为下发不发这个事件。
 */
class VertxLlmClientReachedTest {

    private Vertx vertx;
    private NetServer server;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        vertx.close();
    }

    @Test
    @DisplayName("provider 正常流式返回：REACHED 恰好一次，且先于第一个内容事件")
    void reachedPrecedesContent() throws Exception {
        int port = listen(sock -> {
            sock.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");
            writeChunk(sock, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n");
            writeChunk(sock, "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n");
            writeChunk(sock, "data: [DONE]\n\n");
            sock.write("0\r\n\r\n");
        });

        List<LlmStreamEvent.Type> types = streamTypes(port, Duration.ofSeconds(5));

        assertThat(types).containsExactly(
            LlmStreamEvent.Type.REACHED, LlmStreamEvent.Type.DELTA, LlmStreamEvent.Type.DELTA);
    }

    @Test
    @DisplayName("★provider 返回 500：请求已到达第三方，REACHED 仍要发出（计费），随后才是 ERROR")
    void providerErrorStillReached() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));

        List<LlmStreamEvent> events = stream(port, Duration.ofSeconds(5));

        assertThat(events).extracting(LlmStreamEvent::type)
            .containsExactly(LlmStreamEvent.Type.REACHED, LlmStreamEvent.Type.ERROR);
        assertThat(events.get(1).error()).isEqualTo("provider_unavailable");
    }

    @Test
    @DisplayName("★请求发出后 provider 沉默直到读超时：已到达，REACHED 要发出")
    void silentProviderAfterSendStillReached() throws Exception {
        int port = listen(sock -> { });

        List<LlmStreamEvent.Type> types = streamTypes(port, Duration.ofMillis(500));

        assertThat(types).first().isEqualTo(LlmStreamEvent.Type.REACHED);
        assertThat(types).containsOnlyOnce(LlmStreamEvent.Type.REACHED);
    }

    @Test
    @DisplayName("★连不上 provider（端口无人监听）：没到达第三方，绝不发 REACHED（不计费）")
    void connectionRefusedNotReached() throws Exception {
        int port = listen(sock -> { });
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        server = null;

        List<LlmStreamEvent> events = stream(port, Duration.ofSeconds(5));

        assertThat(events).extracting(LlmStreamEvent::type).containsExactly(LlmStreamEvent.Type.ERROR);
        assertThat(events.get(0).error()).isEqualTo("LLM 连接失败");
    }

    private int listen(Consumer<NetSocket> onRequest) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        server = vertx.createNetServer().connectHandler(sock -> {
            // 收到完整请求头后再应答，模拟真实 provider 读完请求才响应
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

    private static void writeChunk(NetSocket sock, String payload) {
        sock.write(Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n");
    }

    private List<LlmStreamEvent.Type> streamTypes(int port, Duration readTimeout) throws Exception {
        return stream(port, readTimeout).stream().map(LlmStreamEvent::type).toList();
    }

    private List<LlmStreamEvent> stream(int port, Duration readTimeout) throws Exception {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, Duration.ofSeconds(5), readTimeout);
        List<LlmStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminated = new CountDownLatch(1);
        client.streamChat(
                new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, true),
                new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
                    LlmRuntimeOptions.Source.PLATFORM))
            .subscribe().with(events::add, err -> terminated.countDown(), terminated::countDown);
        assertThat(terminated.await(20, TimeUnit.SECONDS)).as("流应自行终止").isTrue();
        return events;
    }
}
