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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
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
     *   3. event-loop 检测：caller 在 vert.x-eventloop-thread-N 上时丢到 4-worker
     *      offload pool 跑，本线程同步 get() 等结果（caller 终归在 RESTEasy 调度，
     *      不在 event loop）。
     *   4. precheck timeout 收紧到 1.5s（fail-open 是兜底，cf 边缘正常 ~200ms，
     *      等 10s 比立刻 fail-open 更糟）。
     */
    private Cache<String, PrecheckResult> precheckCache;
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
     * 同步检查（< 5ms hot path）：本地 redis snapshot 优先
     *
     * SNAP-5 改造：
     *   1. 先查 redis user snapshot（命中 → 0 cloud RTT）
     *   2. miss → lazy fetch cloud /precheck + 写 redis（首次 + 降级路径）
     *   3. 月度计数从 redis counter:user:{id}:m:{period} 读（双写真源）
     */
    public GuardResult check(String tenantId, String userId) {
        // SNAP-9: 在当前 span 上打 tag，让 trace 在 Tempo 里能按用户筛选
        Span currentSpan = Span.current();
        if (currentSpan != null && userId != null) {
            currentSpan.setAttribute("aster.user_id", userId);
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
        java.util.Optional<io.aster.billing.snapshot.UserSnapshot> local = snapshot.getUser(userId);

        if (local.isPresent()) {
            if (currentSpan != null) {
                currentSpan.setAttribute("aster.quota.source", "local");
            }
            // Hot path: redis 命中
            io.aster.billing.snapshot.UserSnapshot s = local.get();
            limit = s.apiCallsLimit();
            apiAccessAllowed = s.apiAccessAllowed();
            unlimited = s.unlimitedApi();
            // used 取本地 Redis 计数器。已知风险（经评审接受）：Redis 冷启动 /
            // counter 丢失 / 迁移后，本地计数器从 0 重新累积，而 cloud 可能已记录
            // 历史用量（如 150%）。此窗口内 ">=200% hard reject" 会失效，直到本地
            // 计数追上。判定为可接受：(1) counter 是用量"计量"而非访问"鉴权"——
            // 真正的访问控制（撤销 / 过期 / plan 禁用）由 ApiKeyVerifierService 的
            // cloud verify 持有；(2) 影响有界（单个重新累积窗口）；(3) 用 cloud
            // monthlyUsed 给本地 counter 播种的修法会引入 double-count 风险，
            // 需更大改动，不在本次范围。如需收紧：UserSnapshot 携带 baseline
            // monthlyUsed，此处取 max(localCounter, baseline)。
            used = snapshot.getCounter(userId);
        } else {
            // R32 hotfix v3: 先查进程内 Caffeine（5min TTL），命中即 0 RTT。
            PrecheckResult cached = precheckCache == null ? null
                : precheckCache.getIfPresent(userId == null ? "" : userId);
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
                // 同 userId 命中 (< 5min TTL) 即走快路径，配额执行恢复全保真。
                if (currentSpan != null) {
                    currentSpan.setAttribute("aster.quota.source", "fail_open_warming");
                }
                triggerAsyncPrefetch(userId);
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
     * 同步检查 per-API-key per-second 限流（< 50ms；任何故障 fail-open）。
     *
     * R32 hotfix v3：原来用 Vert.x WebClient + 700ms future.get，event-loop
     * 上调用同样会被回调排队卡死。改成 SharedHttpClient + 同步 send()
     * + event-loop 检测 offload。HMAC 签名链路不变。
     */
    public RateCheck checkRate(String tenantId, String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            return new RateCheck(true, 0, 0);
        }
        String plan;
        try {
            plan = planGateService.lookupPlan(tenantId).plan();
        } catch (Exception e) {
            return new RateCheck(true, 0, 0);
        }
        try {
            String threadName = Thread.currentThread().getName();
            if (threadName != null && threadName.startsWith("vert.x-eventloop") && precheckPool != null) {
                // 关键：offload 后必须带超时 get()，否则 event-loop 线程会卡在
                // 队列等待上 —— precheckPool 只有 4 线程且与 precheck warmup 共享，
                // 高并发 cache-miss / cloud 抖动时任务可能排队，无超时 get() 会把
                // 阻塞从"单个慢请求"放大成"整个 event-loop 停摆"，延迟扩散到
                // 不相关请求。HTTP 自身超时 700ms，这里给 1s 上限（含排队 + 调度
                // 余量），超时按 fail-open 处理（rate-check 是软控制，真正的访问
                // 鉴权在 ApiKeyVerifierService）。
                java.util.concurrent.Future<RateCheck> f =
                    precheckPool.submit(() -> checkRateSync(apiKeyId, plan));
                try {
                    return f.get(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    f.cancel(true);
                    LOG.debugf("checkRate offload 超时(>1s)，按 fail-open 放行 apiKeyId=%.8s", apiKeyId);
                    return new RateCheck(true, 0, 0);
                }
            }
            return checkRateSync(apiKeyId, plan);
        } catch (Exception e) {
            return new RateCheck(true, 0, 0);
        }
    }

    private RateCheck checkRateSync(String apiKeyId, String plan) {
        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            String path = "/api/internal/api/rate-check";
            URI fullUri = baseUri.resolve(path);

            String bodyJson = new JsonObject()
                .put("apiKeyId", apiKeyId)
                .put("plan", plan)
                .encode();

            // v2 签名：绑定 nonce + bodyHash（见 InternalCallSigner）。
            // 旧 3 字段 canonical 不绑 body，一次签名即可换 body 无限重放。
            var signed = config.hmacKey()
                .map(k -> InternalCallSigner.sign(k, "POST", path, bodyJson))
                .orElse(new InternalCallSigner.Signed(
                    String.valueOf(System.currentTimeMillis() / 1000), "", ""));
            String timestamp = signed.timestamp();
            String signature = signed.signature();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(fullUri)
                .timeout(Duration.ofMillis(700))
                .header("X-Aster-Timestamp", String.valueOf(timestamp))
                .header("X-Aster-Signature", signature)
                .header("X-Aster-Nonce", signed.nonce())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = sharedHttpClient.client()
                .send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new RateCheck(true, 0, 0);
            }
            JsonObject json = new JsonObject(resp.body());
            return new RateCheck(
                json.getBoolean("allowed", true),
                json.getInteger("used", 0),
                json.getInteger("limit", 0)
            );
        } catch (Exception e) {
            return new RateCheck(true, 0, 0);
        }
    }

    public record RateCheck(boolean allowed, int used, int limit) {}

    /**
     * 异步记录一次调用 — fire-and-forget，不阻塞业务路径
     */
    public void recordAsync(String userId, String tenantId, String apiKeyId,
                             String endpointPath, String status, long latencyMs) {
        // PlanGate 关闭：跳过本地计数 + cloud 异步上报。无 cloud 接收方时
        // 这两步纯粹是 DNS lookup + connection refused 的开销。
        if (!config.enabled()) {
            return;
        }
        // SNAP-5 双写步骤 1：本地 redis INCR（同步，hot path 真源，0 RTT）
        // 仅 success 计数（与 cloud apiCallRecords 表 status='success' 过滤一致）
        if ("success".equals(status) && userId != null) {
            try {
                snapshot.incrementCounter(userId);
            } catch (Exception e) {
                LOG.warnf("local counter incr failed userId=%s: %s", userId, e.getMessage());
            }
        }

        // SNAP-5 双写步骤 2：异步推 cloud（fire-and-forget，dashboard 显示用 + 跨 pod 对账）
        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            int port = baseUri.getPort() == -1
                ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
                : baseUri.getPort();
            boolean ssl = "https".equals(baseUri.getScheme());
            String path = "/api/internal/api/usage";

            JsonObject body = new JsonObject()
                .put("userId", userId)
                .put("tenantId", tenantId)
                .put("apiKeyId", apiKeyId)
                .put("endpointPath", endpointPath)
                .put("status", status)
                .put("latencyMs", latencyMs);

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
                    userId, err.getMessage()));
        } catch (Exception e) {
            LOG.warnf("api usage record exception: user=%s, err=%s", userId, e.getMessage());
        }
    }

    /**
     * AKA-8: 合并查询 — 一次 GET 拿 plan + apiCallsLimit + monthlyUsed
     */
    private record PrecheckResult(long apiCallsLimit, long monthlyUsed, String plan) {}

    /** 限制并发预热：同一 userId 已有 in-flight 任务时不重复提交。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> prefetchInFlight
        = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * R32 hotfix v3: hot path 不能阻塞 event-loop。fail-open 立刻返回后，
     * 把真正的 precheck 丢到 worker pool 上跑，结果写进 Caffeine + redis snapshot。
     * 下一次该 userId 命中即走快路径。
     */
    private void triggerAsyncPrefetch(String userId) {
        if (userId == null || precheckPool == null) return;
        // 防止"同一用户连续 N 个请求 → N 个 fetch in-flight"打爆 cf 端。
        if (prefetchInFlight.putIfAbsent(userId, Boolean.TRUE) != null) return;
        try {
            precheckPool.submit(() -> {
                try {
                    PrecheckResult pre = fetchPrecheckSync(userId);
                    if (precheckCache != null) {
                        precheckCache.put(userId, pre);
                    }
                    try {
                        snapshot.setUser(new io.aster.billing.snapshot.UserSnapshot(
                            userId, pre.plan, pre.apiCallsLimit, null, null, null));
                    } catch (Exception ignore) {
                        // snapshot 内部已 catch；这里再兜一层
                    }
                } catch (Exception e) {
                    LOG.debugf("async precheck warmup failed userId=%s: %s",
                        userId, e.getMessage());
                } finally {
                    prefetchInFlight.remove(userId);
                }
            });
        } catch (RuntimeException submitFail) {
            prefetchInFlight.remove(userId);
        }
    }

    /**
     * R32 hotfix v3: 用 java.net.http.HttpClient 同步阻塞 send()，不依赖 Vert.x
     * event-loop 调度。共享 HttpClient 实例（{@link io.aster.common.http.SharedHttpClient}）
     * 保留 keep-alive 连接 + TLS session resumption，hot path 实测 ~50ms。
     * timeout 收紧到 {@value #PRECHECK_TIMEOUT_MS}ms — 超过即 fail-open，cf 边缘
     * 正常 ~200ms，1.5s 已经留足缓冲。
     */
    private PrecheckResult fetchPrecheckSync(String userId) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        String path = "/api/internal/api/precheck";
        String query = "userId=" + urlEncode(userId == null ? "" : userId);
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
            json.getString("plan", "free")
        );
    }

    private static final long PRECHECK_TIMEOUT_MS = 1500;

    private WebClient getClient() {
        return sharedWebClient.client();
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
