package io.aster.policy.rest;

import io.aster.billing.ApiQuotaGuard;
import io.aster.monitoring.BusinessMetrics;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.BatchRequest;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.metrics.PolicyMetrics;
import io.aster.policy.api.convert.NamedContextMapper;
import io.aster.policy.api.schema.ParameterSchemaExtractor;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.replay.ReplayExecutorAdapter;
import io.aster.policy.rest.model.*;
import io.aster.replay.core.ExecutionPhaseResult;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.replay.core.ReplayExecutionRequest;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import io.aster.policy.security.rbac.AnonymousAllowed;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * REST API资源：策略评估服务
 *
 * 提供策略评估、批量评估、验证和缓存管理的RESTful接口。
 * 支持通过 X-Tenant-Id 头部实现多租户隔离。
 */
@Path("/api/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class PolicyEvaluationResource {

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationResource.class);

    /**
     * Bounded concurrency for /evaluate-source. Each in-flight call
     * holds one Polyglot Context (shared Engine, but per-call runtime
     * state), and uncapped concurrency under burst load drove the JVM
     * into OOM during loadtest — heap exhaustion at c≥4 was
     * deterministic regardless of -Xmx setting.
     *
     * The cap is min(CPU-bound, heap-bound):
     *   - CPU bound: 2× cores. Per-call work is Truffle interpretation
     *     (CPU-bound, not I/O-bound). 1× starves on any GC pause; 4×
     *     reproduces the original OOM in our sweep.
     *   - Heap bound: maxHeap / EVAL_SOURCE_HEAP_BUDGET_MB. Empirical
     *     measurement put each in-flight call at ~30–50 MB of transient
     *     Polyglot state (Context build + AST instantiation + JSON parse
     *     of Core IR). We budget 64 MB / call so the cap stays inside
     *     heap even with GC pauses doubling working-set briefly.
     *
     * Why the heap floor matters: the May sweep showed that on a 1 CPU /
     * 512m heap container, even the 2-permit cap let two concurrent
     * Context.build() calls OOM mid-construction. The heap bound on the
     * 512m profile evaluates to 512/64 = 8 — looks fine, but in practice
     * Quarkus base RSS + buffers leave only ~200m for application heap,
     * which translates to ~3 concurrent calls. The Semaphore now picks
     * the *smaller* of the two bounds, so any narrow-heap container
     * automatically converges on the safer limit.
     *
     * Acquisition uses a short wait window before falling back to 503
     * + Retry-After: marketing playground / dashboard preview users
     * see "server busy, please retry" instead of a 5xx storm.
     *
     * The marketing /api/playground/evaluate-source path (the only
     * public consumer) is also rate-limited at the BFF and now
     * debounced + min-interval'd in the AsterPlayground component.
     * Defense in depth — any one layer failing still keeps the
     * backend from going down.
     *
     * Operators: production deployments should provision ≥1 GB heap.
     * The startup banner WARNs if maxHeap is below this threshold so
     * miscalibrated sidecars surface in the logs instead of pager.
     */
    private static final long EVAL_SOURCE_HEAP_BUDGET_MB = 64;
    private static final long MIN_RECOMMENDED_HEAP_MB = 768;
    private static final int EVAL_SOURCE_PERMITS_COUNT;
    static {
        int cpuBound = Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int heapBound = (int) Math.max(1, maxHeapMb / EVAL_SOURCE_HEAP_BUDGET_MB);
        EVAL_SOURCE_PERMITS_COUNT = Math.min(cpuBound, heapBound);
        if (maxHeapMb < MIN_RECOMMENDED_HEAP_MB) {
            LOG.warnf("evaluate-source: maxHeap=%d MB is below recommended %d MB "
                + "for production. Concurrency capped to %d permits "
                + "(cpuBound=%d, heapBound=%d). Increase -Xmx to raise the cap.",
                maxHeapMb, MIN_RECOMMENDED_HEAP_MB, EVAL_SOURCE_PERMITS_COUNT,
                cpuBound, heapBound);
        } else {
            LOG.infof("evaluate-source: concurrency cap = %d "
                + "(cpuBound=%d, heapBound=%d, maxHeap=%d MB)",
                EVAL_SOURCE_PERMITS_COUNT, cpuBound, heapBound, maxHeapMb);
        }
    }
    private static final Semaphore EVAL_SOURCE_PERMITS =
        new Semaphore(EVAL_SOURCE_PERMITS_COUNT, true);
    private static final long EVAL_SOURCE_ACQUIRE_TIMEOUT_MS = 250;

    /**
     * Bounded concurrency for the ANONYMOUS CNL-parsing endpoints
     * (/schema, /validate). These are not API-key protected (metadata, no
     * side effects) but they DO run the lexer/canonicalizer, which is
     * super-linear in input length — a burst of near-64KB-cap requests
     * could otherwise pin every worker thread for seconds. The work here is
     * parse-only (no Polyglot Context, far lighter than evaluate-source), so
     * a plain CPU-bound cap (2× cores) suffices; on saturation we shed with
     * 503 + Retry-After rather than letting the worker pool starve. Per-IP
     * rate limiting (RateLimitFilter) + the 64KB @Size cap + the global body
     * limit are the other layers; this is the concurrency backstop.
     */
    private static final int ANON_PARSE_PERMITS_COUNT =
        Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
    private static final Semaphore ANON_PARSE_PERMITS =
        new Semaphore(ANON_PARSE_PERMITS_COUNT, true);
    private static final long ANON_PARSE_ACQUIRE_TIMEOUT_MS = 200;

    // 审计 #98（Medium 3, DEFERRED）：匿名 /schema、/validate 的每请求解析墙钟超时。
    // 本 PR 只保留静态的匿名源码上限（MAX_ANON_SOURCE_LENGTH=16 KiB，见 SchemaRequest），
    // 把最坏单次解析耗时压进秒级；Mutiny 每请求墙钟超时（.ifNoItem().after().failWith()）延后
    // 单独处理——它挂在 JAX-RS reactive 返回的 Uni 上，需在能跑 Quarkus 增强/集成测试的环境里
    // 验证后再加。见 issue #98。

    @Inject
    PolicyEvaluationService evaluationService;

    @Inject
    PolicyMetrics policyMetrics;

    @Inject
    BusinessMetrics businessMetrics;

    @Inject
    PolicyAuditPublisher auditPublisher;

    @Inject
    ApiQuotaGuard apiQuotaGuard;

    @Inject
    ReplayExecutorAdapter replayExecutorAdapter;

    /**
     * replay 执行编排的 core 三阶段 API（P0-A S2-1a-0 Task 4，从
     * replay 抽出，无 Quarkus/CDI 依赖，纯值对象——直接 new，
     * 不走 CDI 生产者（无状态、无需容器管理生命周期）。
     */
    private final ReplayExecutionCore replayExecutionCore = new ReplayExecutionCore();

    @Inject
    io.aster.policy.tenant.TenantContext tenantContext;

    /**
     * 调用方身份解析（issue #174）：tenant / user / apiKeyId 的单一事实源。
     * 此前 4 个 resource 各自手写 tenantId()，且已漂移——只有本类带 R32 hotfix。
     */
    @Inject
    RequestIdentityResolver identityResolver;

    @Inject
    io.aster.policy.compiler.PolicyCompiler policyCompiler;

    @Inject
    io.aster.policy.stability.ToolchainIdentityProvider toolchainIdentityProvider;

    @ConfigProperty(name = "aster.entry.legacy-evaluate-sentinel", defaultValue = "true")
    boolean legacyEvaluateSentinel;

    @Context
    RoutingContext routingContext;

    /**
     * R29++ Codex audit：JAX-RS request scope，用于读取
     * {@link io.aster.policy.security.TrialEndpointGuard#TRIAL_GUARD_PASSED_PROP}
     * 凭证。该凭证由 TrialEndpointGuard 在 AUTHENTICATION-100 优先级设上，
     * 只在 trial 请求生命周期内有效。enforceApiQuota 用它做三重校验
     * （路径 + 凭证 + tenant），避免 quota bypass 只看 tenant 字符串。
     */
    @Context
    jakarta.ws.rs.container.ContainerRequestContext jaxrsCtx;

    /**
     * 评估单个策略
     *
     * POST /api/policies/evaluate
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility", "context": [{...}, {...}] }
     */
    @POST
    @Path("/evaluate")
    public Uni<EvaluationResponse> evaluate(@Valid EvaluationRequest request) {
        enforceApiQuota("/api/v1/policies/evaluate");
        // 跨线程预捕获，必须在进入 Uni 之前——理由见 RequestIdentity。
        RequestIdentity identity = captureIdentity();
        String tenantId = identity.tenantId();
        String performedBy = identity.performedBy();
        String apiKeyId = identity.apiKeyId();
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = businessMetrics.startPolicyEvaluation();
        Map<String, Object> metadata = buildEvaluationMetadata(request);

        LOG.infof("Evaluating policy: %s.%s for tenant %s", request.policyModule(), request.policyFunction(), tenantId);

        return evaluationService.evaluatePolicy(
                tenantId,
                request.policyModule(),
                request.policyFunction(),
                request.context()
        )
        .onItem().transform(result -> {
            long executionTime = System.currentTimeMillis() - startTime;

            // 记录指标（非阻塞）
            policyMetrics.recordEvaluation(request.policyModule(), request.policyFunction(), executionTime, true);
            businessMetrics.recordPolicyEvaluation();
            businessMetrics.endPolicyEvaluation(sample);

            // 记录业务指标（贷款批准/拒绝）
            if ("aster.finance.loan".equals(request.policyModule())) {
                recordLoanDecision(result.getResult());
            }

            publishPolicyEvaluationEvent(
                tenantId,
                request,
                performedBy,
                true,
                executionTime,
                null,
                metadata
            );

            LOG.infof("Policy evaluation completed in %dms: %s.%s", executionTime, request.policyModule(), request.policyFunction());
            recordApiCall("/api/v1/policies/evaluate", "success", executionTime, tenantId, performedBy, apiKeyId);
            return EvaluationResponse.success(result.getResult(), executionTime);
        })
        .onFailure().recoverWithItem(throwable -> {
            long executionTime = System.currentTimeMillis() - startTime;

            // 记录错误指标（非阻塞）
            policyMetrics.recordEvaluation(request.policyModule(), request.policyFunction(), executionTime, false);
            businessMetrics.endPolicyEvaluation(sample);

            publishPolicyEvaluationEvent(
                tenantId,
                request,
                performedBy,
                false,
                executionTime,
                throwable.getMessage(),
                metadata
            );

            LOG.errorf(throwable, "Policy evaluation failed after %dms: %s.%s", executionTime, request.policyModule(), request.policyFunction());
            recordApiCall("/api/v1/policies/evaluate", "api_error", executionTime, tenantId, performedBy, apiKeyId);
            return EvaluationResponse.error(throwable.getMessage());
        });
    }

    /**
     * 评估 JSON 格式策略
     *
     * POST /api/policies/evaluate-json
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policy": "{...Core IR JSON...}", "context": {...} }
     */
    @POST
    @Path("/evaluate-json")
    public Uni<EvaluationResponse> evaluateJson(@Valid JsonPolicyRequest request) {
        enforceApiQuota("/api/v1/policies/evaluate-json");
        // 跨线程预捕获，必须在进入 Uni 之前——理由见 RequestIdentity。
        RequestIdentity identity = captureIdentity();
        String tenantId = identity.tenantId();
        String performedBy = identity.performedBy();
        String apiKeyId = identity.apiKeyId();
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = businessMetrics.startPolicyEvaluation();

        LOG.infof("Evaluating JSON policy for tenant %s", tenantId);

        try {
            // request.policy() 本就是 Core IR JSON，直接在进程内执行（issue #172）。
            //
            // 此前这里先 fork `node aster-convert` 把 JSON 转成 CNL、再解析回 Core IR。
            // 但生产运行镜像（Dockerfile.jvm = zulu-alpine JRE）**不含 Node**，
            // ASTER_CLI_PATH 也从未配置，resolveCliPath() 最终回退到裸 "aster-convert"
            // 必然 IOException —— 该端点在生产 100% 不可用。
            //
            // 顺带修掉第二个问题：原实现从 CNL 里抽出模块名/函数名后走
            // evaluationService.evaluatePolicy(按名字查已部署策略)，这与本端点
            // "无需提前部署"的契约相矛盾——传进来的策略体其实从未被执行。
            // 现在直接执行请求携带的 Core IR。
            Object evalContext = request.context() instanceof List<?> list
                ? list.toArray()
                : request.context();

            DynamicCnlExecutor.ExecutionResult execResult =
                DynamicCnlExecutor.executeCoreIrJson(request.policy(), evalContext, null);

            String policyModule = execResult.moduleName();
            String policyFunction = execResult.functionName();
            Object[] contextArray = evalContext instanceof Object[] arr
                ? arr
                : new Object[] { evalContext };

            LOG.infof("Executed Core IR policy: %s.%s", policyModule, policyFunction);

            return Uni.createFrom().item(execResult)
            .onItem().transform(result -> {
                long executionTime = System.currentTimeMillis() - startTime;

                // 记录指标（非阻塞）
                policyMetrics.recordEvaluation(policyModule, policyFunction, executionTime, true);
                businessMetrics.recordPolicyEvaluation();
                businessMetrics.endPolicyEvaluation(sample);

                // 记录业务指标（贷款批准/拒绝）
                if ("aster.finance.loan".equals(policyModule)) {
                    recordLoanDecision(result.result());
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceFormat", "json");

                publishPolicyEvaluationEvent(
                    tenantId,
                    new EvaluationRequest(policyModule, policyFunction, contextArray),
                    performedBy,
                    true,
                    executionTime,
                    null,
                    metadata
                );

                LOG.infof("JSON policy evaluation completed in %dms: %s.%s", executionTime, policyModule, policyFunction);
                recordApiCall("/api/v1/policies/evaluate-json", "success", executionTime, tenantId, performedBy, apiKeyId);
                return EvaluationResponse.success(result.result(), executionTime);
            })
            .onFailure().recoverWithItem(throwable -> {
                long executionTime = System.currentTimeMillis() - startTime;

                // 记录错误指标（非阻塞）
                policyMetrics.recordEvaluation(policyModule, policyFunction, executionTime, false);
                businessMetrics.endPolicyEvaluation(sample);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceFormat", "json");

                publishPolicyEvaluationEvent(
                    tenantId,
                    new EvaluationRequest(policyModule, policyFunction, contextArray),
                    performedBy,
                    false,
                    executionTime,
                    throwable.getMessage(),
                    metadata
                );

                LOG.errorf(throwable, "JSON policy evaluation failed after %dms: %s.%s", executionTime, policyModule, policyFunction);
                recordApiCall("/api/v1/policies/evaluate-json", "api_error", executionTime, tenantId, performedBy, apiKeyId);
                return EvaluationResponse.error(throwable.getMessage());
            });
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            businessMetrics.endPolicyEvaluation(sample);

            LOG.errorf(e, "Failed to process JSON policy: %s", e.getMessage());
            recordApiCall("/api/v1/policies/evaluate-json", "api_error", executionTime, tenantId, performedBy, apiKeyId);
            return Uni.createFrom().item(EvaluationResponse.error("JSON 策略解析失败: " + e.getMessage()));
        }
    }

    /**
     * 直接评估 CNL 源代码
     *
     * POST /api/policies/evaluate-source
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "source": "module aster.example ... ", "context": {...}, "locale": "en-US", "functionName": "approveLoan" (可选，未指定时由入口选择器决定) }
     *
     * 适用于 Dashboard 执行场景，无需预先部署策略。
     * 使用动态执行流程：CNL → AST → Core IR → JSON → GraalVM Polyglot
     *
     * 支持两种 context 格式：
     * 1. 命名格式: { "申请": {...}, "年龄": 25 } - 参数名与函数定义匹配
     * 2. 位置格式: [{...}, 25] - 按位置顺序传参
     *
     * <p>R30+ audit P1：本方法 149 行，主要承担 5 个职责（quota / permit /
     * tenant snapshot / Truffle 执行 / 审计发布）。重构计划（R31）：
     * <ol>
     *   <li>提取 {@code EvaluationDispatcher} 处理 CNL → Truffle 调用 + 错误归一</li>
     *   <li>提取 {@code AuditEventPublisher} 包装 publishPolicyEvaluationEvent</li>
     *   <li>permit acquire/release 用 try-with-resources AutoCloseable 包装</li>
     * </ol>
     * 暂不动是因为本方法被 R28→R30 5 轮 audit 反复读过，行为契约稳定，
     * 重构需要先补端到端 IT 才能安全推进（QuotaChainIT 是第一步）。
     */
    @POST
    @Path("/evaluate-source")
    public Uni<EvaluationResponse> evaluateSource(
        @Valid SourcePolicyRequest request,
        @QueryParam("trace") @DefaultValue("false") boolean trace,
        // P0-A 回放地基（ADR 0030 附录 A）：replayCapture=true 时在响应体附
        // replayMetadata（runtimeToolchainId + canonical input/output hash +
        // traceHash + canonicalizationVersion）。cloud BFF 拿到后写 Execution
        // 新列（本 API 不直写 cloud DB）。hash 由本侧（Java 权威）计算。
        @QueryParam("replayCapture") @DefaultValue("false") boolean replayCapture,
        // ★模拟执行（What-if / ADR 0033）：这不是一次真实业务执行，而是拿历史
        //   输入在**另一个版本**的源码上重跑，只为得到对照决策。true 时跳过全部
        //   「这是一次真实执行」的副作用：
        //     · 配额——查一次估算要重跑上百条，按真实执行计费等于看报表就被扣钱
        //     · 业务指标（recordEvaluation/recordPolicyEvaluation/recordLoanDecision）
        //       ——模拟结果混进 KPI 会污染真实经营数据
        //     · 审计事件——审计链记的是「谁在何时做了什么决策」，模拟没做出任何决策
        //     · API 调用统计
        //   并发闸门**不跳过**：模拟同样消耗 CPU，仍需背压保护。
        @QueryParam("simulate") @DefaultValue("false") boolean simulate
    ) {
        // ★simulate 必须绑定 HMAC 内部调用者（与 replayCapture 同门控，第九轮 P0-1b）：
        //   它是一个**免计费**开关，若信任裸 query boolean，任何外部调用方
        //   加个 ?simulate=true 就能白嫖配额、且不留 API 调用记录。
        //   未验证的调用方传了也静默忽略（与 replayCapture 一致，保持 trial 端点宽容）。
        final boolean effectiveSimulate =
            simulate && io.aster.security.apikey.InternalCallerFilter.isHmacVerified(jaxrsCtx);
        if (!effectiveSimulate) {
            enforceApiQuota("/api/v1/policies/evaluate-source");
        }
        // Bounded concurrency gate. Acquire before doing any work; if
        // the wait window expires, return 503 with Retry-After so the
        // caller (BFF / playground) backs off instead of piling on.
        // Release happens in the worker's own finally block — NOT in
        // Uni.onTermination. onTermination fires on cancellation, which
        // would hand the permit back while the synchronous worker is
        // still burning CPU; repeated start-then-cancel would then push
        // real concurrency past this gate. The finally covers both
        // normal return and exceptions, so no permit leaks either.
        final boolean acquired;
        try {
            acquired = EVAL_SOURCE_PERMITS.tryAcquire(
                EVAL_SOURCE_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(503)
                    .header("Retry-After", "1")
                    .entity(Map.of(
                        "error", "evaluate_source_busy",
                        "message", "Server interrupted while waiting for an eval slot"))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }
        if (!acquired) {
            LOG.warnf("evaluate-source 拒绝：并发达到上限 %d，返回 503",
                EVAL_SOURCE_PERMITS_COUNT);
            throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(503)
                    .header("Retry-After", "1")
                    .entity(Map.of(
                        "error", "evaluate_source_busy",
                        "message",
                        "Too many concurrent evaluate-source requests. "
                            + "Retry in a moment.",
                        "concurrencyLimit", EVAL_SOURCE_PERMITS_COUNT))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }
        // ★许可归还收敛为一个 CAS 保护的一次性 lease（第十三轮）。
        //
        //   许可在**请求线程**取得，却要在 **worker 线程**归还，中间隔着
        //   Mutiny 的惰性订阅边界。归还必须"恰好一次"，而可能触发它的路径有三条：
        //     1. acquire 后、supplier 前的准备工作抛出（同步）
        //     2. supplier 真正跑完/抛异常（worker 线程，finally）
        //     3. 订阅阶段调度被拒——worker 池饱和，supplier **永不执行**
        //
        //   第 3 条是之前修漏的：runSubscriptionOn 只是**装配** Uni，真正的
        //   executor.execute 发生在稍后的订阅阶段，那时本方法早已返回。
        //   Mutiny 自己捕获拒绝并转成 Uni failure（UniRunSubscribeOn#subscribe），
        //   所以任何写在本方法里的同步 catch 都**不可能**收到它。
        //   后果比取消绕过更糟：单向累积、不可恢复，最终整站 503。
        //
        //   用 AtomicBoolean 的 CAS 做 release-once：三条路径调用同一个
        //   releaseOnce，谁先到谁释放，后到的 CAS 失败直接跳过。
        //   既不泄漏，也不会双重释放（双重 release 会凭空增加许可，
        //   把闸门上限悄悄抬高，比泄漏更隐蔽）。
        final java.util.concurrent.atomic.AtomicBoolean permitReleased =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable releaseOnce = () -> {
            if (permitReleased.compareAndSet(false, true)) {
                EVAL_SOURCE_PERMITS.release();
            }
        };
        final RequestIdentity identity;
        final String tenantId;
        final String performedBy;
        final String apiKeyIdSnap;
        final long apiCallStart;
        final Timer.Sample sample;
        try {
            // 跨线程预捕获，必须在切到 worker pool 之前——理由见 RequestIdentity。
            identity = captureIdentity();
            tenantId = identity.tenantId();
            performedBy = identity.performedBy();
            apiKeyIdSnap = identity.apiKeyId();
            apiCallStart = System.currentTimeMillis();
            sample = businessMetrics.startPolicyEvaluation();

            LOG.infof("Evaluating CNL source for tenant %s (locale=%s, function=%s)",
                tenantId, request.getLocaleOrDefault(), request.getFunctionNameOrDefault());
        } catch (RuntimeException | Error setupFailure) {
            releaseOnce.run();
            throw setupFailure;
        }

        // 使用 Uni.createFrom().item() 包装同步执行，避免阻塞主线程
        return Uni.createFrom().item(() -> {
            // ★许可必须在**worker 真正结束**时归还，不能挂在 onTermination
            //   （第十二轮）：HTTP 取消会立刻触发 onTermination 释放许可，
            //   而同步的 supplier.get() 仍在 CPU 上跑——反复取消即可让并发数
            //   远超闸门上限，闸门形同虚设。放在 finally 里，取消也不会提前归还。
            try {
            try {
                // 结构词别名授权口径按调用来源可信度区分（安全边界）：
                //   - 内部调用方（cloud BFF S2S，带 X-Internal-Caller + HMAC）转发的是**已发布
                //     版本冻结别名快照**——创建时已 per-user 授权+校验+进 envelope，可信 →
                //     allowStructural=true。
                //   - 直连 API-key 的 trial/即时源码=**未冻结的现场用户输入**，不可信 →
                //     allowStructural=false（结构词别名被 UserAliasValidator 拒，防绕过 per-user 授权
                //     注入结构词）。此端点身兼「存储版本执行」与「trial 源码预览」两职，故必须区分。
                // 审计 #98（Medium 1）：结构词别名信任<b>必须</b>绑定到「HMAC 签名已验证」，
                // 而非 X-Internal-Caller 头的<b>存在</b>。InternalCallerFilter 只在 constantTimeEquals
                // 真正通过后盖 HMAC_VERIFIED_PROP 章；evaluate-source.public / trial 旁路路径不会盖章。
                // 因此带三条 X-Internal-* 头 + 垃圾签名的调用方（无论走 public 逃生舱还是 API-key）
                // 在此处得到 aliasesTrusted=false → allowStructural=false，UserAliasValidator 拒其
                // 结构词别名（RETURN/IF/MATCH…），堵住 ADR-0022 门控绕过。
                boolean aliasesTrusted =
                    io.aster.security.apikey.InternalCallerFilter.isHmacVerified(jaxrsCtx);

                // 回放捕获门控（Codex 复审 安全/成本）：replayCapture 只对 HMAC 已验证的内部
                // 调用方（cloud BFF）生效——回放地基只用于 cloud 持久化 Execution，唯一持久化方
                // 是 BFF；trial/匿名流量无 Execution 落库，无理由请求 replayCapture。放行匿名会
                // 让每次评估白算 3 次 canonical 序列化+SHA（大 payload 放大 CPU/内存）。未验证
                // 调用方即使传 replayCapture=true 也静默忽略（非报错——保持 trial 端点宽容）。
                boolean effectiveReplayCapture = replayCapture && aliasesTrusted;

                // 阶段一：建 vocabIndex/aliasSet → arm trace → 调 executor → finally drain
                // （逐字移入 ReplayExecutionCore.execute，见该类注释）。executor 异常原样
                // 透传——不在此处捕获，由下方现有四类 catch 处理。
                ReplayExecutionRequest execRequest = new ReplayExecutionRequest(
                    tenantId,
                    request.source(),
                    request.context(),  // 直接传递原始 context，让执行器处理格式映射
                    request.getFunctionNameOrDefault(),
                    request.getLocaleOrDefault(),
                    request.vocabulary(),
                    request.aliasSet(),
                    legacyEvaluateSentinel,
                    aliasesTrusted,
                    trace,
                    effectiveReplayCapture);
                ExecutionPhaseResult phase = replayExecutionCore.execute(execRequest, replayExecutorAdapter);

                // ★模拟执行跳过全部「真实执行」副作用（见 simulate 参数注释）：
                //   指标会污染真实经营 KPI，审计事件会记录一次并不存在的决策。
                if (!effectiveSimulate) {
                    // 记录指标
                    policyMetrics.recordEvaluation(
                        phase.execResult().moduleName(),
                        phase.execResult().functionName(),
                        phase.execResult().executionTimeMs(),
                        true
                    );
                    businessMetrics.recordPolicyEvaluation();
                    businessMetrics.endPolicyEvaluation(sample);

                    // 记录业务指标（贷款批准/拒绝）
                    if ("aster.finance.loan".equals(phase.execResult().moduleName())) {
                        recordLoanDecision(phase.execResult().result());
                    }

                    // 发布审计事件
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("sourceFormat", "cnl");
                    metadata.put("locale", request.getLocaleOrDefault());
                    metadata.put("dynamicExecution", true);
                    metadata.put("namedContext", request.context() instanceof Map);

                    publishPolicyEvaluationEvent(
                        tenantId,
                        new EvaluationRequest(phase.execResult().moduleName(), phase.execResult().functionName(), new Object[]{request.context()}),
                        performedBy,
                        true,
                        phase.execResult().executionTimeMs(),
                        null,
                        metadata
                    );
                }

                LOG.infof("CNL source evaluation completed in %dms: %s.%s",
                    phase.execResult().executionTimeMs(), phase.execResult().moduleName(), phase.execResult().functionName());

                // 阶段二：回放 trace：trace=true 或 replayCapture=true 时都构建 DecisionTrace。
                // 前者供前端展示，后者供 traceHash 计算（回放地基）。steps 来自 truffle
                // 同线程 drain；drain 标记不可回放时，后续 ReplayMetadata 必须诚实降级。
                io.aster.policy.api.model.DecisionTrace decisionTrace =
                    replayExecutionCore.buildDecisionTrace(
                        phase.execResult(), phase.traceDrainResult(), trace || effectiveReplayCapture);

                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "success",
                        System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }

                // trace 字段只在客户端显式请求 trace=true 时回传（保持既有契约）；
                // replayCapture 单独走 replayMetadata，不污染 decisionTrace 字段。
                EvaluationResponse response = EvaluationResponse.success(
                    phase.execResult().result(),
                    phase.execResult().executionTimeMs(),
                    trace ? decisionTrace : null,
                    phase.execResult().functionName());

                if (effectiveReplayCapture) {
                    // 阶段三：★权威 hash 在 Java 评估侧计算（Codex #4）：input=请求级 context，
                    // output=业务 result，trace=决策级 DecisionTrace（剔 executionTimeMs）。
                    // ★fail-loud（Codex 复审 P0）：显式请求 replayCapture 时**总是**附 replayMetadata，
                    // 绝不静默丢——compute 内部对小数走 string-lift、对真正无法 hash 的值落
                    // NON_REPLAYABLE + reasons，调用方据 replayabilityStatus 判定是否拿到了地基。
                    // 只有 compute 本身意外抛（不该发生，已内部 fail-loud）才兜底为 NON_REPLAYABLE。
                    io.aster.policy.replay.ReplayMetadata rm = replayExecutionCore.computeReplayMetadata(
                        toolchainIdentityProvider.currentToolchainId(),
                        request.context(),
                        phase.execResult(),
                        decisionTrace,
                        phase.traceDrainResult());
                    response = response.withReplayMetadata(rm);
                }

                // 决策骨架（Phase 0）：DecisionTrace 的脱敏投影，供 cloud 落库后做
                // 条件漏斗 / 死分支聚合。
                //
                // ★采集条件与 decisionTrace 的**回传**条件解耦：只要本次构建了
                // DecisionTrace（trace=true 或 replayCapture），就附带骨架。
                // 骨架不含任何业务值（见 TraceSkeleton 类注释与其测试），
                // 故不需要 replayRetentionEnabled 门控——这正是它的价值：
                // 零 PII 成本，对全部租户可用。
                //
                // ★已知局限（Phase 0 不解决）：trace collector 仅在
                // trace=true || effectiveReplayCapture 时 arm（见 ReplayExecutionCore
                // 阶段一），两者皆为 false 的普通执行**不会产生骨架**。
                // 要让漏斗覆盖全量执行，需把 collector 改为常驻——那是有成本的
                // 决策（每次执行多一次 step 收集 + drain），必须先压测确认对 p99
                // 的影响，不能在本次顺手改掉。样本口径因此偏向"开了 trace/replay
                // 的执行"，消费侧（漏斗 UI）必须如实标注，不得声称全量。
                io.aster.policy.replay.TraceSkeleton skeleton =
                    io.aster.policy.replay.TraceSkeleton.from(decisionTrace);
                if (skeleton != null) {
                    response = response.withTraceSkeleton(skeleton);
                }
                return response;

            } catch (DynamicCnlExecutor.AmbiguousEntryException e) {
                // ★模拟执行的耗时不进业务指标（第十轮）：否则「simulate 不污染指标」不成立
                if (!effectiveSimulate) {
                    businessMetrics.endPolicyEvaluation(sample);
                }
                LOG.warnf("Dynamic CNL entry point ambiguous: %s", e.getCandidates());
                // ★异常路径同样不给模拟执行记账（第九轮 P0-1）
                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(400)
                        .entity(EvaluationResponse.ambiguous(e.getCandidates()))
                        .type(MediaType.APPLICATION_JSON)
                        .build()
                );

            } catch (DynamicCnlExecutor.ModuleExecutionException e) {
                // ★模拟执行的耗时不进业务指标（第十轮）：否则「simulate 不污染指标」不成立
                if (!effectiveSimulate) {
                    businessMetrics.endPolicyEvaluation(sample);
                }
                var moduleError = e.resolutionException();
                LOG.warnf("Dynamic CNL module resolution failed: code=%s, message=%s",
                    moduleError.code(), moduleError.getMessage());
                // ★异常路径同样不给模拟执行记账（第九轮 P0-1）
                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(400)
                        .entity(EvaluationResponse.diagnostic(
                            moduleError.code().name(),
                            moduleError.getMessage(),
                            moduleError.candidates()))
                        .type(MediaType.APPLICATION_JSON)
                        .build()
                );

            } catch (DynamicCnlExecutor.DynamicExecutionException e) {
                // ★模拟执行的耗时不进业务指标（第十轮）：否则「simulate 不污染指标」不成立
                if (!effectiveSimulate) {
                    businessMetrics.endPolicyEvaluation(sample);
                }
                LOG.errorf(e, "Dynamic CNL execution failed: %s", e.getMessage());
                // ★异常路径同样不给模拟执行记账（第九轮 P0-1）
                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }
                return EvaluationResponse.error("CNL 动态执行失败: " + e.getMessage());

            } catch (InProcessCnlParser.CnlParseException e) {
                // ★模拟执行的耗时不进业务指标（第十轮）：否则「simulate 不污染指标」不成立
                if (!effectiveSimulate) {
                    businessMetrics.endPolicyEvaluation(sample);
                }
                LOG.errorf(e, "CNL parsing failed: %s", e.getMessage());
                // ★异常路径同样不给模拟执行记账（第九轮 P0-1）
                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }
                return EvaluationResponse.error("CNL 解析失败: " + e.getMessage());

            } catch (Exception e) {
                // ★模拟执行的耗时不进业务指标（第十轮）：否则「simulate 不污染指标」不成立
                if (!effectiveSimulate) {
                    businessMetrics.endPolicyEvaluation(sample);
                }
                LOG.errorf(e, "Failed to process CNL source: %s", e.getMessage());
                // ★异常路径同样不给模拟执行记账（第九轮 P0-1）
                if (!effectiveSimulate) {
                    recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                }
                return EvaluationResponse.error("CNL 策略执行失败: " + e.getMessage());
            }
            } finally {
                // 路径2：worker 真正跑完（正常或异常）。取消**不会**提前触发这里——
                // 这正是第十二轮要的语义：不归还一个仍在烧 CPU 的 worker 的许可。
                releaseOnce.run();
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
            // 路径3：订阅阶段调度被拒 → supplier 永不执行 → 上面的 finally 永不触发。
            // Mutiny 把拒绝转成 Uni failure（UniRunSubscribeOn#subscribe 内部捕获），
            // 只能在这里收到；写在资源方法里的同步 catch 不可达。
            // onTermination 覆盖 failure/cancel/success：前两者兜底，
            // success 时 CAS 已被 worker 拿走，这里是 no-op。
            .onTermination().invoke(releaseOnce);
    }

    /**
     * 获取 CNL 源代码的参数模式
     *
     * POST /api/policies/schema
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "source": "module aster.example ... ", "locale": "en-US", "functionName": "approveLoan" (可选，未指定时由入口选择器决定) }
     *
     * 返回函数参数的结构化模式信息，用于：
     * 1. 动态生成表单（根据参数名和类型生成输入控件）
     * 2. API 客户端参数提示
     * 3. 文档生成
     */
    @POST
    @Path("/schema")
    @AnonymousAllowed  // 只读元数据：豁免类级 @RequireRole，对未认证调用方开放（防护靠 @Size + 并发闸 + 限流）
    public Uni<SchemaResponse> getSchema(@Valid SchemaRequest request) {
        LOG.infof("Extracting schema from CNL source (locale=%s, function=%s)",
            request.getLocaleOrDefault(), request.getFunctionNameOrDefault());

        return Uni.createFrom().item(() -> {
            // 并发闸：解析是 CPU 密集且对长输入超线性，匿名端点必须限并发，
            // 防止突发请求拖垮 worker 池。占满则快速 503 + Retry-After 让调用方重试。
            if (!tryAcquireAnonParse()) {
                throw new jakarta.ws.rs.ServiceUnavailableException(
                    jakarta.ws.rs.core.Response.status(503)
                        .header("Retry-After", "1")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of(
                            "error", "schema_busy",
                            "message", "Schema service is busy; please retry."))
                        .build());
            }
            try {
                ParameterSchemaExtractor.SchemaResult result = ParameterSchemaExtractor.extractSchema(
                    request.source(),
                    request.getFunctionNameOrDefault(),
                    request.getLocaleOrDefault()
                );

                if (result.success()) {
                    LOG.infof("Schema extracted successfully: %s.%s with %d parameters",
                        result.moduleName(), result.functionName(), result.parameters().size());
                    return SchemaResponse.success(result);
                } else {
                    LOG.warnf("Schema extraction failed: %s", result.error());
                    return SchemaResponse.error(result.error());
                }

            } catch (Exception e) {
                LOG.errorf(e, "Failed to extract schema: %s", e.getMessage());
                return SchemaResponse.error("模式提取失败: " + e.getMessage());
            } finally {
                ANON_PARSE_PERMITS.release();
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 编译 CNL 源代码（只解析+降级，不执行、不落库）。
     *
     * <p>POST /api/v1/policies/compile
     *
     * <p>供保存前可编译性校验（cloud defense-in-depth：拒绝落库不可编译的源码）
     * 与 IDE compile-on-type 使用。匿名只读（同 /schema）：豁免 API-key 边界，
     * 防护靠 @Size 上限 + 匿名并发闸 + 限流。返回结构化 diagnostics（1-based 行列），
     * 前端 Monaco 可精确标错。
     */
    @POST
    @Path("/compile")
    @AnonymousAllowed
    public Uni<CompileResponse> compile(@Valid CompileRequest request) {
        LOG.infof("Compiling CNL source (locale=%s, hasAliases=%b)",
            request.getLocaleOrDefault(),
            request.aliasSet() != null && !request.aliasSet().isEmpty());

        return Uni.createFrom().item(() -> {
            if (!tryAcquireAnonParse()) {
                throw new jakarta.ws.rs.ServiceUnavailableException(
                    jakarta.ws.rs.core.Response.status(503)
                        .header("Retry-After", "1")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of(
                            "error", "compile_busy",
                            "message", "Compile service is busy; please retry."))
                        .build());
            }
            try {
                // aliasSet 大小治理：源码有 16KB 上限，但 aliasSet 是独立 body 字段，
                // 不受该 @Size 约束——须单独限长，防攻击者把负载塞进 aliasSet 绕过
                // 成本控制（反序列化+序列化+parse 都吃 CPU）。超限 → 400 快速拒。
                String aliasSetError = validateAliasSetBounds(request.aliasSet());
                if (aliasSetError != null) {
                    throw new jakarta.ws.rs.BadRequestException(
                        jakarta.ws.rs.core.Response.status(400)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(java.util.Map.of("error", "alias_set_too_large",
                                "message", aliasSetError))
                            .build());
                }
                // 别名 Map → JSON 串。序列化失败 fail-closed（返编译失败），不静默降级
                // 为无别名（否则依赖别名的源码会被误判解析错误）。
                String aliasSetJson;
                try {
                    aliasSetJson = serializeAliasSet(request.aliasSet());
                } catch (Exception se) {
                    return CompileResponse.fail(
                        java.util.List.of(CompileDiagnostic.error(1, 1, "别名集无法处理，无法编译")),
                        "别名集序列化失败: " + se.getMessage());
                }
                CompilationResult result = policyCompiler.compile(
                    request.source(), request.getLocaleOrDefault(), aliasSetJson);
                return toCompileResponse(result);
            } catch (jakarta.ws.rs.BadRequestException bre) {
                throw bre; // 400 直接上抛，不吞成编译失败
            } catch (Exception e) {
                LOG.warnf("Compile failed: %s", e.getMessage());
                return CompileResponse.fail(
                    java.util.List.of(),
                    "编译失败: " + e.getMessage());
            } finally {
                ANON_PARSE_PERMITS.release();
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /** CompilationResult → CompileResponse（对齐 cloud PolicyCompileResponse 契约）。 */
    private CompileResponse toCompileResponse(CompilationResult result) {
        if (result.isSuccess()) {
            // 成功也带诊断——warn-mode 的 W600 稳定性 warning 随成功返回（前端黄标，ADR 0031）。
            java.util.List<CompileDiagnostic> warnings = result.getDiagnostics().stream()
                .map(CompileDiagnostic::from)
                .toList();
            return CompileResponse.ok(extractModuleInfo(result.getCoreJson()), warnings);
        }
        // 结构化诊断优先（含 1-based 行列 + W600 code/severity）；无位置信息的失败回退到
        // 行列=1 的兜底诊断，保证前端总能拿到至少一条标记。
        java.util.List<CompileDiagnostic> diags;
        if (!result.getDiagnostics().isEmpty()) {
            diags = result.getDiagnostics().stream()
                .map(CompileDiagnostic::from)
                .toList();
        } else {
            diags = result.getErrors().stream()
                .map(msg -> CompileDiagnostic.error(1, 1, msg))
                .toList();
        }
        String error = result.getErrors().isEmpty() ? "编译失败" : result.getErrors().get(0);
        return CompileResponse.fail(diags, error);
    }

    /** 从 Core IR JSON 提取模块概要 {name, functions[], types[]}（best-effort，失败返回 null）。 */
    private CompileResponse.ModuleInfo extractModuleInfo(String coreJson) {
        if (coreJson == null || coreJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                io.aster.common.JacksonMappers.PRETTY.readTree(coreJson);
            String name = root.path("name").asText(null);
            java.util.List<String> functions = new java.util.ArrayList<>();
            root.path("functions").forEach(f -> {
                String fn = f.path("name").asText(null);
                if (fn != null) functions.add(fn);
            });
            java.util.List<String> types = new java.util.ArrayList<>();
            root.path("types").forEach(t -> {
                String tn = t.path("name").asText(null);
                if (tn != null) types.add(tn);
            });
            if (name == null && functions.isEmpty() && types.isEmpty()) {
                return null;
            }
            return new CompileResponse.ModuleInfo(name, functions, types);
        } catch (Exception e) {
            LOG.debugf("模块概要提取失败（不影响编译成功判定）: %s", e.getMessage());
            return null;
        }
    }

    /** 匿名 compile 端点的 aliasSet 大小上限（防绕过 16KB 源码限制的成本控制）。 */
    private static final int MAX_ALIAS_KINDS = 64;
    private static final int MAX_ALIASES_PER_KIND = 64;
    private static final int MAX_ALIAS_PHRASE_LENGTH = 256;
    private static final int MAX_ALIAS_KIND_LENGTH = 128;

    /**
     * 校验 aliasSet 大小边界。超限返回错误消息（调用方回 400），否则返回 null。
     * 源码有 @Size 16KB，但 aliasSet 是独立字段不受约束——单独限 kind 数/每 kind
     * 别名数/单别名长度/kind key 长度，防负载塞进 aliasSet 绕过成本控制。
     */
    private static String validateAliasSetBounds(Map<String, java.util.List<String>> aliasSet) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return null;
        }
        if (aliasSet.size() > MAX_ALIAS_KINDS) {
            return "别名 kind 数超过上限（最多 " + MAX_ALIAS_KINDS + "）";
        }
        for (var e : aliasSet.entrySet()) {
            if (e.getKey() != null && e.getKey().length() > MAX_ALIAS_KIND_LENGTH) {
                return "别名 kind 名长度超过上限（最多 " + MAX_ALIAS_KIND_LENGTH + " 字符）";
            }
            java.util.List<String> phrases = e.getValue();
            if (phrases == null) {
                continue;
            }
            if (phrases.size() > MAX_ALIASES_PER_KIND) {
                return "单个 kind 的别名数超过上限（最多 " + MAX_ALIASES_PER_KIND + "）";
            }
            for (String p : phrases) {
                if (p != null && p.length() > MAX_ALIAS_PHRASE_LENGTH) {
                    return "别名短语长度超过上限（最多 " + MAX_ALIAS_PHRASE_LENGTH + " 字符）";
                }
            }
        }
        return null;
    }

    /**
     * 请求 aliasSet Map → JSON 串（供 PolicyCompiler）。null/空 → null（无别名）。
     * 序列化失败**上抛**（fail-closed）——不静默降级为无别名，否则依赖别名的源码
     * 会被误判为解析错误。调用方 catch 后返回编译失败。
     */
    private String serializeAliasSet(Map<String, java.util.List<String>> aliasSet) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return null;
        }
        try {
            return io.aster.common.JacksonMappers.PRETTY.writeValueAsString(aliasSet);
        } catch (Exception e) {
            throw new IllegalStateException("aliasSet 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 尝试在 {@link #ANON_PARSE_ACQUIRE_TIMEOUT_MS} 窗口内获取匿名解析许可。
     * 返回 false 表示占满——调用方应以 503 + Retry-After 拒绝。
     */
    private static boolean tryAcquireAnonParse() {
        try {
            return ANON_PARSE_PERMITS.tryAcquire(
                ANON_PARSE_ACQUIRE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 准备上下文数组（统一处理 List、数组、Map 等格式）
     */
    private Object[] prepareContextArray(Object context) {
        if (context instanceof List<?> list) {
            return list.toArray();
        } else if (context instanceof Object[] arr) {
            return arr;
        } else if (context instanceof Map) {
            return new Object[] { context };
        } else {
            return new Object[] { context };
        }
    }

    /**
     * 批量评估多个策略
     *
     * POST /api/policies/evaluate/batch
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "requests": [{ "policyModule": "...", "policyFunction": "...", "context": [...] }, ...] }
     */
    @POST
    @Path("/evaluate/batch")
    public Uni<BatchEvaluationResponse> evaluateBatch(@Valid BatchEvaluationRequest request) {
        // batch 按"批中每条都算一次调用"扣配额：在 quota 检查之外，结果落地时按条 recordApiCall
        enforceApiQuota("/api/v1/policies/evaluate/batch");
        // 跨线程预捕获，必须在进入 Uni 之前——理由见 RequestIdentity。
        RequestIdentity identity = captureIdentity();
        String tenantId = identity.tenantId();
        String performedBy = identity.performedBy();
        String apiKeyId = identity.apiKeyId();
        long startTime = System.currentTimeMillis();

        LOG.infof("Batch evaluating %d policies for tenant %s", request.requests().size(), tenantId);

        // 转换为内部批量请求格式
        List<BatchRequest> batchRequests = request.requests().stream()
            .map(req -> new BatchRequest(
                tenantId,
                req.policyModule(),
                req.policyFunction(),
                req.context()
            ))
            .toList();

        return evaluationService.evaluateBatchWithFailures(batchRequests)
            .map(batchResult -> {
                long totalExecutionTime = System.currentTimeMillis() - startTime;

                // 转换为REST响应格式
                List<EvaluationResponse> responses = new ArrayList<>();

                // 添加成功的结果
                for (var attempt : batchResult.getSuccesses()) {
                    long execTime = (long) attempt.getResult().getExecutionTimeMs();

                    // 记录成功指标
                    policyMetrics.recordEvaluation(
                        attempt.getPolicyModule(),
                        attempt.getPolicyFunction(),
                        execTime,
                        true
                    );

                    // 记录业务指标
                    if ("aster.finance.loan".equals(attempt.getPolicyModule())) {
                        recordLoanDecision(attempt.getResult().getResult());
                    }
                    businessMetrics.recordPolicyEvaluation();

                    responses.add(EvaluationResponse.success(
                        attempt.getResult().getResult(),
                        execTime
                    ));
                    recordApiCall("/api/v1/policies/evaluate/batch", "success", execTime, tenantId, performedBy, apiKeyId);
                }

                // 添加失败的结果
                for (var attempt : batchResult.getFailures()) {
                    long execTime = totalExecutionTime / batchRequests.size(); // 估算

                    // 记录失败指标
                    policyMetrics.recordEvaluation(
                        attempt.getPolicyModule(),
                        attempt.getPolicyFunction(),
                        execTime,
                        false
                    );

                    responses.add(EvaluationResponse.error(attempt.getError()));
                    recordApiCall("/api/v1/policies/evaluate/batch", "api_error", execTime, tenantId, performedBy, apiKeyId);
                }

                LOG.infof("Batch evaluation completed in %dms: %d success, %d failures",
                    totalExecutionTime, batchResult.getSuccessCount(), batchResult.getFailureCount());

                return new BatchEvaluationResponse(
                    responses,
                    totalExecutionTime,
                    batchResult.getSuccessCount(),
                    batchResult.getFailureCount()
                );
            });
    }

    /**
     * 验证策略是否存在且可调用
     *
     * POST /api/policies/validate
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility" }
     */
    @POST
    @Path("/validate")
    @AnonymousAllowed  // 只读元数据：豁免类级 @RequireRole，对未认证调用方开放
    public Uni<ValidationResponse> validate(@Valid ValidationRequest request) {
        LOG.infof("Validating policy: %s.%s", request.policyModule(), request.policyFunction());

        // 并发闸：validate 会查库并（在无预编译产物时，即绝大多数版本的常态）
        // 动态编译策略——CPU 密集。匿名端点必须限并发，与 /schema 共用同一许可池，
        // 占满则快速 503 + Retry-After，防突发请求拖垮 worker 池。
        if (!tryAcquireAnonParse()) {
            return Uni.createFrom().failure(
                new jakarta.ws.rs.ServiceUnavailableException(
                    jakarta.ws.rs.core.Response.status(503)
                        .header("Retry-After", "1")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of(
                            "error", "validate_busy",
                            "message", "Validation service is busy; please retry."))
                        .build()));
        }

        // acquire 之后到 .eventually 挂上之前的任何同步异常（理论上 validatePolicy
        // 当前返回 Uni.createFrom().item 不会同步抛，但写法须防御式）都必须释放许可，
        // 否则永久泄漏。try/catch 兜底：构链阶段抛异常即就地释放并上抛。
        try {
            return evaluationService.validatePolicy(
                    request.policyModule(),
                    request.policyFunction()
            )
            .map(result -> {
                if (result.isValid()) {
                    LOG.infof("Policy validation successful: %s.%s", request.policyModule(), request.policyFunction());
                    return ValidationResponse.success(
                        result.getParameters() != null ? result.getParameters().size() : 0,
                        result.getReturnType()
                    );
                } else {
                    LOG.warnf("Policy validation failed: %s.%s - %s", request.policyModule(), request.policyFunction(), result.getMessage());
                    return ValidationResponse.failure(result.getMessage());
                }
            })
            // 无论 item/failure/cancellation，都释放许可（eventually 等价于
            // onTermination().invoke）。显式 Runnable lambda：方法引用会与
            // eventually(Supplier<Uni>) 重载歧义。
            .eventually(() -> ANON_PARSE_PERMITS.release());
        } catch (Throwable t) {
            ANON_PARSE_PERMITS.release();
            throw t;
        }
    }

    /**
     * 清除策略缓存
     *
     * DELETE /api/policies/cache
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility" } (both fields optional)
     */
    @DELETE
    @Path("/cache")
    public Uni<CacheClearResponse> clearCache(@Valid CacheClearRequest request) {
        String tenantId = tenantId();

        LOG.infof("Clearing cache for tenant %s: module=%s, function=%s",
            tenantId, request.policyModule(), request.policyFunction());

        return evaluationService.invalidateCache(
                tenantId,
                request.policyModule(),
                request.policyFunction()
        )
        .map(v -> {
            String message = String.format("Cache cleared for tenant %s", tenantId);
            if (request.policyModule() != null) {
                message += ", module=" + request.policyModule();
            }
            if (request.policyFunction() != null) {
                message += ", function=" + request.policyFunction();
            }
            LOG.infof(message);
            return CacheClearResponse.success(message);
        })
        .onFailure().recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Failed to clear cache for tenant %s", tenantId);
            return CacheClearResponse.failure(throwable.getMessage());
        });
    }


    // R31-1：publishPolicyEvaluationEvent + buildEvaluationMetadata 提取到
    // PolicyAuditPublisher。这里保留 thin pass-through 避免一次性改全部 13
    // 个调用点，让 diff 集中在新建文件上，行为零变化。后续 R31-1.2 时
    // 直接把每个 call site 替换为 auditPublisher.publish(...) 即可移除
    // 这两个 thin wrapper，再减 ~25 行。
    private void publishPolicyEvaluationEvent(
        String tenantId,
        EvaluationRequest request,
        String performedBy,
        boolean success,
        long executionTimeMs,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        auditPublisher.publish(tenantId, request, performedBy,
            success, executionTimeMs, errorMessage, metadata);
    }

    private Map<String, Object> buildEvaluationMetadata(EvaluationRequest request) {
        return auditPublisher.buildMetadata(request);
    }

    /**
     * 一次请求的调用方身份快照（issue #174）。
     *
     * <p><b>存在的理由是线程边界，不是"减少重复"。</b>评估在 worker pool 上完成，
     * 回调里 {@code routingContext} / {@code RequestScoped} 已失效——三者必须在进入
     * {@code Uni} 之前抓好。漏抓 {@code apiKeyId} 时 {@code recordApiCall} 会读到 null
     * 或抛 {@code ContextNotActiveException} 并被 Mutiny 静默 drop，表现为**配额与计费
     * 计数无声丢失**（这个坑已经踩过一次，见各端点原注释）。
     *
     * <p>此前 4 个端点各自手抄这三行 + 一段解释注释，抄漏一行不会有任何编译或测试报错。
     * 收敛成一次 {@link #captureIdentity()} 调用后，"必须预捕获"这条约束由**类型**表达：
     * 拿不到 {@code RequestIdentity} 就没有 tenantId 可用。
     *
     * <p>刻意**不**把 quota/metrics/计时也塞进来——那些各端点确有差异
     * （如 evaluate-source 还有 permit 闸门与 onTermination 释放），强行统一会抹掉
     * 端点特有的语义。这里只收敛真正逐字相同的那部分。
     */
    private record RequestIdentity(String tenantId, String performedBy, String apiKeyId) {}

    /**
     * 在**请求线程**上捕获调用方身份，供跨线程回调使用。
     *
     * <p>必须在返回 {@code Uni} 之前调用。
     */
    private RequestIdentity captureIdentity() {
        return new RequestIdentity(tenantId(), performedBy(), apiKeyIdFromContext());
    }

    /**
     * 提取租户ID
     *
     * 从 X-Tenant-Id 请求头提取租户ID，如果不存在则返回 "default"
     */
    private String tenantId() {
        return identityResolver.tenantId();
    }

    /**
     * R32 hotfix v3: 读 API-key 上下文。filter 写在 jaxrsCtx.property，比
     * Vert.x header 更可靠。Header 是回退路径（trial endpoint / 老调用方）。
     */
    private String apiKeyIdFromContext() {
        return identityResolver.apiKeyId();
    }

    private String performedBy() {
        return identityResolver.performedBy();
    }

    /**
     * 同步检查 API 配额；命中即抛 WebApplicationException
     * 写入 X-Quota-Limit / Remaining / Reset / Warning 响应头（API-5）
     *
     * 已用 == 0 而 limit == 0 时仍会写 0/0 的响应头，给客户端清晰信号"plan 不开放"
     */
    private ApiQuotaGuard.GuardResult enforceApiQuota(String endpointPath) {
        String tenantId = tenantId();
        String userId = performedBy();

        // R29++→R30 quota bypass 三重校验：
        //   path == TRIAL_PATH && guard 凭证 == true && tenantId == "trial"
        // path + property 通过共享的 TrialBypassPredicate 完成；tenantId 单独
        // 校验，确认 resource 层身份与 guard 链路真的同源。三者同时满足才
        // 跳过 PlanGate；任何一项不满足 → 走原路径。
        //
        // TrialEndpointGuard 已经做了成本控制（Origin + body cap + per-IP
        // limiter + concurrent semaphore），叠加 PlanGate 会把 "trial" 当成
        // 未签约租户 403。
        boolean guardedTrial = io.aster.policy.security.TrialBypassPredicate
            .isGuardedTrialPath(
                endpointPath,
                jaxrsCtx == null ? null : (Boolean) jaxrsCtx.getProperty(
                    io.aster.policy.security.TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP));
        if (guardedTrial && "trial".equals(tenantId)) {
            return new ApiQuotaGuard.GuardResult(
                ApiQuotaGuard.Verdict.ALLOW, -1L, 0L, 0, null);
        }

        ApiQuotaGuard.GuardResult result = apiQuotaGuard.check(tenantId, userId);

        // 写响应头（API-5）
        if (routingContext != null && routingContext.response() != null) {
            String limitStr = result.limit() == -1 ? "unlimited" : String.valueOf(result.limit());
            routingContext.response().putHeader("X-Quota-Limit", limitStr);
            if (result.limit() != -1) {
                long remaining = Math.max(0, result.limit() - result.used());
                routingContext.response().putHeader("X-Quota-Remaining", String.valueOf(remaining));
                routingContext.response().putHeader("X-Quota-Reset", String.valueOf(monthStartUnix()));
            }
            if (result.warning() != null) {
                routingContext.response().putHeader("X-Quota-Warning", result.warning());
            }
        }

        switch (result.verdict()) {
            case FORBIDDEN -> throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of(
                        "error", "api_access_denied",
                        "message", "Free 计划不包含 Policy Execution API。请升级到 Pro/Team 或试用 Trial。"
                    ))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
            case RATE_LIMITED -> throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(429)
                    .entity(Map.of(
                        "error", "api_quota_hard_exceeded",
                        "message", "本月 API 调用已超 200% 上限，已拒绝。请升级套餐或联系客服。",
                        "limit", result.limit(),
                        "used", result.used()
                    ))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
            default -> { /* ALLOW: 即使 soft warn 也继续 */ }
        }

        // API-7: per-API-key per-second 限流（API key 命中 verifier 后生效）
        String apiKeyId = apiKeyIdFromContext();
        if (apiKeyId != null && !apiKeyId.isBlank()) {
            ApiQuotaGuard.RateCheck rate = apiQuotaGuard.checkRate(tenantId, apiKeyId);
            if (routingContext != null && routingContext.response() != null) {
                routingContext.response().putHeader("X-RateLimit-Limit", String.valueOf(rate.limit()));
                routingContext.response().putHeader("X-RateLimit-Remaining",
                    String.valueOf(Math.max(0, rate.limit() - rate.used())));
            }
            if (!rate.allowed()) {
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(429)
                        .header("Retry-After", "1")
                        .entity(Map.of(
                            "error", "rate_limit_exceeded",
                            "message", "Per-API-key per-second 限流：超过 " + rate.limit() + " RPS",
                            "rps_limit", rate.limit(),
                            "rps_used", rate.used()
                        ))
                        .type(MediaType.APPLICATION_JSON)
                        .build()
                );
            }
        }
        return result;
    }

    /**
     * 异步记录一次 API 调用（fire-and-forget）。
     *
     * 仅可在 RequestScoped 仍然有效时调用（同步路径 / 主 event-loop 线程）。
     * 若调用点已切换到 worker pool / Uni lambda，RequestScoped 已失效，
     * 必须改用下面的 {@link #recordApiCall(String, String, long, String, String, String)}
     * 重载并把 tenant/user/apiKey 预先捕获后传入。
     */
    private void recordApiCall(String endpointPath, String status, long latencyMs) {
        String tenantId = tenantId();
        String userId = performedBy();
        String apiKeyId = apiKeyIdFromContext();
        apiQuotaGuard.recordAsync(userId, tenantId, apiKeyId, endpointPath, status, latencyMs);
    }

    /**
     * 异步路径专用：tenant/user/apiKey 必须在跨线程跳板前预捕获，
     * 否则 routingContext 会在 worker thread 上触发
     * ContextNotActiveException，被 Mutiny drop 后 quota 计数静默丢失。
     */
    private void recordApiCall(String endpointPath, String status, long latencyMs,
                               String tenantId, String userId, String apiKeyId) {
        apiQuotaGuard.recordAsync(userId, tenantId, apiKeyId, endpointPath, status, latencyMs);
    }

    private static long monthStartUnix() {
        java.time.YearMonth ym = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        return ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
    }

    /**
     * 记录贷款决策指标
     *
     * 根据策略结果判断是批准还是拒绝，并记录相应指标
     *
     * @param result 策略评估结果
     */
    private void recordLoanDecision(Object result) {
        if (result == null) {
            return;
        }

        // 检查结果是否表示批准
        // 支持多种结果格式：
        // 1. Boolean 值
        // 2. 包含 "approved" 字段的对象
        // 3. 字符串 "APPROVED" / "REJECTED"
        boolean approved = false;

        if (result instanceof Boolean boolResult) {
            approved = boolResult;
        } else if (result instanceof String strResult) {
            approved = "APPROVED".equalsIgnoreCase(strResult) || "true".equalsIgnoreCase(strResult);
        } else {
            // 尝试通过反射检查 approved 字段
            try {
                var method = result.getClass().getMethod("isApproved");
                if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                    approved = (Boolean) method.invoke(result);
                }
            } catch (Exception e) {
                // 无法确定批准状态，不记录
                return;
            }
        }

        if (approved) {
            policyMetrics.recordLoanApproval();
        } else {
            policyMetrics.recordLoanRejection();
        }
    }

}
