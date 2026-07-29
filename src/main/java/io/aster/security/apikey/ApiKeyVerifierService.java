package io.aster.security.apikey;

import io.aster.security.internal.InternalCallSigner;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.billing.PlanGateConfig;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * API key 验证服务
 *
 * 把客户传的 ak_xxx 明文 → SHA-256 hash → 调 cloud /api/internal/apikey/verify。
 * 命中结果缓存 5 min（与 plan-gate 一致）。
 *
 * Fail-open 策略：
 *   - cloud 不可达 + cache 命中 → 用缓存（旧但可信）
 *   - cloud 不可达 + cache miss → 拒绝（防伪造的新 key 蒙混过关）
 *
 * 缓存按 keyHash → ApiKeyVerifyResult 1:1，外加 userId → Set<keyHash> 反向索引
 * 用于 invalidateForUser（DUN-4 auto-downgrade 时清掉一个用户所有 key 缓存）。
 */
@ApplicationScoped
public class ApiKeyVerifierService {

    private static final Logger LOG = Logger.getLogger(ApiKeyVerifierService.class);
    // 红队 P1-F：撤销窗口 = 缓存 TTL。原 5min 太长（被撤销的 key 仍可用 5 分钟）。
    // 收紧到 60s：即便跨副本广播失效（Redis 抖动），任一 pod 最多 60s 后重新 cloud/snapshot
    // 校验拿到 revoked 状态。正常路径靠下面的 Redis pub/sub 近实时失效（不等 TTL）。
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX = 10_000;
    /** 红队 P1-F：API key 缓存失效跨副本广播 channel（复用 LexiconAvailabilityService 模式）。 */
    private static final String INVALIDATE_CHANNEL = "aster:apikey:invalidate";

    @Inject
    PlanGateConfig config;

    @Inject
    jakarta.enterprise.inject.Instance<io.quarkus.redis.datasource.RedisDataSource> redisDataSource;

    private io.quarkus.redis.datasource.pubsub.PubSubCommands<String> pubSubCommands;
    private io.quarkus.redis.datasource.pubsub.PubSubCommands.RedisSubscriber invalidateSubscriber;

    @Inject
    io.aster.billing.snapshot.LocalQuotaSnapshotService localSnapshot;

    @Inject
    io.aster.common.http.SharedHttpClient sharedHttpClient;
    private Cache<String, ApiKeyVerifyResult> cache;
    /** userId → 该用户所有缓存过的 keyHash，用于按用户批量失效 */
    private final ConcurrentHashMap<String, Set<String>> userIndex = new ConcurrentHashMap<>();

    /**
     * R32 hotfix：cache-miss verify path 会做两件 event-loop 禁止的事情：
     *   1) blocking Redis read (Quarkus ValueCommands<String>)
     *   2) future.get(8s) waiting for Vert.x WebClient response
     * ApiKeyAuthFilter @ServerRequestFilter 跑在 event loop，触发
     * "thread cannot be blocked" 异常，verify 进入 catch 返回
     * verify_unavailable → 401。
     *
     * 用一个 daemon ExecutorService 把整个慢路径 offload。Filter 调
     * verify() 时同步阻塞这个 future —— 但 filter 不在 event loop 上
     * 等（实际上是 RESTEasy 的内部线程持有），调用链终归是同步语义。
     *
     * 容量：4 个 worker thread 处理所有并发 cache miss。每次 verify
     * cf 边缘 ~50-100ms，4 worker = ~40-80 req/sec verify 吞吐。
     * Caffeine 命中后零开销，所以稳态远超此数。
     */
    private java.util.concurrent.ExecutorService verifyPool;

    void onStart(@Observes StartupEvent ev) {
        cache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX)
            .expireAfterWrite(CACHE_TTL)
            .removalListener((String keyHash, ApiKeyVerifyResult value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                if (value != null && value.userId() != null) {
                    Set<String> hashes = userIndex.get(value.userId());
                    if (hashes != null) hashes.remove(keyHash);
                }
            })
            .build();
        // R32 hotfix：4 daemon worker pool for verify offload
        verifyPool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "apikey-verify-worker");
            t.setDaemon(true);
            return t;
        });
        // 红队 P1-F：订阅跨副本失效广播。收到 userId → 清本副本 Caffeine（近实时撤销，
        // 不等 60s TTL）。fail-open：订阅失败仅退化到 TTL 兜底，不影响验证路径。
        initInvalidateBroadcast();
        LOG.info("ApiKeyVerifierService started: cacheTtl=" + CACHE_TTL);
    }

    /** 红队 P0-F（Codex 审查后补）：广播订阅是否活跃。false=退化到 60s TTL 兜底，供健康检查/指标读取。 */
    private volatile boolean broadcastActive = false;

    /** 跨副本失效广播是否处于活跃状态（订阅已建立）。false 表示当前仅靠 60s TTL 兜底。 */
    public boolean isBroadcastActive() {
        return broadcastActive;
    }

    private void initInvalidateBroadcast() {
        if (redisUnavailable()) {
            LOG.warn("apikey 失效广播未启用（无 Redis）；跨副本撤销退化到 60s TTL 兜底 "
                + "[apikey_invalidate_broadcast=down]");
            return;
        }
        try {
            pubSubCommands = redisDataSource.get().pubsub(String.class);
            // payload = userId。远端 pod 收到即清本地缓存（invalidateLocalOnly 不再回广播，防环）。
            invalidateSubscriber = pubSubCommands.subscribe(INVALIDATE_CHANNEL,
                this::invalidateLocalOnly);
            broadcastActive = true;
            LOG.infof("apikey 失效广播通道已启用: %s [apikey_invalidate_broadcast=up]", INVALIDATE_CHANNEL);
        } catch (Exception e) {
            // 可观测：明确标记 broadcast down，operator 可据此告警；本副本 TTL 兜底仍有效。
            LOG.warn("初始化 apikey 失效广播订阅失败（退化到 60s TTL 兜底）"
                + " [apikey_invalidate_broadcast=down]", e);
        }
    }

    void onStop(@Observes io.quarkus.runtime.ShutdownEvent ev) {
        if (invalidateSubscriber != null) {
            try {
                invalidateSubscriber.unsubscribe();
            } catch (Exception e) {
                LOG.debugf("apikey 失效广播退订异常（忽略）: %s", e.getMessage());
            }
        }
        if (verifyPool != null) {
            verifyPool.shutdown();
            try {
                if (!verifyPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    verifyPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                verifyPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * R32 hotfix v3: 暴露 cache fast path 给 {@link ApiKeyAuthFilter}。
     * 命中返回结果，未命中返回 null。Filter 用它决定走同步快路径还是 Uni 慢路径。
     * 不触发任何 IO，纯 in-memory Caffeine 查询。
     */
    public ApiKeyVerifyResult tryCacheLookup(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()
                || !plaintextKey.startsWith("ak_")) {
            return null;
        }
        if (cache == null) return null;
        return cache.getIfPresent(sha256Hex(plaintextKey));
    }

    /**
     * R32 hotfix v3: 暴露 verify 专用 worker pool 给 {@link ApiKeyAuthFilter}
     * 的 {@code Uni.runSubscriptionOn} 使用。这样 cache-miss verify 走 Uni 异步路径，
     * RESTEasy event-loop 让出线程而不是等 future.get。
     */
    public java.util.concurrent.ExecutorService verifyExecutor() {
        return verifyPool;
    }

    /**
     * 校验明文 API key（ak_xxx）是否有效
     *
     * @param plaintextKey 客户在 Authorization: Bearer 头里传的明文
     * @return 验证结果（缓存命中或新查询）
     */
    public ApiKeyVerifyResult verify(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return ApiKeyVerifyResult.invalid("empty");
        }
        if (!plaintextKey.startsWith("ak_")) {
            return ApiKeyVerifyResult.invalid("bad_format");
        }

        String keyHash = sha256Hex(plaintextKey);
        ApiKeyVerifyResult cached = cache.getIfPresent(keyHash);
        if (cached != null) {
            return cached;
        }

        // R32 hotfix：cache-miss path 含两个 blocking 操作（redis read +
        // future.get cf call）。Vert.x event-loop 线程禁止阻塞，所以如果
        // 当前线程名以 "vert.x-eventloop" 开头，把整个慢路径丢到
        // verifyPool 上跑，本线程阻塞等结果（caller 是 RESTEasy 调度，
        // 不是 event loop）。
        String threadName = Thread.currentThread().getName();
        if (threadName != null && threadName.startsWith("vert.x-eventloop")) {
            try {
                return verifyPool.submit(() -> doSlowPathVerify(keyHash)).get();
            } catch (java.util.concurrent.ExecutionException ee) {
                LOG.warnf("apikey verify offload failed: %s", ee.getCause() == null ? "null" : ee.getCause().getMessage());
                return ApiKeyVerifyResult.invalid("verify_unavailable");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ApiKeyVerifyResult.invalid("verify_unavailable");
            }
        }
        return doSlowPathVerify(keyHash);
    }

    /**
     * R32 hotfix：cache-miss 真正干活的部分。被 {@link #verify} 在合适
     * 的线程上调用 —— 当 caller 已在 worker 上时直接跑；当 caller 在
     * event-loop 上时通过 verifyPool offload。
     */
    private ApiKeyVerifyResult doSlowPathVerify(String keyHash) {
        // SNAP-5: Caffeine miss → 先查 redis snapshot（cloud webhook 推过来的）
        java.util.Optional<io.aster.billing.snapshot.ApiKeySnapshot> redisHit = localSnapshot.getApiKey(keyHash);
        if (redisHit.isPresent()) {
            io.aster.billing.snapshot.ApiKeySnapshot s = redisHit.get();
            // 租户隔离：snapshot 命中也必须带权威 tenantId。历史上这里把
            // s.userId() 当 tenantId 传，导致认证上下文租户 = 用户ID（跨租户
            // 隔离 / 计费 / 审计全错）。现在：
            //   - invalid snapshot → 直接 invalid
            //   - valid 但缺 tenantId（旧 snapshot 尚未回填）→ 不能瞎猜，
            //     fall through 到 cloud verify（权威，返回正确 tenantId），
            //     fail-closed 而非 fail-wrong。
            if (!s.valid()) {
                ApiKeyVerifyResult fromRedis = ApiKeyVerifyResult.invalid(
                    s.reason() != null ? s.reason() : "invalid");
                cache.put(keyHash, fromRedis);
                return fromRedis;
            }
            if (s.tenantId() != null && !s.tenantId().isBlank()) {
                ApiKeyVerifyResult fromRedis = ApiKeyVerifyResult.valid(
                    s.apiKeyId(), s.userId(), s.tenantId(), s.plan(), null, s.role());
                cache.put(keyHash, fromRedis);
                if (fromRedis.userId() != null) {
                    userIndex.computeIfAbsent(fromRedis.userId(), k -> ConcurrentHashMap.newKeySet()).add(keyHash);
                }
                return fromRedis;
            }
            LOG.debugf("apikey snapshot hit but tenantId missing (stale snapshot); "
                + "falling through to cloud verify for authoritative tenant");
            // 不缓存、不 return —— 继续走下面的 cloud verify。
        }

        ApiKeyVerifyResult fresh;
        try {
            fresh = fetchFromCloud(keyHash);
        } catch (Exception e) {
            // R32 hotfix：原来只 log e.getMessage()，对 NPE / wrapped 异常
            // 看到 "null" 没法 diagnose。加 class + cause 链帮排查。
            Throwable cause = e.getCause();
            LOG.warnf("apikey verify cloud unreachable, denying: %s (cause=%s, msg=%s)",
                e.getClass().getName(),
                cause != null ? cause.getClass().getName() : "null",
                e.getMessage() != null ? e.getMessage()
                    : (cause != null ? cause.getMessage() : "null"));
            return ApiKeyVerifyResult.invalid("verify_unavailable");
        }
        cache.put(keyHash, fresh);
        if (fresh.valid() && fresh.userId() != null) {
            userIndex.computeIfAbsent(fresh.userId(), k -> ConcurrentHashMap.newKeySet()).add(keyHash);
        }
        return fresh;
    }

    /**
     * 让指定用户的所有 key 缓存失效（DUN-4 auto-downgrade / 用户主动撤销 / 订阅删除时调用）。
     *
     * <p>红队 P1-F：清本副本本地缓存 **+ 跨副本广播**。此前只清收到 DELETE 的那一个 pod
     * 的 Caffeine，其余 5 副本仍用本地缓存服务被撤销的 key 直到 TTL（跨副本发散，同
     * [[lexicon-availability-cross-replica]]）。现在通过 Redis pub/sub 通知所有副本立即清除。
     *
     * @return 本副本清除的 key 数（不含远端副本）
     */
    public int invalidateForUser(String userId) {
        int n = invalidateLocalOnly(userId);
        broadcastInvalidate(userId);
        return n;
    }

    /**
     * 只清本副本本地缓存，不广播。供 Redis 订阅回调使用（远端 pod 收到广播后清本地），
     * 避免"清→广播→别人清→再广播"的广播风暴/回环。
     */
    private int invalidateLocalOnly(String userId) {
        if (userId == null || cache == null) return 0;
        Set<String> hashes = userIndex.remove(userId);
        if (hashes == null || hashes.isEmpty()) return 0;
        int n = 0;
        for (String h : hashes) {
            cache.invalidate(h);
            n++;
        }
        return n;
    }

    /** 向所有副本广播"清除该 userId 的 key 缓存"。fail-open：Redis 不可用则仅本副本生效（TTL 兜底）。 */
    private void broadcastInvalidate(String userId) {
        if (userId == null || redisUnavailable()) return;
        try {
            io.quarkus.redis.datasource.pubsub.PubSubCommands<String> ps = pubSubCommands != null
                ? pubSubCommands
                : redisDataSource.get().pubsub(String.class);
            ps.publish(INVALIDATE_CHANNEL, userId);
        } catch (Exception e) {
            LOG.warnf("广播 apikey 失效失败 userId=%s（本副本已清，其余副本靠 60s TTL 兜底）: %s",
                userId, e.getMessage());
        }
    }

    private boolean redisUnavailable() {
        return redisDataSource == null || redisDataSource.isUnsatisfied();
    }

    /**
     * 测试支持：把一个明文 key 的验证结果预置进 Caffeine 缓存，使
     * {@link ApiKeyAuthFilter} 的 cache fast-path 命中，无需真实 cloud verify。
     *
     * <p>仅供集成测试模拟"有效/已撤销 key"使用——生产代码路径永远不调用它
     * （验证结果只能来自 {@link #verify} 的 cloud/snapshot 真实校验）。命名与
     * 可见性刻意保留为公开但语义明确，以便 @QuarkusTest 注入后调用。
     */
    public void seedCacheForTest(String plaintextKey, ApiKeyVerifyResult result) {
        if (plaintextKey == null || cache == null) return;
        String keyHash = sha256Hex(plaintextKey);
        cache.put(keyHash, result);
        // 同步维护 userIndex 反向索引——生产 doSlowPathVerify 对 valid key 也会做这步。
        // 不建索引则 invalidateForUser 找不到该 key（红队 P1-F 失效测试依赖此索引）。
        if (result != null && result.valid() && result.userId() != null) {
            userIndex.computeIfAbsent(result.userId(), k -> ConcurrentHashMap.newKeySet()).add(keyHash);
        }
    }

    /**
     * R32 hotfix v2: 用 java.net.http.HttpClient（同步阻塞 send）取代
     * Vert.x WebClient。原因：WebClient 的 onSuccess/onFailure 回调
     * 在 Vert.x 上下文（event loop）上排队，即使我们在 worker pool 上
     * future.get(timeout) 等待，回调本身仍要等 event-loop 调度，繁忙
     * 时会超过 timeout 触发 TimeoutException(null msg) —— 即使 cf 边缘
     * 实际 ~200ms 回包。
     *
     * 改成 java.net.http.HttpClient.send() 同步阻塞 IO：调用线程是
     * verifyPool worker，整个 HTTPS 握手 + 响应在该线程上完成，不
     * 依赖任何 event-loop 调度。
     */
    private ApiKeyVerifyResult fetchFromCloud(String keyHash) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        String path = "/api/internal/apikey/verify";
        URI fullUri = baseUri.resolve(path);

        String bodyJson = new JsonObject().put("keyHash", keyHash).encode();

        // v2 签名：绑定 nonce + bodyHash（见 InternalCallSigner）。旧 3 字段 canonical
        // 不绑 body——攻击者拿到一次签名即可换 keyHash 枚举其他 key。
        // ★body 必须先构造再签名，顺序不可颠倒。
        var signed = config.hmacKey()
            .map(k -> InternalCallSigner.sign(k, "POST", path, bodyJson))
            .orElse(new InternalCallSigner.Signed(
                String.valueOf(System.currentTimeMillis() / 1000), "", ""));
        String timestamp = signed.timestamp();
        String signature = signed.signature();
        // R32 hotfix v3: verify timeout 收紧到 2s — cf 正常 ~200ms，2s 已留足缓冲。
        // 共用 SharedHttpClient 实例保持 keep-alive + TLS 会话复用，hot path 实测 <50ms。
        Duration timeout = Duration.ofSeconds(2);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(fullUri)
            .timeout(timeout)
            .header("X-Aster-Timestamp", String.valueOf(timestamp))
            .header("X-Aster-Signature", signature)
                .header("X-Aster-Nonce", signed.nonce())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = sharedHttpClient.client()
            .send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("verify HTTP " + resp.statusCode() + " body=" + truncate(resp.body(), 200));
        }
        JsonObject json = new JsonObject(resp.body());
        boolean valid = json.getBoolean("valid", false);
        if (!valid) {
            return ApiKeyVerifyResult.invalid(json.getString("reason", "invalid"));
        }
        return ApiKeyVerifyResult.valid(
            json.getString("apiKeyId"),
            json.getString("userId"),
            json.getString("tenantId"),
            json.getString("plan"),
            json.getString("subscriptionStatus"),
            // role 缺失（旧 cloud 未升级）时由 valid(...) 回退 MEMBER。
            json.getString("role")
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
