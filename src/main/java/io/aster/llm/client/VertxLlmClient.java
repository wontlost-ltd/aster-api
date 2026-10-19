package io.aster.llm.client;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.metrics.LlmMetrics;
import io.aster.llm.model.LlmChatResult;
import io.aster.llm.model.LlmModelPolicy;
import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.LlmUsage;
import io.aster.security.net.SsrfGuard;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * Vert.x WebClient 实现的 LLM 客户端
 *
 * 使用 Vert.x WebClient 而非 LangChain4j，确保 GraalVM Native 兼容。
 * 流式模式下手动解析 SSE text/event-stream 响应体。
 */
@ApplicationScoped
public class VertxLlmClient implements LlmClient {

    private static final Logger LOG = Logger.getLogger(VertxLlmClient.class);

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    @Inject
    LlmConfig config;

    @Inject
    SseEventParser sseParser;

    @Inject
    LlmMetrics llmMetrics;

    @Inject
    SsrfGuard ssrfGuard;

    // P0-R19: WebClient DCL consolidated into SharedWebClient.
    // mutinyVertx still injected for createHttpClient (non-WebClient path).
    private WebClient getClient() {
        return sharedWebClient.client();
    }

    @Override
    public Multi<LlmStreamEvent> streamChat(LlmRequest request, LlmRuntimeOptions options) {
        String apiKey = options.apiKey();
        String provider = options.provider();
        return Multi.createFrom().emitter(emitter -> {
            try {
                validateCustomEndpoint(options);
                URI baseUri = URI.create(options.baseUrl());
                String chatPath = resolveChatPath(baseUri, provider);
                JsonObject body = buildRequestBody(request, options);
                int port = resolvePort(baseUri);
                boolean ssl = "https".equals(baseUri.getScheme());

                // 记实际发出的模型（Anthropic 会被兜底替换），而非请求里的原值——
                // 否则日志与 provider 收到的不一致，排障时会被误导（aster-api#360）。
                LOG.infof("LLM 流式请求: provider=%s, model=%s, url=%s:%d%s, source=%s",
                    provider, body.getString("model"), baseUri.getHost(), port, chatPath, options.source());

                // 使用底层 HttpClient 获取 response stream，避免 WebClient.sendJsonObject 缓冲整个响应体
                //
                // ★超时分两种，不能混用（issue #302 H0）：
                //   - connectTimeout 用 timeout()（30s）：建连阶段的上限。
                //     此前这里错填成 readTimeout()（120s），既让建连迟迟不失败，
                //     又**根本没有配读超时**——provider 建连成功后挂住不发数据，
                //     连接与请求就永久悬着，只能靠客户端断开或进程重启释放。
                //   - readIdleTimeout 用 readTimeout()（120s）：**空闲**超时，
                //     每收到一个 chunk 就重置。SSE 必须用空闲而非总时长，
                //     否则一次正常的长回答会被拦腰砍断。
                io.vertx.core.http.HttpClientOptions clientOptions = new io.vertx.core.http.HttpClientOptions()
                    .setSsl(ssl)
                    .setDefaultHost(baseUri.getHost())
                    .setDefaultPort(port)
                    .setConnectTimeout((int) config.timeout().toMillis())
                    // ★单位显式设为毫秒：setReadIdleTimeout 默认按**秒**取整，
                    //   若配成 500ms 会截断成 0，而 0 在 Vert.x 里表示「禁用超时」——
                    //   一个本意收紧超时的配置反而把保护整个关掉。
                    .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setReadIdleTimeout((int) config.readTimeout().toMillis());

                io.vertx.core.http.HttpClient httpClient = mutinyVertx.getDelegate().createHttpClient(clientOptions);
                io.vertx.core.http.RequestOptions reqOptions = new io.vertx.core.http.RequestOptions()
                    .setMethod(HttpMethod.POST)
                    .setURI(chatPath)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Accept", "text/event-stream");

                // 注入认证头
                switch (provider) {
                    case "anthropic" -> {
                        reqOptions.putHeader("x-api-key", apiKey);
                        reqOptions.putHeader("anthropic-version", "2023-06-01");
                    }
                    default -> reqOptions.putHeader("Authorization", "Bearer " + apiKey);
                }

                // 用于跨 chunk 拼接不完整的字节（防止多字节 UTF-8 字符被截断）
                // 使用数组包装以支持 lambda 内重新赋值
                io.vertx.core.buffer.Buffer[] byteBufferHolder = { io.vertx.core.buffer.Buffer.buffer() };

                // 下游取消时关闭 HTTP 连接，释放资源并停止 token 消耗
                emitter.onTermination(() -> {
                    httpClient.close();
                });

                httpClient.request(reqOptions, reqAr -> {
                    if (reqAr.failed()) {
                        // 本地异常 message 记日志，不回显前端（红队硬化：可能含目标 host/URL 细节）
                        LOG.errorf(reqAr.cause(), "LLM 连接失败: provider=%s", provider);
                        emitter.emit(LlmStreamEvent.error("LLM 连接失败"));
                        emitter.complete();
                        httpClient.close();
                        return;
                    }

                    io.vertx.core.http.HttpClientRequest clientRequest = reqAr.result();
                    clientRequest.response(respAr -> {
                        if (respAr.failed()) {
                            LOG.errorf(respAr.cause(), "LLM 响应读取失败: provider=%s", provider);
                            emitter.emit(LlmStreamEvent.error("LLM 响应读取失败"));
                            emitter.complete();
                            httpClient.close();
                            return;
                        }

                        io.vertx.core.http.HttpClientResponse clientResponse = respAr.result();
                        int statusCode = clientResponse.statusCode();

                        if (statusCode != 200) {
                            // 非 200：统一交给 ProviderErrorClassifier 分类（Anthropic/OpenAI 错误体）。
                            // 只透出脱敏、截断后的 error.message 与 error.type，原始错误体不落日志、不回显。
                            // SSRF 硬化：底层 HttpClient.request() 默认【不】跟随重定向，且此处把 3xx
                            // 一并当错误终止——自定义端点若返回 302 到内网也不会被跟随（与非流式路径
                            // WebClient.followRedirects(false) 语义一致）。
                            clientResponse.bodyHandler(errBuf -> {
                                ProviderErrorClassifier.Classified c = classifyAndLog(
                                    statusCode, errBuf.toString(), options, provider);
                                emitter.emit(LlmStreamEvent.error(c.code(), c.providerMessage(), c.providerErrorType()));
                                emitter.complete();
                                httpClient.close();
                            });
                            return;
                        }

                        // 逐 chunk 解析 SSE，使用 Buffer 拼接避免多字节 UTF-8 字符被截断
                        clientResponse.handler(chunk -> {
                            byteBufferHolder[0].appendBuffer(chunk);
                            // 安全地转换为字符串并逐行解析
                            String text = byteBufferHolder[0].toString();
                            int lastNewline = text.lastIndexOf('\n');
                            if (lastNewline < 0) return; // 没有完整行，等待下一个 chunk

                            // 处理已完成的行，保留不完整的尾部
                            String completePart = text.substring(0, lastNewline + 1);
                            String remainder = text.substring(lastNewline + 1);
                            byteBufferHolder[0] = io.vertx.core.buffer.Buffer.buffer(remainder);

                            for (String line : completePart.split("\n", -1)) {
                                line = line.stripTrailing();
                                if (line.startsWith("data: ") || line.startsWith("data:")) {
                                    String data = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                                    LlmStreamEvent event = sseParser.parseLine(data, provider);
                                    if (event != null) {
                                        if (event.type() == LlmStreamEvent.Type.DONE) {
                                            emitter.complete();
                                            httpClient.close();
                                            return;
                                        }
                                        emitter.emit(event);
                                    }
                                }
                            }
                        });

                        clientResponse.endHandler(v -> {
                            // 处理尾部缓冲区中的最后一行（没有换行符结尾的情况）
                            if (byteBufferHolder[0].length() > 0) {
                                String tail = byteBufferHolder[0].toString().stripTrailing();
                                if (tail.startsWith("data: ") || tail.startsWith("data:")) {
                                    String data = tail.startsWith("data: ") ? tail.substring(6) : tail.substring(5);
                                    LlmStreamEvent event = sseParser.parseLine(data, provider);
                                    if (event != null && event.type() != LlmStreamEvent.Type.DONE) {
                                        emitter.emit(event);
                                    }
                                }
                            }
                            emitter.complete();
                            httpClient.close();
                        });

                        clientResponse.exceptionHandler(ex -> {
                            LOG.errorf(ex, "LLM 流式响应异常: provider=%s", provider);
                            emitter.emit(LlmStreamEvent.error("流式响应异常"));
                            emitter.complete();
                            httpClient.close();
                        });
                    });

                    // 已连上 provider：从这里起请求算「到达第三方」，计入配额（cloud#470）。
                    // 放在发送之前而非写完回调里，保证它先于任何响应事件到达下游。
                    emitter.emit(LlmStreamEvent.reached());
                    clientRequest.end(io.vertx.core.buffer.Buffer.buffer(body.encode()));
                });

            } catch (Exception e) {
                LOG.errorf(e, "LLM 流式请求异常: provider=%s", provider);
                emitter.emit(LlmStreamEvent.error("请求异常"));
                emitter.complete();
            }
        });
    }

    @Override
    public Uni<List<String>> listModels(LlmRuntimeOptions options) {
        String apiKey = options.apiKey();
        String provider = options.provider();
        validateCustomEndpoint(options);
        URI baseUri = URI.create(options.baseUrl());
        String path = resolveModelsPath(baseUri);
        int port = resolvePort(baseUri);

        return Uni.createFrom().emitter(emitter -> {
            HttpRequest<Buffer> httpRequest = getClient()
                .request(HttpMethod.GET, port, baseUri.getHost(), path)
                .ssl("https".equals(baseUri.getScheme()))
                .followRedirects(false)
                .timeout(config.timeout().toMillis());
            addAuthHeaders(httpRequest, apiKey, provider);

            httpRequest.send(ar -> {
                if (ar.failed()) {
                    boolean reached = !LlmCallFailedException.isConnectFailure(ar.cause());
                    emitter.fail(new LlmCallFailedException(
                        reached ? "模型列表读取失败" : "LLM 连接失败", reached, ar.cause(), 0));
                    return;
                }
                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    // 模型列表请求不带 model，404 不会被当成「模型被拒」
                    ProviderErrorClassifier.Classified c = classifyAndLog(
                        response.statusCode(), bodyText(response), options, provider);
                    emitter.fail(new LlmCallFailedException(
                        "LLM API 错误 (" + response.statusCode() + ")", true, null, response.statusCode(), c));
                    return;
                }
                // 响应体上界：Vert.x WebClient 已把 body 完整缓冲到内存后才到这里，本检查只是「拒绝超限响应、不继续解析入库」，
                // 不是接收期间的内存硬上限（硬上限需 BodyCodec 流式读取，本 ADR 不做；出站只打 allowlist 内网关）。
                if (response.body() != null && response.body().length() > MAX_MODELS_BODY_BYTES) {
                    emitter.fail(new LlmCallFailedException("模型列表响应过大", true, null, 200));
                    return;
                }
                try {
                    emitter.complete(extractModelIds(response.bodyAsJsonObject()));
                } catch (Exception e) {
                    emitter.fail(new LlmCallFailedException("模型列表解析失败", true, e, 200));
                }
            });
        });
    }

    /**
     * 分类 provider 失败并记一行 WARN。日志只含状态、错误码、error.type 与脱敏后的 message，
     * 绝不记录原始错误体（BYOK 下可能含 key 前缀/鉴权诊断/请求片段）。
     */
    private static ProviderErrorClassifier.Classified classifyAndLog(
        int status, String body, LlmRuntimeOptions options, String provider) {
        ProviderErrorClassifier.Classified c = ProviderErrorClassifier.classify(status, body, options.hasModel());
        LOG.warnf("LLM API 错误: provider=%s status=%d code=%s providerErrorType=%s providerMessage=%s source=%s",
            provider, status, c.code(), c.providerErrorType(), c.providerMessage(), options.source());
        return c;
    }

    private static String bodyText(HttpResponse<Buffer> response) {
        Buffer b = response.body();
        return b == null ? null : b.toString();
    }

    private static final int MAX_MODELS_BODY_BYTES = 1 << 20;

    /** OpenAI 与 Anthropic 的 /v1/models 都是 {"data":[{"id":...}]}；缺 id 的项跳过（形状/条数限制在 service 层统一做）。 */
    private static List<String> extractModelIds(JsonObject body) {
        JsonArray data = body.getJsonArray("data", new JsonArray());
        List<String> ids = new java.util.ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            String id = data.getJsonObject(i).getString("id");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 与 resolveChatPath 同一规则：base 带路径则追加 /models，否则 /v1/models。 */
    private String resolveModelsPath(URI baseUri) {
        String basePath = baseUri.getPath();
        if (basePath == null) basePath = "";
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        return basePath.isEmpty() ? "/v1/models" : basePath + "/models";
    }

    @Override
    public Uni<LlmChatResult> chat(LlmRequest request, LlmRuntimeOptions options) {
        String apiKey = options.apiKey();
        String provider = options.provider();
        validateCustomEndpoint(options);
        URI baseUri = URI.create(options.baseUrl());
        String chatPath = resolveChatPath(baseUri, provider);
        int port = resolvePort(baseUri);

        LlmRequest nonStreamRequest = new LlmRequest(
            request.model(), request.messages(),
            request.temperature(), request.maxTokens(), false
        );
        JsonObject body = buildRequestBody(nonStreamRequest, options);

        return Uni.createFrom().emitter(emitter -> {
            HttpRequest<Buffer> httpRequest = getClient()
                .request(HttpMethod.POST, port, baseUri.getHost(), chatPath)
                .ssl("https".equals(baseUri.getScheme()))
                .followRedirects(false)
                .timeout(config.timeout().toMillis())
                .putHeader("Content-Type", "application/json");

            addAuthHeaders(httpRequest, apiKey, provider);

            httpRequest.sendJsonObject(body, ar -> {
                if (ar.failed()) {
                    boolean reached = !LlmCallFailedException.isConnectFailure(ar.cause());
                    emitter.fail(new LlmCallFailedException(
                        reached ? "LLM 响应读取失败" : "LLM 连接失败", reached, ar.cause()));
                    return;
                }

                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    ProviderErrorClassifier.Classified c = classifyAndLog(
                        response.statusCode(), bodyText(response), options, provider);
                    emitter.fail(new LlmCallFailedException(
                        "LLM API 错误 (" + response.statusCode() + ")", true, null, response.statusCode(), c));
                    return;
                }

                try {
                    JsonObject responseJson = response.bodyAsJsonObject();
                    String content = extractContent(responseJson, provider);
                    LlmUsage usage = extractUsage(responseJson, provider);
                    recordTokenUsage(usage, LlmModelPolicy.wireModel(request.model(), options, config));
                    emitter.complete(new LlmChatResult(content, usage));
                } catch (Exception e) {
                    emitter.fail(new LlmCallFailedException("LLM 响应解析失败", true, e));
                }
            });
        });
    }

    // package-private for unit testing（stream_options 条件 + role 映射是纯逻辑，值得锁定）
    JsonObject buildRequestBody(LlmRequest request, LlmRuntimeOptions options) {
        String provider = options.provider();
        if ("anthropic".equals(provider)) {
            return buildAnthropicBody(request, options);
        }
        boolean isNativeOpenAi = options.wireFormat() == LlmRuntimeOptions.WireFormat.OPENAI_NATIVE;
        JsonArray messages = new JsonArray();
        for (var msg : request.messages()) {
            String role = msg.role();
            if ("developer".equals(role) && !isNativeOpenAi) {
                // "developer" 角色仅 OpenAI 原生 API 支持，OpenAI 兼容代理需映射为 system
                role = "system";
            }
            messages.add(new JsonObject()
                .put("role", role)
                .put("content", msg.content()));
        }

        JsonObject body = new JsonObject()
            .put("model", LlmModelPolicy.wireModel(request.model(), options, config))
            .put("messages", messages)
            .put("temperature", request.temperature())
            .put("max_tokens", request.maxTokens())
            .put("stream", request.stream());
        // issue #185：OpenAI 兼容流式默认不返回 usage，须显式请求末帧带 usage。但部分兼容代理会
        // 严格校验未知字段直接 400，故只对【原生 OpenAI】开启；其他兼容代理（如 rightcode）不加，
        // 退化为无 usage 帧（SSE token 回填 0，cloud 占位 0/0 仍在，不影响配额/BYOK）。Codex 审查。
        if (request.stream() && isNativeOpenAi) {
            body.put("stream_options", new JsonObject().put("include_usage", true));
        }
        return body;
    }

    /**
     * 构造 Anthropic Messages API 请求体（Phase 2 BYOK）。
     *
     * <p>与 OpenAI Chat Completions 的关键差异：Anthropic 的 system prompt 是 <b>top-level</b>
     * {@code system} 字段，不能作为 {@code role=system} 塞进 {@code messages}（会被拒/忽略）。
     * 且 {@code messages} 只允许 user/assistant 交替角色。这里把所有 system/developer 消息合并到
     * top-level system，其余按 user/assistant 放入 messages。
     */
    // 既有单测以 1 参调用：等价于平台凭证（无 BYOK 指定 model）。
    JsonObject buildAnthropicBody(LlmRequest request) {
        return buildAnthropicBody(request, null);
    }

    // package-private for unit testing（Anthropic Messages API 结构是纯逻辑，值得锁定）
    JsonObject buildAnthropicBody(LlmRequest request, LlmRuntimeOptions options) {
        // 模型选择统一走 LlmModelPolicy：平台/未指定 → 非 claude 系兜底；BYOK 指定 → 原样。
        LlmRuntimeOptions effective = options != null ? options
            : new LlmRuntimeOptions(null, "anthropic", null, LlmRuntimeOptions.Source.PLATFORM);
        String model = LlmModelPolicy.wireModel(request.model(), effective, config);
        StringBuilder system = new StringBuilder();
        JsonArray messages = new JsonArray();
        for (var msg : request.messages()) {
            String role = msg.role();
            if ("system".equals(role) || "developer".equals(role)) {
                if (system.length() > 0) system.append("\n\n");
                system.append(msg.content());
            } else {
                // assistant 保留，其余（含未知角色）归一为 user
                String mapped = "assistant".equals(role) ? "assistant" : "user";
                messages.add(new JsonObject().put("role", mapped).put("content", msg.content()));
            }
        }

        JsonObject body = new JsonObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", request.maxTokens())
            .put("temperature", request.temperature())
            .put("stream", request.stream());
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        return body;
    }

    private void addAuthHeaders(HttpRequest<Buffer> request, String apiKey, String provider) {
        switch (provider) {
            case "anthropic" -> {
                request.putHeader("x-api-key", apiKey);
                request.putHeader("anthropic-version", "2023-06-01");
            }
            default -> request.putHeader("Authorization", "Bearer " + apiKey);
        }
    }

    private void validateCustomEndpoint(LlmRuntimeOptions options) {
        if (options.customEndpoint()) {
            // GA 只允许管理员 allowlist 自定义 endpoint；resolver 已校验一次。这里在真正出站前
            // 再做 URL 规范化 + DNS/IP deny，避免后续代码路径绕过 resolver。
            ssrfGuard.validate(options.baseUrl());
        }
    }

    /**
     * 解析完整的 API 路径
     *
     * 如果 base-url 包含路径前缀（如 https://right.codes/codex/v1），
     * 则只追加端点后缀（/chat/completions），避免重复 /v1。
     */
    private String resolveChatPath(URI baseUri, String provider) {
        String basePath = baseUri.getPath();
        if (basePath == null) basePath = "";
        // 去掉尾部斜杠
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);

        // 如果 base URL 已包含路径（如 /codex/v1），则只追加端点后缀
        if (!basePath.isEmpty()) {
            return switch (provider) {
                case "anthropic" -> basePath + "/messages";
                default -> basePath + "/chat/completions";
            };
        }

        // 标准 base URL（如 https://api.openai.com），追加完整路径
        return switch (provider) {
            case "anthropic" -> "/v1/messages";
            default -> "/v1/chat/completions";
        };
    }

    private int resolvePort(URI uri) {
        int port = uri.getPort();
        if (port > 0) return port;
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 从非流式响应根部解析 token 用量（issue #185）。OpenAI 兼容: usage.prompt_tokens/
     * completion_tokens；Anthropic Messages API: usage.input_tokens/output_tokens。
     * provider 未返回 usage 时返回 null（不精确回填）。
     */
    LlmUsage extractUsage(JsonObject body, String provider) {
        if (body == null) return null;
        JsonObject usage = body.getJsonObject("usage");
        if (usage == null) return null;
        int prompt;
        int completion;
        if ("anthropic".equals(provider)) {
            prompt = usage.getInteger("input_tokens", 0);
            completion = usage.getInteger("output_tokens", 0);
        } else {
            prompt = usage.getInteger("prompt_tokens", 0);
            completion = usage.getInteger("completion_tokens", 0);
        }
        return new LlmUsage(prompt, completion);
    }

    /** 把 token 用量写入 Prometheus Counter（本地指标；精确回填 cloud 见 LlmProxyService）。 */
    private void recordTokenUsage(LlmUsage usage, String model) {
        if (usage != null && usage.hasTokens()) {
            llmMetrics.recordTokens(model != null ? model : "unknown",
                usage.promptTokens(), usage.completionTokens());
        }
    }

    private String extractContent(JsonObject body, String provider) {
        return switch (provider) {
            case "anthropic" -> {
                JsonArray content = body.getJsonArray("content");
                if (content == null || content.isEmpty()) {
                    yield "";
                }
                yield content.getJsonObject(0).getString("text", "");
            }
            default -> {
                JsonArray choices = body.getJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    yield "";
                }
                JsonObject message = choices.getJsonObject(0).getJsonObject("message");
                yield message != null ? message.getString("content", "") : "";
            }
        };
    }
}
