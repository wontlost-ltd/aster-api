package io.aster.llm.api;

import io.aster.llm.api.dto.AssistantRequest;
import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.safety.PromptBlockCounter;
import io.aster.llm.safety.PromptGuardConfig;
import io.aster.llm.safety.PromptSafetyGuard;
import io.aster.llm.safety.PromptScopeFilter;
import io.aster.llm.safety.SafetyVerdict;
import io.aster.llm.service.LlmProxyService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.Map;

/**
 * AI 助手 REST 端点
 *
 * 提供 NL-to-CNL 策略生成、策略解释、代码补全等 AI 能力。
 * 生成和解释使用 SSE 流式传输，补全使用同步请求。
 */
@Path("/api/v1/ai")
public class AiAssistantResource {

    private static final Logger LOG = Logger.getLogger(AiAssistantResource.class);

    @Inject
    LlmProxyService llmProxyService;

    @Inject
    LlmConfig llmConfig;

    @Inject
    PromptSafetyGuard safetyGuard;

    @Inject
    PromptGuardConfig guardConfig;

    @Inject
    PromptBlockCounter blockCounter;

    @Inject
    io.aster.llm.safety.SafetyEventReporter safetyReporter;

    @Inject
    ByokEnvelopeParser byokParser;

    @Context
    RoutingContext routingContext;

    /**
     * issue #174：身份解析收敛到共享 resolver。此前本类自写 tenantId()，
     * **缺 R32 hotfix**（不读 ApiKeyAuthFilter 写入的 ctx property），带有效
     * API key 但不带 X-Tenant-Id 的请求会误落到 "default" 租户。
     */
    @jakarta.inject.Inject
    io.aster.policy.rest.RequestIdentityResolver identityResolver;

    @Context
    jakarta.ws.rs.container.ContainerRequestContext requestContext;

    /**
     * NL-to-CNL 策略生成（SSE 流式）
     *
     * POST /api/v1/ai/generate
     *
     * 事件类型：
     * - delta: 增量内容
     * - validation_error: 编译校验失败
     * - final: 最终代码（已通过校验或达到最大重试次数）
     * - error: 服务端错误
     */
    @POST
    @Path("/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> generate(@Valid GeneratePolicyRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        SafetyVerdict v = guard(request.goal(), PromptScopeFilter.Strictness.MEDIUM, tenantId, "generate");
        if (v.blocked()) {
            return refusalStream(v);
        }
        LOG.infof("AI 生成请求: tenant=%s, locale=%s, goal=%.50s...",
            tenantId, request.getLocaleOrDefault(), request.goal());
        return llmProxyService.streamGenerate(tenantId, request, parseByok(),
            byokParser.parseRequestId(requestContext));
    }

    /**
     * 策略优化建议（SSE 流式）
     *
     * POST /api/v1/ai/suggest
     */
    @POST
    @Path("/suggest")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> suggest(@Valid SuggestRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        SafetyVerdict v = guard(request.source(), PromptScopeFilter.Strictness.MEDIUM, tenantId, "suggest");
        if (v.blocked()) {
            return refusalStream(v);
        }
        LOG.infof("AI 优化建议请求: tenant=%s, locale=%s", tenantId, request.getLocaleOrDefault());
        return llmProxyService.streamSuggest(tenantId, request, parseByok(),
            byokParser.parseRequestId(requestContext));
    }

    /**
     * 站内助手 RAG 问答（流式）
     *
     * POST /api/v1/ai/assistant
     *
     * <p>浏览器端已做站内检索，把命中条目作为 groundingHits 送来；模型只依据
     * 这些条目作答。护栏顺序与 suggest 一致：开关 → 租户 → 内容安全 → 转发。
     *
     * <p>安全过滤用 {@code query} 而非整个请求体：groundingHits 是**站内**内容
     * （文档标题/摘要），不是用户输入，拿它跑注入检测只会产生误报。
     */
    @POST
    @Path("/assistant")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> assistant(@Valid AssistantRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        SafetyVerdict v = guard(request.query(), PromptScopeFilter.Strictness.MEDIUM, tenantId, "assistant");
        if (v.blocked()) {
            return refusalStream(v);
        }
        LOG.infof("站内助手问答: tenant=%s, locale=%s, hits=%d",
            tenantId, request.getLocaleOrDefault(), request.hitsOrEmpty().size());
        return llmProxyService.streamAssistant(tenantId, request, parseByok(),
            byokParser.parseRequestId(requestContext));
    }

    /**
     * 代码补全（非流式，低延迟）
     *
     * POST /api/v1/ai/complete
     */
    @POST
    @Path("/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<CompleteResponse> complete(@Valid CompleteRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        SafetyVerdict v = guard(request.prefix(), PromptScopeFilter.Strictness.LENIENT, tenantId, "complete");
        if (v.blocked()) {
            // Complete 是同步 JSON 路径；用 WebApplicationException 把 verdict 直接抛回
            throw new WebApplicationException(
                Response.status(400)
                    .entity(Map.of(
                        "error", "out_of_scope",
                        "message", v.message(),
                        "rule_id", v.ruleId()
                    ))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }
        return llmProxyService.complete(tenantId, request, parseByok(),
            byokParser.parseRequestId(requestContext));
    }

    private void checkEnabled() {
        if (!llmConfig.enabled()) {
            throw new WebApplicationException("AI 功能未启用", 503);
        }
    }

    private String tenantId() {
        return identityResolver.tenantId();
    }

    /**
     * 解析本次请求的 BYOK 覆盖；present-invalid（已验签 body 带 _byok 但字段无效）→ 400，
     * 与 service/resolver 的 no-fallback 语义一致（不静默回退平台）。
     */
    private io.aster.llm.model.ByokOverride parseByok() {
        try {
            return byokParser.parse(requestContext);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                Response.status(400)
                    .entity(Map.of("error", "invalid_byok", "message", e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /**
     * 跑安全守门 + 配额扣除策略。
     *
     * 配额扣除规则（见 07-ai-billing.md "提示词治理"）：
     *   - UI 路径（X-Call-Source 不为 "api"）：前 N 次拦截不扣（容错），第 N+1 次起扣
     *   - API 路径：每次拦截都扣（机器生成的 prompt 应自保质量）
     *
     * 实际扣配额由 LlmProxyService 完成；此处只决定"是否要让下游扣"
     * → 命中且应该扣 → 把 X-Aster-Block-Charge: 1 写到 routingContext，下游识别
     */
    private SafetyVerdict guard(String userText, PromptScopeFilter.Strictness strictness,
                                 String tenantId, String callKind) {
        if (!guardConfig.enabled()) {
            return SafetyVerdict.allow();
        }
        SafetyVerdict v = safetyGuard.guard(userText, strictness);
        if (!v.blocked()) {
            return v;
        }
        // 命中：决定是否扣配额
        boolean isApi = isApiCall();
        boolean charge = isApi || blockCounter.incrementAndGet(tenantId) > guardConfig.uiFreeBlocks();
        LOG.infof("Prompt 守门拦截: tenant=%s, rule=%s, kind=%s, isApi=%s, charge=%s",
            tenantId, v.ruleId(), callKind, isApi, charge);

        if (routingContext != null && routingContext.response() != null) {
            // 下游 LlmProxyService 看到 charge=1 就照常 record；charge=0 跳过 record
            routingContext.response().putHeader("X-Aster-Block-Charge", charge ? "1" : "0");
            routingContext.response().putHeader("X-Aster-Block-Rule", v.ruleId());
        }

        // PG-7: fire-and-forget 上报到 cloud /api/internal/ai/usage
        // 让 anomaly Signal 4（24h jailbreak ≥3 → 24h ban）能感知本次拦截
        safetyReporter.reportBlocked(tenantId, callKind, v.ruleId());

        return v;
    }

    private boolean isApiCall() {
        if (routingContext == null || routingContext.request() == null) {
            return false;
        }
        String src = routingContext.request().getHeader("X-Call-Source");
        return src != null && "api".equalsIgnoreCase(src.trim());
    }

    /**
     * 把 SafetyVerdict 序列化为单条 SSE error 事件 + 立即结束流
     */
    private Multi<String> refusalStream(SafetyVerdict v) {
        String json = String.format(
            "{\"error\":\"out_of_scope\",\"message\":\"%s\",\"rule_id\":\"%s\"}",
            escapeJson(v.message()),
            escapeJson(v.ruleId())
        );
        return Multi.createFrom().item("event: error\ndata: " + json + "\n\n");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
