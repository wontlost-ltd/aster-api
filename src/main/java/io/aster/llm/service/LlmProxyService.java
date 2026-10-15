package io.aster.llm.service;

import io.aster.llm.api.dto.AssistantRequest;
import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.GeneratePolicyEvent;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.ModelsResponse;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmModelPolicy;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.LlmUsage;
import io.aster.llm.model.ValidationResult;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.prompt.PromptContext;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 代理服务
 *
 * 核心编排层：Prompt 组装 → LLM 调用 → SSE 流式 → 编译校验闭环。
 * 校验失败时自动重试（最多 maxAttempts 次），使用修复 Prompt 引导 LLM 修正。
 */
@ApplicationScoped
public class LlmProxyService {

    private static final Logger LOG = Logger.getLogger(LlmProxyService.class);
    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    @Inject
    PromptComposer promptComposer;

    @Inject
    LlmClient llmClient;

    @Inject
    PolicyCompileValidator validator;

    @Inject
    LlmConfig config;

    @Inject
    LlmRuntimeOptionsResolver optionsResolver;

    @Inject
    io.aster.llm.usage.AiUsageReporter usageReporter;

    /**
     * 流式策略生成（含编译校验闭环）
     *
     * 流程：
     * 1. 组装 Prompt → 调用 LLM → 流式输出 delta 事件
     * 2. 流结束后执行编译校验
     * 3. 校验通过 → 输出 final 事件
     * 4. 校验失败 → 输出 validation_error → 构建修复 Prompt → 重新生成
     * 5. 最多重试 maxAttempts 次
     */
    public Multi<String> streamGenerate(String tenantId, GeneratePolicyRequest req, ByokOverride byok,
                                        String requestId) {
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            // BYOK provider 非法：不回退平台 key，直接报错（红队铁律）
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("BYOK 凭证无效: " + e.getMessage())));
        }
        if (options.apiKey() == null || options.apiKey().isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        return Multi.createFrom().emitter(emitter -> {
            PromptContext ctx = promptComposer.buildGenerateContext(tenantId, req);
            String model = applyModel(ctx, options);
            int maxAttempts = config.validation().maxAttempts();
            AtomicInteger attempt = new AtomicInteger(1);
            AtomicReference<PromptContext> currentCtx = new AtomicReference<>(ctx);
            // issue #185：跨 repair 重试累加 token（同一 requestId 回填一笔）。
            AtomicReference<LlmUsage> usageAcc = new AtomicReference<>(LlmUsage.ZERO);
            UsageReportCtx rc = new UsageReportCtx(tenantId, "generate", model, options.usedByok(), requestId, usageAcc);

            emitter.emit(toJson(GeneratePolicyEvent.meta(model)));
            executeGenerateAttempt(currentCtx.get(), options, req.getLocaleOrDefault(),
                attempt, maxAttempts, currentCtx, emitter, rc);
        });
    }

    /** #185：一次流式请求（含多 attempt）的 token 回填上下文。 */
    private record UsageReportCtx(String tenantId, String callKind, String model, boolean usedByok,
                                  String requestId, AtomicReference<LlmUsage> usageAcc) {}

    private void executeGenerateAttempt(
        PromptContext ctx,
        LlmRuntimeOptions options,
        String locale,
        AtomicInteger attempt,
        int maxAttempts,
        AtomicReference<PromptContext> currentCtx,
        io.smallrye.mutiny.subscription.MultiEmitter<? super String> emitter,
        UsageReportCtx rc
    ) {
        StringBuilder fullSource = new StringBuilder();
        int currentAttempt = attempt.get();
        // #185：本 attempt 的 token。attempt 内用 max（Anthropic output_tokens 是累计绝对值，
        // 取 latest；OpenAI 单 final 帧 max 等价）；attempt 结束再 plus 到 rc.usageAcc()（跨 attempt 累加）。
        AtomicReference<LlmUsage> attemptUsage = new AtomicReference<>(LlmUsage.ZERO);

        LOG.infof("LLM 生成尝试 %d/%d", currentAttempt, maxAttempts);

        llmClient.streamChat(ctx.toLlmRequest(), options)
            .subscribe().with(
                event -> {
                    if (event.type() == LlmStreamEvent.Type.USAGE && event.usage() != null) {
                        // attempt 内取 max（不转发前端）。
                        attemptUsage.updateAndGet(u -> u.max(event.usage()));
                    } else if (event.type() == LlmStreamEvent.Type.REACHED) {
                        // 每个 attempt 各发一次；cloud 只按首个记一笔（cloud#470）。
                        emitter.emit(toJson(GeneratePolicyEvent.upstreamReached()));
                    } else if (event.type() == LlmStreamEvent.Type.DELTA && event.delta() != null) {
                        fullSource.append(event.delta());
                        emitter.emit(toJson(GeneratePolicyEvent.delta(event.delta())));
                    } else if (event.type() == LlmStreamEvent.Type.ERROR) {
                        emitter.emit(toJson(GeneratePolicyEvent.error(event.error())));
                        emitter.complete();
                    }
                },
                throwable -> {
                    LOG.errorf(throwable, "LLM 流式调用异常");
                    emitter.emit(toJson(GeneratePolicyEvent.error("LLM 调用失败: " + throwable.getMessage())));
                    emitter.complete();
                },
                () -> {
                    // #185：本 attempt 的流已结束 → 把 attempt 内 max 的 token 并入跨 attempt 累计。
                    // 放在这里覆盖所有分支（成功/repair/最终失败），每个 attempt 的 token 都算一次。
                    rc.usageAcc().updateAndGet(g -> g.plus(attemptUsage.get()));

                    // 流结束，执行编译校验
                    String source = fullSource.toString().trim();
                    if (source.isEmpty()) {
                        // 内容为空也回填（可能已消耗 prompt token）
                        reportStreamUsage(rc);
                        emitter.emit(toJson(GeneratePolicyEvent.error("LLM 未生成内容")));
                        emitter.complete();
                        return;
                    }

                    ValidationResult result = validator.validate(source, locale);

                    if (result.ok()) {
                        // 校验通过，输出清理后的最终代码
                        String cleanedSource = validator.cleanLlmOutput(source);
                        emitter.emit(toJson(GeneratePolicyEvent.finalResult(cleanedSource, true)));
                        reportStreamUsage(rc); // #185：流程终态，回填累计 token
                        emitter.complete();
                    } else if (currentAttempt < maxAttempts) {
                        // 校验失败，尝试修复
                        LOG.warnf("编译校验失败 (尝试 %d/%d): %s",
                            currentAttempt, maxAttempts, result.errorsAsString());

                        emitter.emit(toJson(GeneratePolicyEvent.validationError(result.errorsAsString())));

                        // 构建修复 Prompt
                        PromptContext repairCtx = promptComposer.buildRepairContext(
                            currentCtx.get(), source, result, locale);
                        currentCtx.set(repairCtx);
                        int nextAttempt = attempt.incrementAndGet();

                        // 通知前端修复开始
                        emitter.emit(toJson(GeneratePolicyEvent.repairStart(nextAttempt, maxAttempts)));

                        // 递归重试（rc 累加 token 跨 attempt）
                        executeGenerateAttempt(repairCtx, options, locale,
                            attempt, maxAttempts, currentCtx, emitter, rc);
                    } else {
                        // 超出重试次数
                        LOG.errorf("编译校验最终失败 (%d 次尝试): %s",
                            maxAttempts, result.errorsAsString());
                        emitter.emit(toJson(GeneratePolicyEvent.validationError(result.errorsAsString())));
                        // 仍然输出最后一次的代码，让用户手动修复
                        String cleanedSource = validator.cleanLlmOutput(source);
                        emitter.emit(toJson(GeneratePolicyEvent.finalResult(cleanedSource, false)));
                        reportStreamUsage(rc); // #185：流程终态（含所有 repair attempt 累计）
                        emitter.complete();
                    }
                }
            );
    }

    /**
     * 流式策略优化建议（直接透传 LLM 输出，无需编译校验）
     */
    public Multi<String> streamSuggest(String tenantId, SuggestRequest req, ByokOverride byok,
                                       String requestId) {
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("BYOK 凭证无效: " + e.getMessage())));
        }
        if (options.apiKey() == null || options.apiKey().isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        PromptContext ctx = promptComposer.buildSuggestContext(tenantId, req);
        String model = applyModel(ctx, options);
        boolean usedByok = options.usedByok();
        // issue #185：累加 USAGE 事件，流结束回填。
        AtomicReference<LlmUsage> usageAcc = new AtomicReference<>(LlmUsage.ZERO);

        Multi<String> body = llmClient.streamChat(ctx.toLlmRequest(), options)
            .onItem().invoke(event -> {
                if (event.type() == LlmStreamEvent.Type.USAGE && event.usage() != null) {
                    // suggest 单 attempt：attempt 内 max（Anthropic 累计 output 取 latest）。
                    usageAcc.updateAndGet(u -> u.max(event.usage()));
                }
            })
            .onCompletion().invoke(() ->
                usageReporter.reportUsage(tenantId, "suggest", model, usageAcc.get(), usedByok, requestId))
            // 与 generate 对齐：发 JSON delta（{"type":"delta","data":"..."}）而非裸
            // 文本。裸文本经 SSE TEXT_PLAIN 逐帧传输时，token 前导空格与内部换行
            // 会被 data: 行首空格规则 / 多行拆分吞掉 → 前端拼出的代码丢空格
            // （"Return resource" → "Returnresource"）。JSON 把内容包成字符串值，
            // 前端 JSON.parse 后原样还原，空格/换行全保留。
            .filter(LlmProxyService::isForwardedToCloud)
            .map(LlmProxyService::toCloudFrame);
        return withMetaFrame(model, body);
    }

    /**
     * 站内助手 RAG 问答（流式）。
     *
     * <p>与 {@link #streamSuggest} 同结构：BYOK 解析 → 无 key 即拒 → 组 prompt →
     * 流式转发 → USAGE 累加回填。计量口径记为 "assistant"，便于和
     * generate/suggest 分开看用量与成本。
     */
    public Multi<String> streamAssistant(String tenantId, AssistantRequest req, ByokOverride byok,
                                         String requestId) {
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("BYOK 凭证无效: " + e.getMessage())));
        }
        if (options.apiKey() == null || options.apiKey().isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        PromptContext ctx = promptComposer.buildAssistantContext(tenantId, req);
        String model = applyModel(ctx, options);
        boolean usedByok = options.usedByok();
        AtomicReference<LlmUsage> usageAcc = new AtomicReference<>(LlmUsage.ZERO);

        Multi<String> body = llmClient.streamChat(ctx.toLlmRequest(), options)
            .onItem().invoke(event -> {
                if (event.type() == LlmStreamEvent.Type.USAGE && event.usage() != null) {
                    usageAcc.updateAndGet(u -> u.max(event.usage()));
                }
            })
            .onCompletion().invoke(() ->
                usageReporter.reportUsage(tenantId, "assistant", model, usageAcc.get(), usedByok, requestId))
            // 同 suggest：发 JSON delta 而非裸文本，避免 SSE 逐帧传输吞掉空格/换行。
            .filter(LlmProxyService::isForwardedToCloud)
            .map(LlmProxyService::toCloudFrame);
        return withMetaFrame(model, body);
    }

    /** 经 BYOK 凭证查询 provider 模型列表；仅服务 BYOK（无 envelope → 400），上游失败 → 502。 */
    public Uni<ModelsResponse> listModels(String tenantId, ByokOverride byok) {
        if (byok == null || !byok.isValid()) {
            throw jsonError(400, "invalid_byok", "模型列表查询需要 BYOK 凭证");
        }
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            throw jsonError(400, "invalid_byok", e.getMessage());
        }
        String defaultModel = LlmModelPolicy.wireModel(null, options, config);
        String fetchedAt = java.time.Instant.now().toString();
        Uni<List<String>> upstream;
        try {
            upstream = llmClient.listModels(options);
        } catch (IllegalArgumentException e) {
            // 自定义端点校验（SSRF 等）同步抛出，与 resolver 拒绝同语义 → 400
            throw jsonError(400, "invalid_byok", e.getMessage());
        }
        return upstream
            .map(ids -> new ModelsResponse(options.provider(), boundModelIds(ids), defaultModel, fetchedAt))
            .onFailure(LlmCallFailedException.class).recoverWithUni(t -> {
                int status = t.statusCode();
                if (status == 404 || status == 405) {
                    return Uni.createFrom().item(new ModelsResponse(options.provider(), null, defaultModel, fetchedAt));
                }
                LOG.warnf("模型列表查询失败: tenant=%s provider=%s status=%d", tenantId, options.provider(), status);
                return Uni.createFrom().failure(jsonError(502, "models_unavailable", "无法从 provider 读取模型列表，请稍后重试"));
            });
    }

    /** 与 ByokEnvelopeParser.normalizeModel 同一形状；去重、排序、≤200 条 → jsonb 上界 ≤ 200×128 字符。 */
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    private static List<String> boundModelIds(List<String> ids) {
        return ids.stream().filter(id -> MODEL_ID.matcher(id).matches()).distinct().sorted().limit(200).toList();
    }

    private static jakarta.ws.rs.WebApplicationException jsonError(int status, String code, String message) {
        return new jakarta.ws.rs.WebApplicationException(
            jakarta.ws.rs.core.Response.status(status)
                .entity(java.util.Map.of("error", code, "message", message))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build());
    }

    /**
     * 代码补全（非流式，低延迟）
     */
    public Uni<CompleteResponse> complete(String tenantId, CompleteRequest req, ByokOverride byok,
                                          String requestId) {
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            // BYOK provider 非法：显式 400，不回退平台 key、不返回假成功空补全（与 generate/suggest
            // 的 no-fallback 语义一致，避免 cloud 记假 success）。
            throw new jakarta.ws.rs.WebApplicationException(
                jakarta.ws.rs.core.Response.status(400)
                    .entity(java.util.Map.of("error", "invalid_byok", "message", e.getMessage()))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build());
        }
        if (options.apiKey() == null || options.apiKey().isBlank()) {
            // 未配置 key：根本不调 provider，不计费（cloud#518）。
            return Uni.createFrom().item(new CompleteResponse("", config.model(), false));
        }

        PromptContext ctx = promptComposer.buildCompleteContext(tenantId, req);
        String model = applyModel(ctx, options);
        boolean usedByok = options.usedByok();

        return llmClient.chat(ctx.toLlmRequest(), options)
            .map(result -> {
                // issue #185：把 provider 真实 token 回填给 cloud（关联 requestId）。
                usageReporter.reportUsage(tenantId, "complete", model, result.usage(), usedByok, requestId);
                return new CompleteResponse(result.content(), model, true);
            })
            .onFailure(t -> isRejectedModel(options, t)).transform(t ->
                new jakarta.ws.rs.WebApplicationException(
                    jakarta.ws.rs.core.Response.status(400)
                        .entity(java.util.Map.of("error", "provider_rejected_model",
                            "message", "provider 拒绝了所选模型: " + model))
                        .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                        .build()))
            .onFailure(t -> !(t instanceof jakarta.ws.rs.WebApplicationException)).recoverWithItem(throwable -> {
                LOG.errorf(throwable, "代码补全失败");
                return new CompleteResponse("", model, reachedProvider(throwable));
            });
    }

    /** 把 BYOK 指定的 model 下推到提示上下文，并返回本次实际发出的 model（与 client 拼 body 同一算法）。 */
    private String applyModel(PromptContext ctx, LlmRuntimeOptions options) {
        String model = LlmModelPolicy.wireModel(ctx.toLlmRequest().model(), options, config);
        ctx.model(model);
        return model;
    }

    /** BYOK 指定了 model 且 provider 以 400 拒绝 → 不回退默认 model、不伪装成功。 */
    private static boolean isRejectedModel(LlmRuntimeOptions options, Throwable t) {
        return options.hasModel() && t instanceof LlmCallFailedException e && e.statusCode() == 400;
    }

    /** 流首帧固定为 meta，告知 cloud 本次实际发出的 model。 */
    private static Multi<String> withMetaFrame(String model, Multi<String> body) {
        return Multi.createBy().concatenating().streams(
            Multi.createFrom().item(toJson(GeneratePolicyEvent.meta(model))), body);
    }

    /**
     * suggest / assistant 转发给 cloud 的事件：内容增量、「已到达 provider」标记与错误。
     *
     * <ul>
     *   <li>upstream_reached 让 cloud 对已连上 provider 的调用计费，即便随后没有内容（cloud#470）；</li>
     *   <li>错误必须转发：此前被过滤掉，provider 拒绝时用户只看到空白结果、无从得知失败
     *       （aster-api#360）。错误文案由 VertxLlmClient 统一泛化，不含 provider 原始错误体。</li>
     * </ul>
     */
    static boolean isForwardedToCloud(LlmStreamEvent event) {
        return switch (event.type()) {
            case REACHED, ERROR -> true;
            case DELTA -> event.delta() != null;
            case DONE, USAGE -> false;
        };
    }

    /** 把 {@link #isForwardedToCloud} 放行的事件编码为 SSE data 帧。 */
    static String toCloudFrame(LlmStreamEvent event) {
        return switch (event.type()) {
            case REACHED -> toJson(GeneratePolicyEvent.upstreamReached());
            case ERROR -> toJson(GeneratePolicyEvent.error(event.error()));
            default -> toJson(GeneratePolicyEvent.delta(event.delta()));
        };
    }

    /**
     * 补全失败时请求是否已到达 provider（cloud#518）。只有 client 明确标注的失败才可能算到达；
     * 其它异常发生在发出请求之前，按没到达处理、不计费。
     */
    static boolean reachedProvider(Throwable failure) {
        return failure instanceof io.aster.llm.client.LlmCallFailedException e && e.reachedProvider();
    }

    /** #185：把一次流式请求累计的 token 回填给 cloud（generate/suggest 终态调用）。 */
    private void reportStreamUsage(UsageReportCtx rc) {
        usageReporter.reportUsage(rc.tenantId(), rc.callKind(), rc.model(),
            rc.usageAcc().get(), rc.usedByok(), rc.requestId());
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"序列化失败\"}";
        }
    }
}
