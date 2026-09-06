package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式 LLM 的<b>读超时</b>护栏（issue #302 H0）。
 *
 * <p>★被修的缺陷：{@code streamChat} 把 {@code readTimeout}（120s）填进了
 * {@code setConnectTimeout}，于是
 * <ul>
 *   <li>连接超时被放大到 120s（本应是 {@code timeout()} 的 30s）；</li>
 *   <li><b>根本没有配读超时</b> —— provider 建连成功后挂住不发数据，
 *       连接与请求就永久悬着，只能靠客户端断开或进程重启释放。</li>
 * </ul>
 *
 * <p>本用例起一个<b>真实的</b>「接受连接但从不响应」的服务器来验证。
 * 不用 mock：要证明的正是「Vert.x 客户端在对端沉默时会不会自己超时」，
 * 那是客户端配置与网络栈的行为，mock 掉就什么也没验证。
 */
class VertxLlmClientReadTimeoutTest {

    private Vertx vertx;
    private io.vertx.core.net.NetServer silentServer;

    @AfterEach
    void tearDown() {
        if (silentServer != null) {
            silentServer.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    /**
     * ★核心断言：对端接受连接后<b>永不发送任何字节</b>，流必须在读超时后终止。
     *
     * <p>缺陷版（无读超时）在这里会一直挂着，直到测试超时失败 ——
     * 那正是生产上「请求线程与连接被永久占用」的最小复现。
     */
    @Test
    @DisplayName("对端建连后沉默，流必须因读超时而终止而非永久挂起")
    void silentPeerMustTriggerReadTimeout() throws Exception {
        vertx = Vertx.vertx();

        // 接受 TCP 连接，然后什么都不做——不回状态行、不回头、不回体
        AtomicBoolean accepted = new AtomicBoolean(false);
        CountDownLatch listening = new CountDownLatch(1);
        silentServer = vertx.createNetServer()
            .connectHandler(sock -> accepted.set(true));
        silentServer.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        int port = silentServer.actualPort();

        VertxLlmClient client = newClient(vertx,
            Duration.ofSeconds(5),    // connectTimeout
            Duration.ofMillis(800));  // readTimeout（空闲）

        List<LlmStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminated = new CountDownLatch(1);

        client.streamChat(
                new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, true),
                new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
                    LlmRuntimeOptions.Source.PLATFORM))
            .subscribe().with(
                events::add,
                err -> terminated.countDown(),
                terminated::countDown);

        // 读超时 800ms；给足余量但**远小于**「永久挂起」
        boolean finished = terminated.await(20, TimeUnit.SECONDS);

        assertThat(accepted.get())
            .as("★服务器必须真的收到了连接——没连上的话下面证明的就不是读超时")
            .isTrue();
        assertThat(finished)
            .as("★对端沉默时流必须自行终止（读超时）。挂住不返回 = 请求线程与连接被永久占用，"
                + "正是 issue #302 H0 要修的缺陷")
            .isTrue();
    }

    /**
     * 读超时是<b>空闲</b>超时而非总时长：持续有数据到达时不得被拦腰砍断。
     *
     * <p>★没有这条，「把超时调小」这种修法会看起来也能过第一个用例，
     * 却会把正常的长回答砍断——那是比超时缺失更糟的回归。
     */
    @Test
    @DisplayName("持续有数据到达时不得触发读超时（空闲语义，非总时长）")
    void steadyChunksMustNotTripIdleTimeout() throws Exception {
        vertx = Vertx.vertx();

        // 每 100ms 推一个 SSE 事件，连推 12 次（≈1.2s），最后 [DONE]
        CountDownLatch listening = new CountDownLatch(1);
        silentServer = vertx.createNetServer().connectHandler(sock -> {
            sock.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n");
            int[] n = {0};
            long timer = vertx.setPeriodic(100, tid -> {
                if (n[0]++ < 12) {
                    String payload = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n";
                    sock.write(Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n");
                } else {
                    String done = "data: [DONE]\n\n";
                    sock.write(Integer.toHexString(done.length()) + "\r\n" + done + "\r\n");
                    sock.write("0\r\n\r\n");
                    vertx.cancelTimer(tid);
                }
            });
            sock.closeHandler(v -> vertx.cancelTimer(timer));
        });
        silentServer.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        int port = silentServer.actualPort();

        // 空闲超时 400ms —— 大于 chunk 间隔(100ms)，但**远小于**总时长(≈1.3s)。
        // 若实现用的是「总时长」而非「空闲」，这里必然提前中断、收不到全部事件。
        VertxLlmClient client = newClient(vertx,
            Duration.ofSeconds(5), Duration.ofMillis(400));

        List<LlmStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminated = new CountDownLatch(1);
        client.streamChat(
                new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, true),
                new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
                    LlmRuntimeOptions.Source.PLATFORM))
            .subscribe().with(events::add, err -> terminated.countDown(), terminated::countDown);

        assertThat(terminated.await(20, TimeUnit.SECONDS))
            .as("流应正常结束")
            .isTrue();

        long deltaEvents = events.stream()
            .filter(e -> e.type() == LlmStreamEvent.Type.DELTA)
            .count();
        assertThat(deltaEvents)
            .as("★总时长(≈1.3s)远超空闲超时(400ms)，但每 100ms 就有数据——"
                + "空闲计时应被不断重置，12 个内容事件一个都不能少。"
                + "少了就说明用的是总时长而非空闲语义，正常的长回答会被砍断")
            .isEqualTo(12L);
        assertThat(events)
            .as("★不得混入错误事件——出现错误说明被超时误杀")
            .noneMatch(e -> e.type() == LlmStreamEvent.Type.ERROR);
    }

    /** 手工组装 client：这些依赖都是包私有字段，测试里直接注入即可，无需 CDI。 */
    private VertxLlmClient newClient(Vertx v, Duration connect, Duration read) {
        VertxLlmClient c = new VertxLlmClient();
        c.mutinyVertx = io.vertx.mutiny.core.Vertx.newInstance(v);
        c.config = new StubConfig(connect, read);
        c.sseParser = new SseEventParser();
        return c;
    }

    /** 只实现被 streamChat 用到的几项；其余抛异常——用到了就该在测试里显式面对。 */
    private record StubConfig(Duration connect, Duration read)
        implements io.aster.llm.config.LlmConfig {

        @Override
        public Duration timeout() {
            return connect;
        }

        @Override
        public Duration readTimeout() {
            return read;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String provider() {
            return "openai";
        }

        @Override
        public String baseUrl() {
            return "http://127.0.0.1";
        }

        @Override
        public String model() {
            return "m";
        }

        @Override
        public double temperature() {
            return 0.2;
        }

        @Override
        public int maxTokens() {
            return 16;
        }

        @Override
        public java.util.Optional<String> apiKey() {
            return java.util.Optional.of("k");
        }

        @Override
        public String keySource() {
            return "config";
        }

        @Override
        public Validation validation() {
            throw new UnsupportedOperationException("streamChat 不应用到 validation");
        }

        @Override
        public Prompt prompt() {
            throw new UnsupportedOperationException("streamChat 不应用到 prompt");
        }

        @Override
        public Cache cache() {
            throw new UnsupportedOperationException("streamChat 不应用到 cache");
        }
    }
}
