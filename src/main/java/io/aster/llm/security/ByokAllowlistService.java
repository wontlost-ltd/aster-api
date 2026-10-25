package io.aster.llm.security;

import io.aster.security.net.SsrfGuard;
import io.aster.security.net.ValidatedEndpoint;
import io.aster.common.redis.RedisBroadcastSupervisor;
import io.aster.common.redis.ResilientRedisSubscriber;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.set.SetCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.net.IDN;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BYOK 自定义 endpoint 动态 allowlist。
 *
 * <p>安全边界：这是 aster-api 出站 SSRF 的平台级控制面，GA 只支持平台管理员维护的全局 host
 * allowlist（tenantScope 预留但当前恒为 null）。租户隔离靠 BYOK key 的 per-user 加密，不靠 host
 * allowlist 分租户。
 *
 * <p>一致性模型照 {@code LexiconAvailabilityService}：Redis SET 是真相源，pub/sub 让活副本即时更新，
 * 周期对账兜底丢消息；Redis 缺失/故障时 fail-open 为本副本内存 allowlist，不拖垮启动或 admin 操作。
 * fail-open 只覆盖 Redis 层，新增 host 仍必须先通过 {@link SsrfGuard} 的 https + DNS/IP deny 校验。
 */
@ApplicationScoped
public class ByokAllowlistService {

    private static final Logger LOG = Logger.getLogger(ByokAllowlistService.class);

    /** Redis SET：管理员批准的 BYOK endpoint host。值格式 host 或 host|tenantScope（GA 只写 host）。 */
    public static final String REDIS_ALLOWLIST_SET = "aster:byok:allowlist";

    /** Redis pub/sub channel：allowlist 变更广播。 */
    public static final String ALLOWLIST_CHANNEL = "aster:byok:allowlist:changes";

    /** 低频对账周期（秒）：兜底 pub/sub 丢消息，让活副本最终收敛到 Redis SET。 */
    static final long RECONCILE_PERIOD_SECONDS = 45L;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    RedisBroadcastSupervisor broadcastSupervisor;

    @Inject
    SsrfGuard ssrfGuard;

    private final Set<AllowlistEntry> entries = Collections.synchronizedSet(new LinkedHashSet<>());

    private PubSubCommands<String> pubSubCommands;
    private ResilientRedisSubscriber subscriber;
    private ExecutorService applyExecutor;
    private ScheduledExecutorService reconcileScheduler;

    void onStart(@Observes StartupEvent event) {
        if (redisUnavailable()) {
            LOG.debug("RedisDataSource 未配置：BYOK endpoint allowlist 动态同步禁用（仅 env/本副本内存生效）");
            return;
        }
        applyExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "byok-allowlist-apply");
            t.setDaemon(true);
            return t;
        });
        applyExecutor.execute(this::reconcileFromRedisSet);
        initBroadcastChannel();
        initReconcileSchedule();
    }

    @PreDestroy
    void onStop() {
        if (subscriber != null) {
            subscriber.close();
        }
        shutdownQuietly(reconcileScheduler);
        shutdownQuietly(applyExecutor);
    }

    /**
     * 订阅交给 {@link RedisBroadcastSupervisor}：后台异步建立，失败或断线后退避重订阅，不阻塞启动；
     * down 期间本副本内存仍有效，定时对账兜底。
     * 每次（重新）订阅成功后对账一次，关闭“读 SET 后/订阅前”及断线期间漏收广播的窗口。
     */
    private void initBroadcastChannel() {
        pubSubCommands = redisDataSource.get().pubsub(String.class);
        subscriber = broadcastSupervisor.subscribe("byok_allowlist_broadcast", ALLOWLIST_CHANNEL,
            payload -> applyExecutor.execute(() -> applyRemoteEvent(payload)),
            () -> applyExecutor.execute(this::reconcileFromRedisSet));
    }

    private void initReconcileSchedule() {
        reconcileScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "byok-allowlist-reconcile");
            t.setDaemon(true);
            return t;
        });
        reconcileScheduler.scheduleWithFixedDelay(
            () -> applyExecutor.execute(this::reconcileFromRedisSet),
            RECONCILE_PERIOD_SECONDS, RECONCILE_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** 当前副本可见的动态 host 集。返回值不含 builtin/env 静态项。 */
    public Set<String> allowedHosts() {
        synchronized (entries) {
            Set<String> out = new LinkedHashSet<>();
            for (AllowlistEntry entry : entries) {
                if (entry.tenantScope() == null) {
                    out.add(entry.host());
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    /** 当前副本可见的动态 entry 集，供 admin GET 返回 tenantScope 扩展位。 */
    public Set<AllowlistEntry> allowedEntries() {
        synchronized (entries) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(entries));
        }
    }

    /** 添加全平台 host。Redis 故障时仍本地生效；SSRF 校验失败则 fail-closed。 */
    public MutationResult add(String host) {
        AllowlistEntry entry = canonicalEntry(host, null);
        boolean changed = entries.add(entry);
        persistAndBroadcast(entry, true);
        return new MutationResult(entry.host(), entry.tenantScope(), changed);
    }

    /** 移除全平台 host。Redis 故障时仍本地生效。 */
    public MutationResult remove(String host) {
        // remove 不做 DNS/IP 校验：若某 host 后来被 DNS 污染成私网，管理员仍必须能把旧条目删掉。
        AllowlistEntry entry = canonicalHostOnly(host, null);
        boolean changed = entries.remove(entry);
        persistAndBroadcast(entry, false);
        return new MutationResult(entry.host(), entry.tenantScope(), changed);
    }

    /**
     * 对账到 Redis SET（唯一真相源）。fail-open：Redis 读失败时保持当前本地缓存，不清空。
     */
    private void reconcileFromRedisSet() {
        if (redisUnavailable()) {
            return;
        }
        Set<String> raw;
        try {
            raw = allowlistSet().smembers(REDIS_ALLOWLIST_SET);
        } catch (Exception e) {
            LOG.warn("对账读取 BYOK allowlist Redis SET 失败（fail-open，本副本保持当前缓存）", e);
            return;
        }

        Set<AllowlistEntry> desired = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                AllowlistEntry entry = parseStoredEntry(value);
                validateStoredEntry(entry);
                desired.add(entry);
            } catch (IllegalArgumentException e) {
                LOG.warnf("忽略非法 BYOK allowlist Redis 成员: %s", value);
            }
        }
        synchronized (entries) {
            entries.clear();
            entries.addAll(desired);
        }
        LOG.debugf("BYOK allowlist 对账完成：dynamic=%s", desired);
    }

    private void applyRemoteEvent(String payload) {
        if (payload == null) {
            return;
        }
        int sep = payload.indexOf(':');
        if (sep <= 0 || sep == payload.length() - 1) {
            LOG.warnf("忽略畸形 BYOK allowlist 广播事件: %s", payload);
            return;
        }
        String op = payload.substring(0, sep);
        AllowlistEntry entry;
        try {
            entry = parseStoredEntry(payload.substring(sep + 1));
            if ("add".equals(op)) {
                validateStoredEntry(entry);
            }
        } catch (IllegalArgumentException e) {
            LOG.warnf("忽略非法 BYOK allowlist 广播成员: %s", payload);
            return;
        }
        switch (op) {
            case "add" -> entries.add(entry);
            case "remove" -> entries.remove(entry);
            default -> LOG.warnf("忽略未知 BYOK allowlist 广播 op: %s", payload);
        }
    }

    private void persistAndBroadcast(AllowlistEntry entry, boolean add) {
        if (redisUnavailable()) {
            return;
        }
        String encoded = entry.encode();
        try {
            if (add) {
                allowlistSet().sadd(REDIS_ALLOWLIST_SET, encoded);
            } else {
                allowlistSet().srem(REDIS_ALLOWLIST_SET, encoded);
            }
        } catch (Exception e) {
            LOG.warnf(e, "持久 BYOK allowlist 到 Redis 失败 host=%s add=%s（本副本已生效）",
                entry.host(), add);
        }
        try {
            PubSubCommands<String> ps = pubSubCommands != null
                ? pubSubCommands
                : redisDataSource.get().pubsub(String.class);
            ps.publish(ALLOWLIST_CHANNEL, (add ? "add:" : "remove:") + encoded);
        } catch (Exception e) {
            LOG.warnf(e, "广播 BYOK allowlist 失败 host=%s add=%s（其它副本由对账/重启补齐）",
                entry.host(), add);
        }
    }

    /**
     * 把管理员输入规范化成 host-only entry。GA 不接受动态 path/port；env 静态 allowlist 仍支持
     * path/port 约束，动态控制面保持最小 blast radius。
     */
    private AllowlistEntry canonicalEntry(String rawHost, String tenantScope) {
        String raw = rawHost == null ? "" : rawHost.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        String url = raw.contains("://") ? raw : "https://" + raw;
        ValidatedEndpoint endpoint = ssrfGuard.validate(url);
        if (endpoint.port() != 443 || !endpoint.pathPrefix().isBlank()) {
            throw new IllegalArgumentException("动态 BYOK allowlist 只接受 host（不含 path/非 443 port）");
        }
        return new AllowlistEntry(endpoint.canonicalHost(), normalizeTenantScope(tenantScope));
    }

    /** 仅做 host 语法规范化，不解析 DNS。用于 remove，保证坏 DNS 状态下仍能删除旧条目。 */
    private static AllowlistEntry canonicalHostOnly(String rawHost, String tenantScope) {
        String raw = rawHost == null ? "" : rawHost.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.contains("://") ? raw : "https://" + raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("host 不是合法 URI", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("host 只允许 https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("host 不允许 userinfo/query/fragment");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("动态 BYOK allowlist 只接受 host（不含 path/非 443 port）");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalArgumentException("动态 BYOK allowlist 只接受 host（不含 path/非 443 port）");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 缺少 host");
        }
        String canonical = IDN.toASCII(stripTrailingDot(host.trim()), IDN.USE_STD3_ASCII_RULES)
            .toLowerCase(Locale.ROOT);
        return new AllowlistEntry(canonical, normalizeTenantScope(tenantScope));
    }

    private void validateStoredEntry(AllowlistEntry entry) {
        canonicalEntry(entry.host(), entry.tenantScope());
    }

    private static AllowlistEntry parseStoredEntry(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty allowlist entry");
        }
        String[] parts = raw.trim().split("\\|", -1);
        if (parts.length > 2 || parts[0].isBlank()) {
            throw new IllegalArgumentException("invalid allowlist entry: " + raw);
        }
        String host = parts[0].trim().toLowerCase(Locale.ROOT);
        String tenantScope = parts.length == 2 ? normalizeTenantScope(parts[1]) : null;
        return new AllowlistEntry(host, tenantScope);
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static String normalizeTenantScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean redisUnavailable() {
        return redisDataSource == null || redisDataSource.isUnsatisfied();
    }

    private SetCommands<String, String> allowlistSet() {
        return redisDataSource.get().set(String.class);
    }

    private static void shutdownQuietly(ExecutorService exec) {
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Redis 预留结构：GA tenantScope 恒 null；未来可表达某 host 仅某租户可见。 */
    public record AllowlistEntry(String host, String tenantScope) {
        public AllowlistEntry {
            Objects.requireNonNull(host, "host");
        }

        String encode() {
            return tenantScope == null ? host : host + "|" + tenantScope;
        }
    }

    public record MutationResult(String host, String tenantScope, boolean changed) {
    }
}
