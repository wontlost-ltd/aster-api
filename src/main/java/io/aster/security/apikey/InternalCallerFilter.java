package io.aster.security.apikey;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Internal caller filter for cloud-BFF-only endpoints.
 *
 * <p>Protects:
 * <ul>
 *   <li>{@code /api/v1/policies/evaluate-source} —— 接受请求体里的 CNL 源码，绕过审核流。
 *       仅 cloud BFF 可用于 dashboard 预览/playground，禁止外部客户调用</li>
 *   <li>R23-Critical-2: {@code /api/v1/ai/*} —— LLM 代理端点，会产生真金白银的 token 成本。
 *       浏览器无法持有 HMAC 密钥，必须经过 aster-cloud server-side proxy 转签。
 *       否则任意来源都可烧 LLM 预算 + 通过 X-Tenant-Id 假冒任意租户</li>
 * </ul>
 *
 * <p>守门：要求 X-Internal-Caller=cloud-bff + HMAC 签名（复用 PlanGate HMAC key）。
 *
 * <p>优先级 = AUTHENTICATION + 50（在 RequestSignatureFilter 之后、ApiKeyAuthFilter 之前）。
 */
@ApplicationScoped
public class InternalCallerFilter {

    private static final Logger LOG = Logger.getLogger(InternalCallerFilter.class);

    /**
     * 审计 #98（Medium 1）：HMAC 校验通过后在请求上打的<b>可信凭证</b>属性。
     *
     * <p>下游（{@code PolicyEvaluationResource}）用它派生 {@code aliasesTrusted}
     * （允许注册结构词别名），<b>而不是</b>看 {@code X-Internal-Caller} 头是否<b>存在</b>。
     * 原因：{@code evaluate-source.public} 逃生舱在 HMAC 分支之前就 BYPASS_OK 放行，
     * 一个带三条 {@code X-Internal-*} 头 + 垃圾签名的调用方会被 {@code hasInternalCallerCredentials}
     * （纯粹看头存在）误判为可信，从而拿到 {@code allowStructural=true} 注入 RETURN/IF/MATCH
     * 等结构词别名，击穿 ADR-0022 门控。此属性仅在 {@code constantTimeEquals} 真正通过后设置，
     * public / trial 旁路路径<b>不会</b>设置它——从而把「可信」严格绑定到「签名已验证」。
     */
    public static final String HMAC_VERIFIED_PROP = "aster.internal.hmac-verified";

    /**
     * 已 HMAC 验签的原始请求 body 字节（Phase 2 BYOK）。仅在签名<b>真正</b>通过后盖章，
     * 供下游（如 AiAssistantResource）从<b>已验证</b>的 body 顶层解析 {@code _byok} envelope，
     * 而不必重新读流、也不把 BYOK key 塞进业务 DTO。未验签的 public/trial 旁路不会设置它。
     */
    public static final String VERIFIED_BODY_PROP = "aster.internal.verified-body";

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    /**
     * 红队 P0-C：nonce 重放防护。复用全局 {@link io.aster.policy.security.NonceService}
     * （Postgres UsedNonce 原子 persistIfNotExists，跨 pod 唯一，5min TTL 自动清理），
     * 与 RequestSignatureFilter 同一套，避免再造一份 nonce 存储。
     */
    @Inject
    io.aster.policy.security.NonceService nonceService;

    /**
     * 红队 P0-C（Codex P1）：内部调用 body 上限（字节）。与全局 quarkus.http.limits.
     * max-body-size=1M 对齐。防 readAllBytes 缓冲被超大 body 放大内存。
     */
    private static final int MAX_INTERNAL_BODY_BYTES = 1024 * 1024;

    @ConfigProperty(name = "aster.security.evaluate-source.public", defaultValue = "false")
    boolean evaluateSourcePublic;

    /**
     * Marketing-tier "trial" 旁路，专为 aster-lang.dev playground 设计。
     *
     * <p>区别于 {@code .public}：
     * <ul>
     *   <li>仅放行 {@code /api/v1/policies/evaluate-source} 一条路径。</li>
     *   <li>放行后必须经过 {@link io.aster.policy.security.TrialEndpointGuard} 的
     *       Origin / body-size / per-IP 限流二次校验；后者优先级早于本 filter，
     *       会先把不合规请求 short-circuit 掉。</li>
     * </ul>
     *
     * <p>设计意图：让 operator 不用为了开 marketing playground 而把
     * {@code aster.security.evaluate-source.public=true}（更大信任口）一起打开。
     */
    @ConfigProperty(name = "aster.security.evaluate-source.trial.enabled", defaultValue = "false")
    boolean evaluateSourceTrial;

    /**
     * R23-Critical-2 + R25-Major-3: 临时旁路开关。
     *
     * <p>{@code aster.security.ai.public}：全量旁路所有 /api/v1/ai/*（含 /complete + SSE）。
     * 仅在紧急回退时使用 —— 例如 cloud-side proxy 也挂了的情况下让浏览器直连维持服务。
     * <b>生产应保持 false</b>。
     *
     * <p>{@code aster.security.ai.sse.public}：仅旁路 SSE 端点 (/generate, /suggest)，
     * /complete 保持锁定。R25-Major-3 引入：cloud-side proxy 目前只覆盖 /complete，
     * SSE proxy 还没写完。生产可以临时设 true 让浏览器直连 SSE 端点维持 AI 面板能用，
     * 同时保留 /complete 的鉴权（因为 cloud-side 已迁移）。设 true 时每次调用打 warn。
     *
     * <p>优先级：{@code aiPublic} > {@code aiSsePublic}（粗粒度先生效）。
     */
    @ConfigProperty(name = "aster.security.ai.public", defaultValue = "false")
    boolean aiPublic;

    @ConfigProperty(name = "aster.security.ai.sse.public", defaultValue = "false")
    boolean aiSsePublic;

    /**
     * R25-Major-3: SSE 端点白名单。 /complete 不在此 set 里 —— 它走 cloud-side proxy，
     * 必须始终鉴权。
     */
    private static final java.util.Set<String> AI_SSE_PATHS = java.util.Set.of(
        "/api/v1/ai/generate",
        "/api/v1/ai/suggest"
    );

    /**
     * R27-Minor-3：路径分类 + 旁路决策的纯函数，便于单元测试覆盖。
     *
     * @return Classification 的结果：
     *   - NOT_PROTECTED：路径不在本 filter 管辖范围 → 直接放行
     *   - BYPASS_OK：被 public 配置旁路（dev / 灰度回退）→ 直接放行
     *   - REQUIRE_HMAC：必须验证 HMAC 签名
     */
    enum Classification { NOT_PROTECTED, BYPASS_OK, BYPASS_TRIAL, REQUIRE_HMAC }

    /**
     * 5 参重载：不带内部调用方信号（等价 hasInternalCaller=false）。保留给已有调用方/测试，
     * 语义与改造前一致。
     */
    static Classification classify(String normalizedPath,
                                   boolean evaluateSourcePublic,
                                   boolean evaluateSourceTrial,
                                   boolean aiPublic,
                                   boolean aiSsePublic) {
        return classify(normalizedPath, evaluateSourcePublic, evaluateSourceTrial,
            aiPublic, aiSsePublic, false);
    }

    static Classification classify(String normalizedPath,
                                   boolean evaluateSourcePublic,
                                   boolean evaluateSourceTrial,
                                   boolean aiPublic,
                                   boolean aiSsePublic,
                                   boolean hasInternalCaller) {
        boolean isEvaluateSource = normalizedPath.equals("/api/v1/policies/evaluate-source");
        boolean isAi = normalizedPath.startsWith("/api/v1/ai/")
            // 仅 LLM 代理路径，且必须有 "/ai/<something>" 后缀（防 /api/v1/ai 自身误匹配）
            && normalizedPath.length() > "/api/v1/ai/".length();
        // ★安全审计修复（2026-08-17）：What-If 批次此前**服务端完全无保护**。
        //
        // cloud BFF 一直把它当作内部端点在签名（policy-api.ts 的 needsInternalCaller
        // 对 /whatif-batches 为 true，并发送 X-Internal-Caller + HMAC 头），
        // 但服务端 classify() 只认 evaluate-source 与 /ai/*，其余一律 NOT_PROTECTED
        // ——**客户端签了名，服务端从不校验**。于是该端点仅剩可伪造的
        // X-User-Id / X-User-Role 把守（生产已关闭全局 signature.enabled）。
        //
        // 注意：**不能**改用 ApiKeyAuthFilter 保护本路径——BFF 走的是内部 HMAC，
        // 不携带 Bearer API key，纳入 API-key allowlist 会让 UI 的 What-If 全部 401。
        // 正确做法是让服务端校验 BFF 已经在发的那套 HMAC 凭据。
        boolean isWhatIfBatch =
            normalizedPath.matches("/api/v1/policies/[^/]+/whatif-batches(?:/[^/]+)?");
        if (!isEvaluateSource && !isAi && !isWhatIfBatch) return Classification.NOT_PROTECTED;
        // What-If 无 public/trial 旁路：它按窗口重跑历史执行，属内部编排能力，
        // 不对匿名浏览器流量开放。始终要求 HMAC。
        if (isWhatIfBatch) return Classification.REQUIRE_HMAC;
        // 优先级：.public（全量旁路）> 内部调用 HMAC > .trial（匿名浏览器流量经 TrialEndpointGuard）。
        if (isEvaluateSource && evaluateSourcePublic) return Classification.BYPASS_OK;
        // 携带内部调用头（cloud-bff）的 evaluate-source 走 HMAC 校验，即使 trial 开启也不归
        // trial 旁路——否则 trial 模式下，已认证的内部调用（无浏览器 Origin）会被 trial guard
        // 的 Origin 闸门误拦（cloud /policies/{id}/execute 报 origin_not_allowed 即此因）。
        if (isEvaluateSource && evaluateSourceTrial && !hasInternalCaller) return Classification.BYPASS_TRIAL;
        if (isAi && aiPublic) return Classification.BYPASS_OK;
        // R25-Major-3：细粒度 SSE 旁路 —— 只放 generate/suggest，
        // /complete 仍要求 HMAC（已走 cloud-side proxy）。
        if (isAi && aiSsePublic && AI_SSE_PATHS.contains(normalizedPath)) {
            return Classification.BYPASS_OK;
        }
        return Classification.REQUIRE_HMAC;
    }

    /**
     * P2-R20/R22: startup config validation. If all bypass flags are false
     * AND hmacKey is missing/empty, the internal API endpoints are silently
     * unreachable in production. Log a clear startup warning so operators
     * notice misconfiguration before traffic arrives instead of discovering
     * it via 403 logs at request time.
     *
     * <p>Bypass flags (evaluateSourcePublic / evaluateSourceTrial / aiPublic /
     * aiSsePublic) intentionally don't require HMAC; only flag absence makes
     * the missing key load-bearing.
     *
     * <p>R22 change: observes Quarkus {@link StartupEvent} instead of
     * {@code @PostConstruct}. The previous {@code @PostConstruct} fired
     * lazily on first request because {@code @ApplicationScoped} beans
     * are CDI lazy-init by default — so a misconfigured production deploy
     * could swallow the warning until a real request arrived (live
     * podman validation in /ccg:test cycle confirmed this).
     * {@code StartupEvent} is fired by Quarkus during the boot
     * lifecycle, guaranteeing the check runs at startup time.
     */
    void validateHmacKeyConfig(@Observes StartupEvent ev) {
        boolean anyBypass = evaluateSourcePublic || evaluateSourceTrial || aiPublic || aiSsePublic;
        boolean keyMissing = hmacKey.isEmpty() || hmacKey.get().isBlank();
        if (keyMissing && !anyBypass) {
            LOG.error(
                "aster.plan-gate.hmac-key is missing/empty and no bypass flags are set. " +
                "All HMAC-protected internal endpoints (/api/v1/ai/*, /api/v1/policies/evaluate-source) " +
                "will return 403. Set hmacKey or enable explicit bypass flag for the environment.");
        } else if (keyMissing) {
            LOG.warnf(
                "aster.plan-gate.hmac-key is missing/empty; relying on bypass flags " +
                "(evaluateSourcePublic=%s, evaluateSourceTrial=%s, aiPublic=%s, aiSsePublic=%s). " +
                "Acceptable for dev/playground but unsafe for production.",
                evaluateSourcePublic, evaluateSourceTrial, aiPublic, aiSsePublic);
        } else {
            LOG.info("InternalCallerFilter HMAC key configured; HMAC-protected endpoints active.");
        }
    }

    /**
     * 审计 #98（Medium 1）：调用方是否已通过 HMAC 校验（{@link #HMAC_VERIFIED_PROP} 已盖章）。
     *
     * <p>下游用它派生 {@code aliasesTrusted}（是否允许注册结构词别名），<b>取代</b>看
     * {@code X-Internal-Caller} 头是否存在。只有真正通过 {@code constantTimeEquals} 的请求
     * 才会被本 filter 盖章；public / trial 旁路路径不会盖章 → 此方法返回 false。
     */
    public static boolean isHmacVerified(ContainerRequestContext ctx) {
        return ctx != null && Boolean.TRUE.equals(ctx.getProperty(HMAC_VERIFIED_PROP));
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 50)
    public Uni<Void> filter(ContainerRequestContext ctx) {
        // R23-Critical-1 + R25-Critical-1：matrix-param 归一化必须 **per-segment**。
        // 参见 ApiKeyAuthFilter.shouldProtect 同样修复。
        String p = io.aster.security.PathNormalizer.normalize(ctx.getUriInfo().getPath());

        boolean hasInternalCaller =
            io.aster.policy.security.TrialBypassPredicate.hasInternalCallerCredentials(ctx);
        Classification cls = classify(p, evaluateSourcePublic, evaluateSourceTrial,
            aiPublic, aiSsePublic, hasInternalCaller);
        if (cls == Classification.NOT_PROTECTED) return Uni.createFrom().voidItem();
        if (cls == Classification.BYPASS_OK) {
            // 旁路命中时打 warn —— 不丢失 operator 可见性
            if (p.startsWith("/api/v1/ai/")) {
                if (aiPublic) {
                    LOG.warnf("AI endpoint %s called via FULL public bypass "
                        + "(aster.security.ai.public=true)", p);
                } else {
                    LOG.warnf("AI SSE endpoint %s called via partial public bypass "
                        + "(aster.security.ai.sse.public=true). /complete remains protected.", p);
                }
            }
            return Uni.createFrom().voidItem();
        }
        if (cls == Classification.BYPASS_TRIAL) {
            // TrialEndpointGuard 优先级早于本 filter，已经做过 Origin/body/IP 限流。
            // `evaluate-source.trial.enabled=true` 是必要不充分条件：必须看到
            // guard 颁发的凭证 + 路径精确匹配 TRIAL_PATH，才能放行。
            // R30 改成调 TrialBypassPredicate 与其它 5 个 bypass 点共用同一判定。
            if (!io.aster.policy.security.TrialBypassPredicate.isGuardedTrialRequest(ctx)) {
                LOG.warnf("trial bypass denied: guard property/path missing (path=%s)", p);
                throw forbidden("trial_guard_not_satisfied", p);
            }
            LOG.debugf("evaluate-source served via marketing-tier trial bypass (path=%s)", p);
            return Uni.createFrom().voidItem();
        }
        // cls == REQUIRE_HMAC

        String caller = ctx.getHeaderString("X-Internal-Caller");
        String timestamp = ctx.getHeaderString("X-Aster-Timestamp");
        String signature = ctx.getHeaderString("X-Internal-Signature");
        // 红队 P0-C：nonce 必填（绑定进 canonical + 重放去重）。旧 v1（无 nonce 的 3 行签名）
        // 无真实用户，无需兼容窗口——直接只认 v2，缺 nonce = 缺凭证拒绝。
        String nonce = ctx.getHeaderString("X-Aster-Nonce");

        if (!"cloud-bff".equals(caller) || timestamp == null || signature == null || nonce == null) {
            String reason = p.startsWith("/api/v1/ai/")
                ? "ai_internal_only" : "evaluate_source_internal_only";
            throw forbidden(reason, p);
        }

        // 时间戳防重放（5 min 窗口）
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw forbidden("invalid_timestamp", p);
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > 300) {
            throw forbidden("stale_timestamp", p);
        }

        if (hmacKey.isEmpty()) {
            // 配置错误：生产必须配 HMAC；如果缺则全部拒绝
            LOG.warnf("%s called without HMAC key configured; rejecting", p);
            throw forbidden("hmac_not_configured", p);
        }

        // 红队 P0-C：canonical = method\npath\nts\nnonce\nbodySha256\ntenant\nrole，
        // 绑定 body/tenant/role/nonce，修掉「改 body 烧预算 / 改 tenant/role 假冒提权 / 重放」。
        // body 需在 @ServerRequestFilter 里缓存后重置流（镜像 RequestSignatureFilter），
        // 故改为 Uni + worker 线程执行（避免 event-loop 阻塞读流 + DB nonce 写入）。
        final String method = ctx.getMethod();
        final String tenant = headerOrEmpty(ctx, "X-Tenant-Id");
        final String role = headerOrEmpty(ctx, "X-User-Role");
        return Uni.createFrom().item(() -> {
            try {
                // 红队 P0-C（Codex P1）：显式 body 上限。Quarkus 全局 quarkus.http.limits.
                // max-body-size=1M 已在 HTTP 层拦超大 body，此处再按 Content-Length 快速失败
                // （防在 readAllBytes 前就明知超限还去缓冲），defense-in-depth。
                int declaredLen = ctx.getLength();
                if (declaredLen > MAX_INTERNAL_BODY_BYTES) {
                    throw forbidden("body_too_large", p);
                }
                byte[] body = ctx.getEntityStream().readAllBytes();
                if (body.length > MAX_INTERNAL_BODY_BYTES) {
                    throw forbidden("body_too_large", p);
                }
                ctx.setEntityStream(new ByteArrayInputStream(body));

                String bodySha = sha256Hex(body);
                String canonical = method + "\n" + p + "\n" + ts + "\n" + nonce + "\n"
                    + bodySha + "\n" + tenant + "\n" + role;
                String expected = sign(hmacKey.get(), canonical);
                if (!constantTimeEquals(expected, signature)) {
                    throw forbidden("invalid_signature", p);
                }

                // 审计 #98（Medium 1）：签名<b>真正</b>通过后才盖可信凭证章。下游只认这个
                // 属性来决定 aliasesTrusted，绝不看 X-Internal-Caller 头的存在与否。public /
                // trial 旁路路径在此之前就 return 了，天然不会设置它 → 逃生舱下带垃圾签名的
                // 调用方拿不到结构词别名信任。
                ctx.setProperty(HMAC_VERIFIED_PROP, Boolean.TRUE);
                // Phase 2 BYOK：把已验签 body 字节挂到请求属性，供 resource 从可信 body 解析
                // _byok envelope（只有走到这里=签名已过的请求才有；旁路路径拿不到）。
                ctx.setProperty(VERIFIED_BODY_PROP, body);

                // 签名通过后再消费 nonce（防重放）。requestHash 绑定 method/path/query/body，
                // tenant 维度键（内部调用无浏览器 tenant 时用 "cloud-bff" 占位，跨 pod 唯一）。
                String query = ctx.getUriInfo().getRequestUri().getQuery();
                String requestHash = io.aster.policy.security.RequestCanonicalizer.computeRequestHash(
                    method, ctx.getUriInfo().getPath(), query, body);
                String nonceTenant = tenant.isEmpty() ? "cloud-bff" : tenant;
                nonceService.ensureFresh(nonceTenant, nonce, requestHash);
                return null;
            } catch (WebApplicationException e) {
                throw e; // 透传 403/409，保留状态码
            } catch (java.io.IOException e) {
                throw new RuntimeException("failed to buffer request body for HMAC", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
          .replaceWithVoid();
    }

    private static String headerOrEmpty(ContainerRequestContext ctx, String name) {
        String v = ctx.getHeaderString(name);
        return v == null ? "" : v;
    }

    /** body sha256（hex）。空 body → 空字节的 sha256（与 cloud 端 sha256Hex 一致）。 */
    static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body == null ? new byte[0] : body));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static WebApplicationException forbidden(String reason, String path) {
        // R27-Minor-2: operator-actionable 错误消息。区分三类：
        //   1. AI SSE endpoint (generate/suggest) —— 当前 cloud SSE proxy 还没写完，
        //      operator 可临时开 aster.security.ai.sse.public=true 灰度回退
        //   2. AI complete —— cloud proxy 已就绪 (/api/llm/complete)，必须经它转发；
        //      ai.sse.public 对 /complete 无效
        //   3. evaluate-source —— 仅 cloud-bff 内部使用
        boolean isAi = path.startsWith("/api/v1/ai/");
        boolean isAiSse = isAi && AI_SSE_PATHS.contains(path);
        String error;
        String message;
        if (isAiSse) {
            error = "ai_sse_endpoint_forbidden";
            message = "AI SSE endpoint requires HMAC-signed call from cloud-side proxy. "
                + "Cloud-side SSE proxy is not yet implemented — to temporarily allow "
                + "browser direct calls during migration, set "
                + "aster.security.ai.sse.public=true (operator decision).";
        } else if (isAi) {
            // 主要就是 /api/v1/ai/complete —— cloud-side proxy 已就绪
            error = "ai_endpoint_forbidden";
            message = "AI endpoint must be called via aster-cloud server-side proxy "
                + "/api/llm/complete (which signs with PlanGate HMAC). Direct browser "
                + "calls are not supported. Note: aster.security.ai.sse.public does NOT "
                + "apply to /complete.";
        } else {
            error = "evaluate_source_forbidden";
            message = "POST /evaluate-source is internal-only. "
                + "Use /evaluate (DB-backed) for production policies.";
        }
        return new WebApplicationException(
            Response.status(403)
                .entity(Map.of(
                    "error", error,
                    "reason", reason,
                    "path", path,
                    "message", message
                ))
                .type(MediaType.APPLICATION_JSON)
                .build()
        );
    }

    /**
     * R27-Minor-3: 包级可见，便于单测复用同一签名实现。
     */
    static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
