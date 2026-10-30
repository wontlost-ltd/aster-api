package io.aster.billing;

import io.aster.security.internal.InternalCallSigner;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Policy Execution API 的配额守门
 *
 * 三档行为（按 PM 取舍）：
 *   - 0%–100%   ：放行
 *   - 100%–110% ：放行 + soft warn 响应头（X-Quota-Warning）
 *   - 110%–200% ：放行（保 SLA），但记录用于后续催升级
 *   - >200%     ：429 hard reject
 *   - apiCallsLimit==0  → 直接 403（Free 用户）
 *   - apiCallsLimit==-1 → 永远放行（Enterprise）
 *
 * 调用次数记录由 recordAsync(...) 异步走，不阻塞热路径。
 *
 * <p>线程模型（#393）：
 * <ul>
 *   <li>{@link #check}（月度配额）在 Vert.x event-loop 上同步调用，只读进程内视图——视图由
 *       {@link io.aster.billing.snapshot.LocalQuotaSnapshotService#peekOwner} 异步从 Redis 加载；</li>
 *   <li>{@link #checkRateAsync}（每秒限流）返回 {@code Uni}，由调用方组合进请求链：裁决来自 Redis
 *       原子 INCR 的回包，热路径不阻塞；{@link #checkRate} 是同一逻辑的同步版，仅供 worker 线程。</li>
 * </ul>
 *
 * <p>200% 硬上限不是原子封顶：check 读用量与成功后的 INCR 分离，并发中的请求可能同时看到
 * 「未超限」而全部放行，超出量以「判定时已在途的请求数」为上界，此后的 check 立即 429。
 * 这与产品口径一致——100%~200% 本就是放行区间，硬上限防的是持续滥用而非单次并发尖峰。
 */
@ApplicationScoped
public class ApiQuotaGuard {

    private static final Logger LOG = Logger.getLogger(ApiQuotaGuard.class);

    public enum Verdict {
        /** 放行（含未超限 / soft warn / overage 都标 ALLOW，由 detail 区分） */
        ALLOW,
        /** Free 档不开放 API */
        FORBIDDEN,
        /** 超 200% 月度配额 hard reject */
        RATE_LIMITED,
        /** per-second 限流（短期高频）— 客户端应该 retry-after */
        RATE_LIMITED_PER_SECOND
    }

    public record GuardResult(Verdict verdict, long limit, long used, int percent, String warning) {
        public boolean allowed() { return verdict == Verdict.ALLOW; }
    }

    @Inject
    PlanGateService planGateService;

    @Inject
    PlanGateConfig config;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    @Inject
    io.aster.common.http.SharedHttpClient sharedHttpClient;

    @Inject
    io.aster.billing.snapshot.LocalQuotaSnapshotService snapshot;

    @Inject
    ApiKeyRateLimiter rateLimiter;
    // P0-R19: WebClient DCL consolidated into SharedWebClient

    /**
     * R32 hotfix v3: precheck cold-path 原来用 Vert.x WebClient + future.get(10s)
     * 在 event-loop 上等回调，回调本身要等 event-loop 调度 → 10s 超时 → fail-open。
     * 客户端每次 cache miss 都要白等 10s，把 50ms 的策略执行放大成 11s 总耗时。
     *
     * 修复策略（HMAC + fail-open 语义不变，只换交付）：
     *   1. {@link Caffeine} 进程内缓存 PrecheckResult 5 min — 同一用户 5min 内不
     *      重复 fetch，把 cf round-trip 从每次 evaluate 摊销到几乎 0。
     *   2. {@link java.net.http.HttpClient}（{@link io.aster.common.http.SharedHttpClient}
     *      共享单例）替代 WebClient，{@code send()} 同步阻塞 + 复用 keep-alive
     *      连接 + TLS session resumption。
     *   3. cache miss 时立即 fail-open，precheck 丢到 4-worker pool 后台预热。
     *   4. precheck timeout 收紧到 1.5s（fail-open 是兜底，cf 边缘正常 ~200ms，
     *      等 10s 比立刻 fail-open 更糟）。
     */
    Cache<String, PrecheckResult> precheckCache;
    private ExecutorService precheckPool;
    private static final java.time.Duration PRECHECK_CACHE_TTL = java.time.Duration.ofMinutes(5);
    private static final int PRECHECK_CACHE_MAX = 10_000;
    /** precheck 单次最长等待。cf 正常 ~200ms；超过 1.5s 直接 fail-open 比死等更友好。 */
    private static final java.time.Duration PRECHECK_TIMEOUT = java.time.Duration.ofMillis(1500);

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        precheckCache = Caffeine.newBuilder()
            .maximumSize(PRECHECK_CACHE_MAX)
            .expireAfterWrite(PRECHECK_CACHE_TTL)
            .build();
        precheckPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "quota-precheck-worker");
            t.setDaemon(true);
            return t;
        });
        LOG.info("ApiQuotaGuard started: precheckTtl=" + PRECHECK_CACHE_TTL
            + ", timeout=" + PRECHECK_TIMEOUT);
    }

    void onStop(@jakarta.enterprise.event.Observes io.quarkus.runtime.ShutdownEvent ev) {
        if (precheckPool != null) {
            precheckPool.shutdown();
            try {
                if (!precheckPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    precheckPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                precheckPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 同步检查（纯内存，event-loop 安全）：本地 snapshot 视图优先
     *
     * SNAP-5 改造：
     *   1. 先查进程内配额视图（由 Redis user snapshot + 月度计数异步加载，命中 → 0 RTT）
     *   2. miss → lazy fetch cloud /precheck + 写 redis（首次 + 降级路径）
     *   3. 月度计数取 redis counter:user:{id}:m:{period}（双写真源），本副本 INCR 回包即时回写
     *
     * <p>ADR 0015 §3：快照、计数器、precheck 与 Caffeine 全部按 {@code quotaOwnerId} 取键——
     * 个人 key 即调用者 userId，团队 key 为 team owner，成员调用共用 owner 的月度池。
     */
    public GuardResult check(String tenantId, String quotaOwnerId) {
        // SNAP-9: 在当前 span 上打 tag，让 trace 在 Tempo 里能按配额归属筛选。
        // aster.user_id 保留写同一值，避免既有看板按该属性筛选时断档。
        Span currentSpan = Span.current();
        if (currentSpan != null && quotaOwnerId != null) {
            currentSpan.setAttribute("aster.quota_owner_id", quotaOwnerId);
            currentSpan.setAttribute("aster.user_id", quotaOwnerId);
            currentSpan.setAttribute("aster.tenant_id", tenantId == null ? "" : tenantId);
        }
        // PlanGate 关闭时（aster.plan-gate.enabled=false，e.g. on-prem
        // 无 cloud BFF 或 loadtest 环境），整个配额体系跳过 — snapshot
        // 拿不到、cloud 拒连，再走下面任何分支只会换来一次 fail-open
        // 加几十毫秒延迟。直接放行 enterprise 等价物。
        if (!config.enabled()) {
            return new GuardResult(Verdict.ALLOW, -1, 0, 0, null);
        }

        long limit;
        long used;
        boolean apiAccessAllowed;
        boolean unlimited;
        java.util.Optional<io.aster.billing.snapshot.LocalQuotaSnapshotService.LocalQuota> local =
            snapshot.peekOwner(quotaOwnerId);

        if (local.isPresent()) {
            if (currentSpan != null) {
                currentSpan.setAttribute("aster.quota.source", "local");
            }
            // Hot path: redis 命中
            io.aster.billing.snapshot.UserSnapshot s = local.get().user();
            limit = s.apiCallsLimit();
            apiAccessAllowed = s.apiAccessAllowed();
            unlimited = s.unlimitedApi();
            // used 取本地 Redis 计数器与快照基线的最大值（#412）。本地计数只含经本服务的直连调用；
            // cloud REST 调用写入同一 owner 池（cloud#652）却不经过本地计数器，Redis 冷启动 / 计数丢失
            // 后本地计数也会从 0 重新累积。快照携带 cloud 统计的 owner 池当月合并用量作基线，
            // 取最大值（而非相加，基线已含本服务上报的直连调用），使 200% 硬上限按合并总量生效。
            // 基线随快照推送 / 每小时对账 / precheck 预热刷新，滞后量以刷新间隔内的调用为上界。
            used = s.effectiveUsed(local.get().used(),
                io.aster.billing.snapshot.LocalQuotaSnapshotService.currentPeriod());
        } else {
            // R32 hotfix v3: 先查进程内 Caffeine（5min TTL），命中即 0 RTT。
            PrecheckResult cached = precheckCache == null ? null
                : precheckCache.getIfPresent(quotaOwnerId == null ? "" : quotaOwnerId);
            // monthlyUsed 只在其所属账期内有效：缓存最长 5 分钟，跨月后的条目按未命中处理，
            // 不能把上月计数沿用到新月。
            if (cached != null && !io.aster.billing.snapshot.LocalQuotaSnapshotService.currentPeriod().equals(cached.period)) {
                cached = null;
            }
            if (cached != null) {
                if (currentSpan != null) {
                    currentSpan.setAttribute("aster.quota.source", "local_caffeine");
                }
                limit = cached.apiCallsLimit;
                used = cached.monthlyUsed;
                apiAccessAllowed = limit != 0;
                unlimited = limit == -1;
            } else {
                // Cache miss + redis miss。Hot path 不能阻塞 event-loop 等 cf 回包
                // （旧实现等 10s 然后 fail-open，把 50ms 策略放大成 11s）。
                //
                // 安全模型：API key 已经被 {@link ApiKeyVerifierService} 验证过
                // （cf authoritative，会 reject 已撤销 / 过期 / Free-plan-上禁用的 key）。
                // 此处的 precheck 只是配额计量门控（soft warn at 80% / hard reject
                // at 200%），首次调用 fail-open 不会让"未授权"用户进来。
                //
                // 策略：立刻 fail-open 返回 ALLOW，同时把真正的 fetchPrecheck 丢
                // 到 worker pool 上跑、结果写进 Caffeine + redis snapshot，下次
                // 同 quotaOwnerId 命中 (< 5min TTL) 即走快路径，配额执行恢复全保真。
                if (currentSpan != null) {
                    currentSpan.setAttribute("aster.quota.source", "fail_open_warming");
                }
                triggerAsyncPrefetch(quotaOwnerId);
                PlanInfo fallback = PlanInfo.failOpen();
                return new GuardResult(Verdict.ALLOW, fallback.apiCallsLimit(), 0, 0, null);
            }
        }

        // Free 档（apiCallsLimit == 0）→ 403
        if (!apiAccessAllowed) {
            return new GuardResult(Verdict.FORBIDDEN, 0, 0, 0, null);
        }

        // Enterprise（-1）→ 永远放行
        if (unlimited) {
            return new GuardResult(Verdict.ALLOW, -1, 0, 0, null);
        }
        int percent = (int) ((used * 100) / Math.max(limit, 1));

        if (used >= limit * 2) {
            // 超 200% hard reject
            return new GuardResult(Verdict.RATE_LIMITED, limit, used, percent, null);
        }
        if (used >= limit) {
            // 100%-200% soft warn
            String warn = used >= (long) (limit * 1.1)
                ? "soft-overage"
                : "approaching-limit";
            return new GuardResult(Verdict.ALLOW, limit, used, percent, warn);
        }
        if (percent >= 80) {
            return new GuardResult(Verdict.ALLOW, limit, used, percent, "approaching-limit");
        }
        return new GuardResult(Verdict.ALLOW, limit, used, percent, null);
    }

    /**
     * 异步检查 per-API-key per-second 限流（event-loop 安全，调用方须组合进请求 Uni）。
     *
     * <p>#393 之前每个请求都把 cloud {@code /api/internal/api/rate-check} 的同步 HTTP 丢进
     * 4 线程池，再在 event-loop 上 {@code Future.get(1s)} 等结果：既阻塞 event-loop，又把
     * 每个 evaluate 绑上 ~100ms 出站 RTT。现由 {@link ApiKeyRateLimiter} 按同一算法在本服务
     * Redis 上原子计数裁决。
     *
     * <p>plan 取值（不阻塞）：租户 plan 缓存 → 配额 owner 的本地快照 → 都未知则本次 fail-open
     * （与旧实现「plan 查询失败即放行」一致，后台预热完成后下一次请求即按真实档位限流）。
     * 不能拿 {@code lookupPlan} 的 fail-open 值（pro）去限流：那会把 enterprise 冷启动限成 10 RPS。
     * Free 档不靠这里拦截——月度 {@link #check} 对 apiCallsLimit==0 直接 403。
     */
    public io.smallrye.mutiny.Uni<RateCheck> checkRateAsync(String tenantId, String quotaOwnerId, String apiKeyId) {
        if (!config.enabled() || apiKeyId == null || apiKeyId.isBlank()) {
            return io.smallrye.mutiny.Uni.createFrom().item(RateCheck.UNCHECKED);
        }
        String plan = knownPlan(tenantId, quotaOwnerId);
        if (plan == null) {
            return io.smallrye.mutiny.Uni.createFrom().item(RateCheck.UNCHECKED);
        }
        return resumeOnCaller(rateLimiter.acquire(apiKeyId, plan));
    }

    /**
     * 让裁决结果回到调用方的 Vert.x（duplicated）上下文再向下游发射。
     *
     * <p>Redis 回包通常本就在该上下文，但超时分支由 Mutiny 的定时线程触发；若不切回，调用方
     * 后续的 chain（读 routingContext / 请求作用域 bean、抢 evaluate-source 许可）会跑在
     * 非请求线程上。assembly 时不在 Vert.x 上下文（单测 / worker）则原样返回。
     */
    static <T> io.smallrye.mutiny.Uni<T> resumeOnCaller(io.smallrye.mutiny.Uni<T> uni) {
        io.vertx.core.Context caller = io.vertx.core.Vertx.currentContext();
        if (caller == null) {
            return uni;
        }
        return uni.emitOn(task -> {
            if (io.vertx.core.Vertx.currentContext() == caller) {
                task.run();
            } else {
                caller.runOnContext(ignored -> task.run());
            }
        });
    }

    /**
     * 同步版每秒限流——<b>仅限 worker 线程</b>（如返回 {@code Response} 的 GuardDecisionResource）：
     * worker 上 {@code lookupPlan} 可同步取到真实 plan，限流裁决本身带短超时与本地兜底，不会久等。
     */
    public RateCheck checkRate(String tenantId, String apiKeyId) {
        if (!config.enabled() || apiKeyId == null || apiKeyId.isBlank()) {
            return RateCheck.UNCHECKED;
        }
        String plan;
        try {
            plan = planGateService.lookupPlan(tenantId).plan();
        } catch (Exception e) {
            return RateCheck.UNCHECKED;
        }
        return rateLimiter.acquire(apiKeyId, plan).await().atMost(java.time.Duration.ofSeconds(2));
    }

    /** 非阻塞取已确认的 plan：租户缓存优先，其次配额 owner 快照；都未知返回 null。 */
    private String knownPlan(String tenantId, String quotaOwnerId) {
        try {
            return planGateService.peekPlan(tenantId).map(PlanInfo::plan)
                .or(() -> snapshot.peekOwner(quotaOwnerId).map(q -> q.user().plan()))
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public record RateCheck(boolean allowed, int used, int limit) {
        /** 未参与限流（plan gate 关闭 / 无 key / plan 未知时 fail-open）。 */
        public static final RateCheck UNCHECKED = new RateCheck(true, 0, 0);

        /**
         * plan 本身不开放 API（free，RPS 上限 0）：语义是「无权访问」而不是「太快」，
         * 调用方必须映射为与月度配额 FORBIDDEN 相同的 403，而不是 429。
         * 发生在月度快照与 precheck 都未命中（check 已 fail-open）而租户 plan 缓存已知为 free 时。
         */
        public boolean forbidden() {
            return !allowed && limit == 0;
        }
    }

    /**
     * 异步记录一次调用 — fire-and-forget，不阻塞业务路径
     *
     * <p>{@code quotaOwnerId} 决定计入哪个月度池（本地计数器与 cloud 落库的归属），
     * {@code userId} 仍是实际调用成员（审计与用量明细）。个人 key 两者相同；
     * {@code quotaOwnerId} 为 null 时按 userId 计数，cloud 侧同样缺省回退 userId（ADR 0015 §4）。
     */
    public void recordAsync(String quotaOwnerId, String userId, String tenantId, String apiKeyId,
                             String endpointPath, String status, long latencyMs) {
        // PlanGate 关闭：跳过本地计数 + cloud 异步上报。无 cloud 接收方时
        // 这两步纯粹是 DNS lookup + connection refused 的开销。
        if (!config.enabled()) {
            return;
        }
        // SNAP-5 双写步骤 1：本地 redis INCR（响应式 fire-and-forget，回包回写本地视图）
        // 仅 success 计数（与 cloud apiCallRecords 表 status='success' 过滤一致）。
        // 计数器按配额归属累加，必须与 check() 读取的键一致，否则团队 key 的用量永远读不到。
        String counterKey = quotaOwnerId != null ? quotaOwnerId : userId;
        if ("success".equals(status) && counterKey != null) {
            try {
                snapshot.incrementCounter(counterKey).subscribe().with(ignored -> { });
            } catch (Exception e) {
                LOG.warnf("local counter incr failed quotaOwnerId=%s: %s", counterKey, e.getMessage());
            }
        }

        // SNAP-5 双写步骤 2：异步推 cloud（fire-and-forget，dashboard 显示用 + 跨 pod 对账）
        postUsage(usageBody(quotaOwnerId, userId, tenantId, apiKeyId, endpointPath, status, latencyMs));
    }

    /**
     * 签名并异步发送 usage 上报体（fire-and-forget），任何异常只记日志、不影响业务路径。
     * 包可见：单测在此打桩并捕获 {@link #recordAsync} 组装的上报体，锁定 owner / 成员字段不被互换。
     */
    void postUsage(JsonObject body) {
        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            int port = baseUri.getPort() == -1
                ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
                : baseUri.getPort();
            boolean ssl = "https".equals(baseUri.getScheme());
            String path = "/api/internal/api/usage";

            // v2 签名：绑定 nonce + bodyHash（见 InternalCallSigner）
            var signed = config.hmacKey()
                .map(k -> InternalCallSigner.sign(k, "POST", path, body.encode()))
                .orElse(new InternalCallSigner.Signed(
                    String.valueOf(System.currentTimeMillis() / 1000), "", ""));
            String timestamp = signed.timestamp();
            String signature = signed.signature();

            getClient()
                .post(port, baseUri.getHost(), path)
                .ssl(ssl)
                .timeout(config.requestTimeout().toMillis())
                .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
                .putHeader("X-Aster-Signature", signature)
                .putHeader("X-Aster-Nonce", signed.nonce())
                .putHeader("Content-Type", "application/json")
                .sendBuffer(body.toBuffer())
                .onFailure(err -> LOG.warnf("api usage record failed: user=%s, err=%s",
                    body.getString("userId"), err.getMessage()));
        } catch (Exception e) {
            LOG.warnf("api usage record exception: user=%s, err=%s", body.getString("userId"), e.getMessage());
        }
    }

    /**
     * cloud {@code POST /api/internal/api/usage} 的请求体。{@code quotaOwnerId} 为空时 cloud 回退 userId；
     * 旧 cloud 忽略该字段。包可见，供单测锁定字段集合。
     */
    static JsonObject usageBody(String quotaOwnerId, String userId, String tenantId, String apiKeyId,
                                String endpointPath, String status, long latencyMs) {
        return new JsonObject()
            .put("quotaOwnerId", quotaOwnerId)
            .put("userId", userId)
            .put("tenantId", tenantId)
            .put("apiKeyId", apiKeyId)
            .put("endpointPath", endpointPath)
            .put("status", status)
            .put("latencyMs", latencyMs);
    }

    /**
     * AKA-8: 合并查询 — 一次 GET 拿 plan + apiCallsLimit + monthlyUsed
     */
    record PrecheckResult(long apiCallsLimit, long monthlyUsed, String plan, String period) {}

    /**
     * precheck 预热写入的快照：带上 cloud 返回的 owner 池当月合并用量作为基线（#412）。
     * 账期优先取 cloud 响应里统计该用量所用的 {@code period}：月末最后时刻发出的 precheck，
     * 回包时本服务可能已进入新月，若用本地当月会把上月用量标成新月。旧 cloud 不返回时退回本地当月。
     * 包可见，供单测锁定字段。
     */
    static io.aster.billing.snapshot.UserSnapshot snapshotFromPrecheck(
            String quotaOwnerId, String plan, long apiCallsLimit, long monthlyUsed, String period) {
        String baselinePeriod = period == null || period.isBlank()
            ? io.aster.billing.snapshot.LocalQuotaSnapshotService.currentPeriod() : period;
        return new io.aster.billing.snapshot.UserSnapshot(quotaOwnerId, plan, apiCallsLimit, null, null, null,
            monthlyUsed, baselinePeriod);
    }

    /** 限制并发预热：同一 quotaOwnerId 已有 in-flight 任务时不重复提交。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> prefetchInFlight
        = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * R32 hotfix v3: hot path 不能阻塞 event-loop。fail-open 立刻返回后，
     * 把真正的 precheck 丢到 worker pool 上跑，结果写进 Caffeine + redis snapshot。
     * 下一次该 quotaOwnerId 命中即走快路径。
     */
    private void triggerAsyncPrefetch(String quotaOwnerId) {
        if (quotaOwnerId == null || precheckPool == null) return;
        // 防止"同一配额 owner 连续 N 个请求 → N 个 fetch in-flight"打爆 cf 端。
        if (prefetchInFlight.putIfAbsent(quotaOwnerId, Boolean.TRUE) != null) return;
        try {
            precheckPool.submit(() -> {
                try {
                    PrecheckResult pre = fetchPrecheckSync(quotaOwnerId);
                    if (precheckCache != null) {
                        precheckCache.put(quotaOwnerId, pre);
                    }
                    try {
                        snapshot.setUser(snapshotFromPrecheck(
                            quotaOwnerId, pre.plan, pre.apiCallsLimit, pre.monthlyUsed, pre.period));
                    } catch (Exception ignore) {
                        // snapshot 内部已 catch；这里再兜一层
                    }
                } catch (Exception e) {
                    LOG.debugf("async precheck warmup failed quotaOwnerId=%s: %s",
                        quotaOwnerId, e.getMessage());
                } finally {
                    prefetchInFlight.remove(quotaOwnerId);
                }
            });
        } catch (RuntimeException submitFail) {
            prefetchInFlight.remove(quotaOwnerId);
        }
    }

    /**
     * R32 hotfix v3: 用 java.net.http.HttpClient 同步阻塞 send()，不依赖 Vert.x
     * event-loop 调度。共享 HttpClient 实例（{@link io.aster.common.http.SharedHttpClient}）
     * 保留 keep-alive 连接 + TLS session resumption，hot path 实测 ~50ms。
     * timeout 收紧到 {@value #PRECHECK_TIMEOUT_MS}ms — 超过即 fail-open，cf 边缘
     * 正常 ~200ms，1.5s 已经留足缓冲。
     */
    private PrecheckResult fetchPrecheckSync(String quotaOwnerId) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        String path = "/api/internal/api/precheck";
        // 查询参数名沿用 userId（cloud 契约不变），其语义是配额 owner：
        // cloud 按该用户取套餐，并统计其 owner 共享池的当月用量（ADR 0015 §4）。
        String query = "userId=" + urlEncode(quotaOwnerId == null ? "" : quotaOwnerId);
        URI fullUri = baseUri.resolve(path + "?" + query);

        // v2 签名：GET 无 body，bodyHash 取空串的 SHA-256——「没有 body」本身也被签名
        // 覆盖，不能被替换成带 body 的请求。
        var signed = config.hmacKey()
            .map(k -> InternalCallSigner.sign(k, "GET", path, ""))
            .orElse(new InternalCallSigner.Signed(
                String.valueOf(System.currentTimeMillis() / 1000), "", ""));
        String timestamp = signed.timestamp();
        String signature = signed.signature();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(fullUri)
            .timeout(PRECHECK_TIMEOUT)
            .header("X-Aster-Timestamp", String.valueOf(timestamp))
            .header("X-Aster-Signature", signature)
                .header("X-Aster-Nonce", signed.nonce())
            .GET()
            .build();

        HttpResponse<String> resp = sharedHttpClient.client()
            .send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("precheck 返回 " + resp.statusCode());
        }
        JsonObject json = new JsonObject(resp.body());
        return new PrecheckResult(
            json.getLong("apiCallsLimit", 0L),
            json.getLong("monthlyUsed", 0L),
            json.getString("plan", "free"),
            // 旧 cloud 不返回账期时按取回时的本地当月记，与 snapshotFromPrecheck 的回退一致
            json.getString("period", io.aster.billing.snapshot.LocalQuotaSnapshotService.currentPeriod())
        );
    }

    private static final long PRECHECK_TIMEOUT_MS = 1500;

    private WebClient getClient() {
        return sharedWebClient.client();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
