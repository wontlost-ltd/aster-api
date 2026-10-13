package io.aster.llm.client;

import io.vertx.core.Vertx;

import java.time.Duration;

/** VertxLlmClient 测试共用：不经 CDI 手工组装 client。 */
final class LlmClientTestSupport {

    /** 测试用的 Anthropic 默认模型；断言兜底取的是配置值而非写死的常量。 */
    static final String ANTHROPIC_DEFAULT_MODEL = "claude-test-default";

    private LlmClientTestSupport() {
    }

    /** 手工组装 client：这些依赖都是包私有字段，测试里直接注入即可，无需 CDI。 */
    static VertxLlmClient newClient(Vertx v, Duration connect, Duration read) {
        VertxLlmClient c = new VertxLlmClient();
        c.mutinyVertx = io.vertx.mutiny.core.Vertx.newInstance(v);
        c.config = new StubConfig(connect, read);
        c.sseParser = new SseEventParser();
        return c;
    }

    /** 只实现被 streamChat 用到的几项；其余抛异常——用到了就该在测试里显式面对。 */
    record StubConfig(Duration connect, Duration read)
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
        public String anthropicDefaultModel() {
            return ANTHROPIC_DEFAULT_MODEL;
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
