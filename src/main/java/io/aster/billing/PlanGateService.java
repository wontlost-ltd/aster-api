package io.aster.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 跨服务 plan 查询服务
 *
 * 流程：
 *   1. 优先读 Caffeine cache
 *   2. 未命中调 aster-cloud /internal/tenant/{id}/plan（HMAC 签名）
 *   3. 失败时按 failOpen 策略返回 PlanInfo.failOpen() 或抛异常
 *
 * 故障吸收：HTTP 异常或超时永远不会传播给业务侧；最差表现是 fail-open。
 */
@ApplicationScoped
public class PlanGateService {

    private static final Logger LOG = Logger.getLogger(PlanGateService.class);

    @Inject
    PlanGateConfig config;

    @Inject
    io.aster.common.http.SharedHttpClient sharedHttpClient;

    private Cache<String, PlanInfo> cache;

    /**
     * R32 hotfix v3: 同 ApiQuotaGuard，hot path 不能阻塞 event-loop 等 cf 回包。
     * Cache miss 时立刻返回 failOpen + 后台 warmup，下次同 tenantId 命中走 0 RTT 路径。
     */
    private ExecutorService lookupPool;
    private final ConcurrentHashMap<String, Boolean> lookupInFlight = new ConcurrentHashMap<>();
    private static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(1500);

    void onStart(@Observes StartupEvent ev) {
        cache = Caffeine.newBuilder()
            .maximumSize(config.cacheMaxEntries())
            .expireAfterWrite(config.cacheTtl())
            .build();
        lookupPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "plan-gate-worker");
            t.setDaemon(true);
            return t;
        });
        if (!config.enabled()) {
            LOG.info("PlanGate 未启用：aster.plan-gate.enabled=false，所有调用按 Pro 档处理");
        } else {
            LOG.infof("PlanGate 启动：cloudUrl=%s, cacheTtl=%s",
                config.cloudInternalUrl(), config.cacheTtl());
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (lookupPool != null) {
            lookupPool.shutdown();
            try {
                if (!lookupPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    lookupPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                lookupPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 查询某租户的 plan 信息（带 cache，故障 fail-open） */
    public PlanInfo lookupPlan(String tenantId) {
        if (!config.enabled()) {
            return PlanInfo.failOpen();
        }
        if (tenantId == null || tenantId.isBlank()) {
            return PlanInfo.failOpen();
        }

        PlanInfo cached = cache.getIfPresent(tenantId);
        if (cached != null) return cached;

        // R32 hotfix v3: hot path 不能阻塞 event-loop 等 cf 回包。Cache miss 时
        // 立刻 fail-open 并把真正的 fetch 丢到 worker pool 上跑，下次同 tenant
        // 5min TTL 内命中 Caffeine 即走 0 RTT 路径。
        //
        // 安全模型：plan 信息仅用于 soft tier-check（如 enterprise 不限 RPS）。
        // 真正的 plan-based access 控制由 ApiKeyVerifierService 的 cloud verify
        // 持有 authority（包括 plan 字段），那条路径已经在 Caffeine 5min 缓存。
        // 这里 fail-open 不会让"不该执行的用户"绕过任何门控。
        String threadName = Thread.currentThread().getName();
        if (threadName != null && threadName.startsWith("vert.x-eventloop")) {
            triggerAsyncLookup(tenantId);
            // 配置一致性：若运维显式设了 failOpen=false（要求 fail-closed），
            // event-loop 分支也必须遵守，否则首次请求 / 缓存失效窗口会无视该
            // 配置静默放行 —— 与下方 worker 同步路径的 fail-closed 语义产生分歧。
            // 默认 failOpen=true 时维持原有 0-RTT fail-open 热路径，行为不变。
            if (!config.failOpen()) {
                // 不可用 ≠ 档位不足：用 503 语义的专属异常，避免被 402
                // upgrade mapper 误导客户端去升级套餐。
                throw new PlanLookupUnavailableException("plan_lookup_unavailable");
            }
            return PlanInfo.failOpen();
        }

        // Caller 在 worker 上时直接 fetch（同步路径，e.g. CDI 内部调用 / 后台任务）。
        // 统一走 java.net.http.HttpClient（同步 send），与 event-loop warmup 路径
        // 一致；不再用 Vert.x WebClient——后者的 onSuccess/onFailure 回调要等
        // event-loop 调度，繁忙时即使在 worker 上 future.get 也会假超时（R32 根因）。
        try {
            PlanInfo fetched = fetchFromCloudHttpClient(tenantId);
            cache.put(tenantId, fetched);
            return fetched;
        } catch (Exception e) {
            LOG.warnf("PlanGate 查询失败 tenant=%s: %s（按 fail-open=%s 处理）",
                tenantId, e.getMessage(), config.failOpen());
            if (!config.failOpen()) {
                // 同上：lookup 失败是服务可用性问题（503/可重试），不是 402。
                throw new PlanLookupUnavailableException("plan_lookup_failed");
            }
            return PlanInfo.failOpen();
        }
    }

    private void triggerAsyncLookup(String tenantId) {
        if (tenantId == null || lookupPool == null) return;
        if (lookupInFlight.putIfAbsent(tenantId, Boolean.TRUE) != null) return;
        try {
            lookupPool.submit(() -> {
                try {
                    PlanInfo fetched = fetchFromCloudHttpClient(tenantId);
                    cache.put(tenantId, fetched);
                } catch (Exception e) {
                    LOG.debugf("async plan-gate warmup failed tenant=%s: %s",
                        tenantId, e.getMessage());
                } finally {
                    lookupInFlight.remove(tenantId);
                }
            });
        } catch (RuntimeException submitFail) {
            lookupInFlight.remove(tenantId);
        }
    }

    /**
     * R32 hotfix v3: 同步阻塞 send()，跑在 lookupPool 上不依赖任何 event-loop 调度。
     */
    private PlanInfo fetchFromCloudHttpClient(String tenantId) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        // 本层自卫：对 tenantId 做 path-segment 编码，避免内部调用方未来传入
        // 含 '/'、'?'、'%2F' 的值造成 path / HMAC 签名歧义（当前 TenantFilter
        // 已约束格式，但 PlanGateService 是公共 service 方法，不应依赖上游）。
        // 编码后的 path 同时用于请求与签名，保证两端一致。
        String encodedTenant = java.net.URLEncoder
            .encode(tenantId, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
        String path = "/api/internal/tenant/" + encodedTenant + "/plan";
        URI fullUri = baseUri.resolve(path);

        long timestamp = System.currentTimeMillis() / 1000;
        String signature = config.hmacKey()
            .map(key -> sign(key, "GET\n" + path + "\n" + timestamp))
            .orElse("");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(fullUri)
            .timeout(LOOKUP_TIMEOUT)
            .header("X-Aster-Timestamp", String.valueOf(timestamp))
            .header("X-Aster-Signature", signature)
            .GET()
            .build();

        HttpResponse<String> resp = sharedHttpClient.client()
            .send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                "cloud /internal/tenant/" + tenantId + "/plan 返回 " + resp.statusCode());
        }
        return toPlanInfo(new JsonObject(resp.body()));
    }

    /** 是否允许审批流（≥Pro 档） */
    public boolean allowsApproval(String tenantId) {
        return lookupPlan(tenantId).allowsApproval();
    }

    /** 单租户最大成员数（-1 表示无限） */
    public int maxTeamMembers(String tenantId) {
        return lookupPlan(tenantId).maxTeamMembers();
    }

    /** 让指定 tenant 的缓存立即失效（cloud 升级回调可调用此方法） */
    public void invalidate(String tenantId) {
        if (cache != null) {
            cache.invalidate(tenantId);
        }
    }

    private static PlanInfo toPlanInfo(JsonObject json) {
        return new PlanInfo(
            json.getString("plan", "free"),
            json.getString("legacyTier", null),
            json.getBoolean("allowsApproval", false),
            json.getInteger("maxTeamMembers", 1),
            json.getLong("evaluationsLimit", 0L),
            json.getLong("apiCallsLimit", 0L),
            // ★缺省 0 = fail-closed（ADR 0034 §7.2）。
            //   plan-gate 尚未下发该字段时（部署顺序、旧版本），按「无此能力」处理，
            //   而不是猜一个宽松值——猜错的代价是免费开放付费能力。
            json.getInteger("concurrentReplayBatches", 0)
        );
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
}
