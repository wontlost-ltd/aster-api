package io.aster.billing.snapshot;

import io.aster.billing.PlanGateConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Snapshot 预热 + 对账
 *
 * 触发：
 *   - StartupEvent：启动 30s 内拉一次全量，把 redis 写满
 *   - @Scheduled every="1h"：全量对账，把 redis 与 cloud 一致化（容灾兜底）
 *
 * 走 cloud /api/internal/snapshot/full?cursor=...&limit=1000，分页拉。
 */
@ApplicationScoped
public class SnapshotWarmupService {

    private static final Logger LOG = Logger.getLogger(SnapshotWarmupService.class);
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 100; // 上限 100k user 防失控

    @Inject
    LocalQuotaSnapshotService snapshot;

    @Inject
    PlanGateConfig config;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;
    // P0-R19: WebClient DCL consolidated into SharedWebClient

    // Shutdown latch: integration tests start the app briefly then exit.
    // The 2s warm-up delay below routinely outlives the test JVM's
    // Quarkus context, and the in-flight Vert.x dispatch then logs a
    // noisy RejectedExecutionException ("event executor terminated").
    // Latch is checked at every async hand-off so a late warm-up is a
    // silent no-op instead of a stack trace.
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // R30+ audit P0：在多 StartupEvent / multi-restart 场景里不让 warm-up
    // 线程累积。StartupEvent 在正常 Quarkus 生命周期内只 fire 一次，但
    // 测试热重启 / dev-mode reload 会触发多次。引用一个静态守门，确保同一
    // 进程内只有一条活跃 warm-up 线程；onShutdown 同时 interrupt 中断
    // sleep / future.get，避免守护线程在 JVM 强制退出前继续做无意义工作。
    private static final java.util.concurrent.atomic.AtomicReference<Thread> WARMUP_THREAD =
        new java.util.concurrent.atomic.AtomicReference<>();

    void onShutdown(@Observes ShutdownEvent ev) {
        shuttingDown.set(true);
        Thread t = WARMUP_THREAD.getAndSet(null);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.enabled()) {
            LOG.info("plan-gate disabled, skipping snapshot warm-up");
            return;
        }
        // 异步启动 warm-up，不阻塞 readiness。
        //
        // 用独立 daemon 线程而不是 mutinyVertx.executeBlocking：后者完成时
        // 把 success 回 dispatch 到原 context 的 event loop —— 集成测试场景下，
        // event loop 在 2s warm-up 期间常常先被 Quarkus shutdown 关掉，
        // dispatch 失败时 Vert.x 把它当作 "Uncaught exception received by
        // Vert.x [Error Occurred After Shutdown]" 打印一长串 stack。
        //
        // daemon=true 让线程不阻碍 JVM 退出；fetchPage 自身在 shuttingDown
        // 设置后会快速失败，整体冷启动开销不变。
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000); // 给 redis / cloud 上线时间
                if (shuttingDown.get()) {
                    LOG.debug("snapshot warm-up skipped (shutdown in progress)");
                    return;
                }
                int n = fullSync("warmup");
                LOG.infof("snapshot warm-up complete: %d users", n);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (RejectedExecutionException e) {
                LOG.debug("snapshot warm-up aborted (event loop terminated during shutdown)");
            } catch (Exception e) {
                if (shuttingDown.get()) {
                    LOG.debug("snapshot warm-up failed during shutdown: " + e.getMessage());
                } else {
                    LOG.warnf("snapshot warm-up failed (will retry on 1h cron): %s", e.getMessage());
                }
            } finally {
                // 不论怎么退出，清掉守门引用，让下一次 onStart 可以重启
                WARMUP_THREAD.compareAndSet(Thread.currentThread(), null);
            }
        }, "snapshot-warmup");
        t.setDaemon(true);

        // check-then-set：如果已经有 warm-up 线程存活，直接放弃本次
        Thread existing = WARMUP_THREAD.get();
        if (existing != null && existing.isAlive()) {
            LOG.debug("snapshot warm-up already running, skipping duplicate start");
            return;
        }
        if (!WARMUP_THREAD.compareAndSet(existing, t)) {
            LOG.debug("snapshot warm-up race lost to another StartupEvent");
            return;
        }
        t.start();
    }

    @Scheduled(every = "1h", delayed = "10m",
               skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    void hourlyReconcile() {
        if (!config.enabled()) return;
        try {
            int n = fullSync("reconcile");
            LOG.infof("hourly snapshot reconcile: %d users", n);
        } catch (Exception e) {
            LOG.warnf("hourly reconcile failed: %s", e.getMessage());
        }
    }

    /**
     * 全量同步（分页）
     * @return 同步的 user 数量
     */
    public int fullSync(String reason) throws Exception {
        String cursor = null;
        int totalUsers = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject resp = fetchPage(cursor);
            JsonArray users = resp.getJsonArray("users", new JsonArray());
            JsonArray keys = resp.getJsonArray("apiKeys", new JsonArray());
            for (int i = 0; i < users.size(); i++) {
                JsonObject u = users.getJsonObject(i);
                snapshot.setUser(new UserSnapshot(
                    u.getString("userId"),
                    u.getString("plan", "free"),
                    u.getLong("apiCallsLimit", 0L),
                    u.getString("subscriptionStatus"),
                    u.getLong("aiBannedUntilEpochMs"),
                    u.getLong("gracePeriodEndsEpochMs")
                ));
                totalUsers++;
            }
            for (int i = 0; i < keys.size(); i++) {
                JsonObject k = keys.getJsonObject(i);
                String keyHash = k.getString("keyHash");
                if (keyHash == null) continue;
                ApiKeySnapshot s = k.getBoolean("valid", false)
                    ? new ApiKeySnapshot(true, null,
                        k.getString("apiKeyId"), k.getString("userId"),
                        k.getString("tenantId"),
                        k.getString("plan"), k.getString("role"),
                        k.getLong("revokedAtEpochMs"))
                    : ApiKeySnapshot.invalid("revoked");
                snapshot.setApiKey(keyHash, s);
            }
            cursor = resp.getString("nextCursor");
            if (cursor == null) break;
        }
        LOG.infof("snapshot %s: synced %d users", reason, totalUsers);
        return totalUsers;
    }

    private JsonObject fetchPage(String cursor) throws Exception {
        if (shuttingDown.get()) {
            throw new RejectedExecutionException("snapshot warm-up aborted: shutdown in progress");
        }
        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/snapshot/full";
        StringBuilder query = new StringBuilder("limit=" + PAGE_LIMIT);
        if (cursor != null) {
            query.append("&cursor=").append(java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }

        // ★必须 v2 canonical（nonce + bodySha256）：cloud 已关闭 v1 兼容窗口。
        //   此前手写 v1 → 401 → 启动预热与 1h 对账**全部失败**，
        //   Redis 配额快照可能长期为空或陈旧（issue #231，真实例实测确认）。
        //   ★签名只用 pathname，**不含 query**——本方法的 cursor/limit 在 query 里，
        //   把它们签进去会与 cloud 的 url.pathname 对不上。
        // ★同 PlanGateService：key 缺失时发空签名而非抛异常，
        //   否则「没配密钥」会以别的形态（预热失败）冒出来，更难定位。
        var signed = config.hmacKey()
            .filter(k -> !k.isBlank())
            .map(k -> io.aster.security.internal.InternalCallSigner.sign(k, "GET", path, ""))
            .orElse(null);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        getClient()
            .get(port, baseUri.getHost(), path + "?" + query)
            .ssl(ssl)
            .timeout(10_000) // 全量拉相对慢，给 10s
            .putHeader("X-Aster-Timestamp",
                signed != null ? signed.timestamp() : String.valueOf(System.currentTimeMillis() / 1000))
            .putHeader("X-Aster-Nonce", signed != null ? signed.nonce() : "")
            .putHeader("X-Aster-Signature", signed != null ? signed.signature() : "")
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    future.completeExceptionally(new RuntimeException(
                        "snapshot/full HTTP " + resp.statusCode()));
                    return;
                }
                try {
                    future.complete(resp.bodyAsJsonObject());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);

        // R30+ audit P1：原 15s 是为 cloud-side endpoint slow-start 留的，
        // 但 daemon 卡 15s 期间 ShutdownEvent 进来就只能等。降到 8s + 配合
        // shuttingDown 标志快速返回，整体 warm-up 在最坏情况下不超过
        // 8s × MAX_PAGES = 800s（与原 15s × 100 相比）。
        // 实际 cloud 健康时单页 < 1s；超时已是病理路径，长尾减半合理。
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            // 主动 cancel 让 WebClient release connection，避免 connection pool 泄漏
            future.cancel(true);
            throw te;
        }
    }

    private WebClient getClient() {
        return sharedWebClient.client();
    }

}
