package io.aster.policy.api.cache;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 租户级策略结果缓存 epoch（issue #484）。
 *
 * <p>结果缓存键带上租户当前 epoch，失效 = 在共享存储里把 epoch 原子 +1。旧 epoch 的键此后不会再被
 * 构造出来，靠 TTL 回收，因此正确性不再依赖逐键删除、副本本地索引或失效广播一定送达：
 * <ul>
 *   <li>断线期间漏收的失效：本地 epoch 缓存过期后从存储回源即拿到新 epoch；</li>
 *   <li>重启副本遗留在共享 Redis 的条目：键里是旧 epoch，读不到；</li>
 *   <li>在途评估在失效之后回填：它写的是开始时读到的旧 epoch 键，同样读不到（天然栅栏）。</li>
 * </ul>
 *
 * <p>读路径每次都回源会多一次 Redis 往返，所以本地缓存每个租户的 epoch：订阅在线且取值时也在线的条目
 * 可信 {@code trustedTtl}（推送随时把新 epoch 合并进来；TTL 只兜底静默断开的连接），否则只用
 * {@code untrustedTtl}。合并一律取较大值，迟到的旧读数不会覆盖推送来的新 epoch。
 *
 * <p>存储不可用时宁可绕过结果缓存也不返回可能陈旧的结果：取不到 epoch（且本地无未过期条目）即返回
 * empty，调用方直接执行策略、不读不写缓存；失效写不进存储的租户记为「待补发」，补发成功前一律绕过，
 * 由 {@link #retryPendingBumps()} 定期重试，成功后照常广播。
 */
final class TenantCacheEpochs {

    private static final Logger LOG = Logger.getLogger(TenantCacheEpochs.class);

    /** epoch 的共享存储：Redis（多副本）或进程内（未配置 Redis 的单实例）。 */
    interface Store {
        /** 读当前 epoch；不存在时先初始化再返回。 */
        Uni<Long> read(String tenant);

        /** 原子 +1，返回新值；不存在时先初始化。 */
        Uni<Long> bump(String tenant);
    }

    private record Entry(long epoch, long fetchedAtNanos, boolean pushBacked) {}

    private final Store store;
    private final BooleanSupplier channelUp;
    private final LongSupplier nanoClock;
    private final long trustedTtlNanos;
    private final long untrustedTtlNanos;
    private final long failureBackoffNanos;
    private final BiConsumer<String, Long> onBumped;

    private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();
    private final Set<String> pendingBumps = ConcurrentHashMap.newKeySet();

    /** 存储读失败后的退避截止时刻（nanoTime）：期间不再回源，避免 Redis 挂掉时每个请求都等满超时。 */
    private volatile long storeUnavailableUntil;
    private volatile boolean storeUnavailableSet;

    /**
     * @param channelUp 失效广播订阅当前是否在线
     * @param onBumped  epoch 推进成功（含补发）后回调，用于广播新 epoch
     */
    TenantCacheEpochs(Store store, BooleanSupplier channelUp, LongSupplier nanoClock,
                      Duration trustedTtl, Duration untrustedTtl, Duration failureBackoff,
                      BiConsumer<String, Long> onBumped) {
        this.store = store;
        this.channelUp = channelUp;
        this.nanoClock = nanoClock;
        this.trustedTtlNanos = trustedTtl.toNanos();
        this.untrustedTtlNanos = untrustedTtl.toNanos();
        this.failureBackoffNanos = failureBackoff.toNanos();
        this.onBumped = onBumped;
    }

    /**
     * 租户当前 epoch。empty 表示此刻无法确认（存储不可用或失效待补发），调用方必须绕过结果缓存。
     */
    Uni<OptionalLong> current(String tenant) {
        if (pendingBumps.contains(tenant)) {
            return Uni.createFrom().item(OptionalLong.empty());
        }
        long now = nanoClock.getAsLong();
        Entry entry = local.get(tenant);
        if (entry != null && isFresh(entry, now)) {
            return Uni.createFrom().item(OptionalLong.of(entry.epoch()));
        }
        if (storeUnavailableSet && now - storeUnavailableUntil < 0) {
            return Uni.createFrom().item(OptionalLong.empty());
        }
        boolean upAtStart = channelUp.getAsBoolean();
        return store.read(tenant)
            .map(epoch -> {
                storeUnavailableSet = false;
                if (pendingBumps.contains(tenant)) {
                    // 读的同时本副本的失效没写进存储：读到的仍是旧 epoch，不能用。
                    return OptionalLong.empty();
                }
                return OptionalLong.of(merge(tenant, new Entry(epoch, now, upAtStart)).epoch());
            })
            .onFailure().recoverWithItem(e -> {
                storeUnavailableUntil = nanoClock.getAsLong() + failureBackoffNanos;
                storeUnavailableSet = true;
                LOG.debugf("读取租户结果缓存 epoch 失败，本次绕过结果缓存 tenant=%s: %s", tenant, e.toString());
                return OptionalLong.empty();
            });
    }

    /**
     * 失效租户结果缓存：推进存储里的 epoch。完成后本副本立即用新 epoch；写入失败则记为待补发并丢弃本地
     * epoch，在补发成功前该租户的评估一律绕过结果缓存。返回的 Uni 不因存储失败而失败。
     */
    Uni<Void> invalidate(String tenant) {
        return store.bump(tenant)
            .invoke(epoch -> applyBump(tenant, epoch))
            .onFailure().recoverWithItem(e -> {
                markPending(tenant);
                LOG.warnf("推进租户结果缓存 epoch 失败，补发成功前该租户绕过结果缓存 tenant=%s: %s", tenant, e.toString());
                return null;
            })
            .replaceWithVoid();
    }

    /** 收到其他副本（或自己）广播的新 epoch。 */
    void onRemoteEpoch(String tenant, long epoch) {
        merge(tenant, new Entry(epoch, nanoClock.getAsLong(), true));
    }

    /**
     * 订阅（重新）建立：down 期间可能漏收推送，丢弃全部本地 epoch，下一次读取回源。
     * 回调与「订阅置为 up」之间的极短窗口由 {@code trustedTtl} 兜底。
     */
    void onChannelUp() {
        local.clear();
    }

    /** 补发失效写入失败的租户；由调用方定期在非事件循环线程上调用（阻塞等待存储）。 */
    void retryPendingBumps(Duration timeout) {
        for (String tenant : List.copyOf(pendingBumps)) {
            try {
                long epoch = store.bump(tenant).await().atMost(timeout);
                applyBump(tenant, epoch);
                LOG.infof("租户结果缓存 epoch 补发成功 tenant=%s epoch=%d", tenant, epoch);
            } catch (RuntimeException e) {
                LOG.debugf("租户结果缓存 epoch 补发仍失败 tenant=%s: %s", tenant, e.toString());
                return;
            }
        }
    }

    boolean hasPendingBumps() {
        return !pendingBumps.isEmpty();
    }

    int pendingBumpCount() {
        return pendingBumps.size();
    }

    /** 本地缓存的 epoch（测试用），不存在时为 empty。 */
    OptionalLong cached(String tenant) {
        Entry entry = local.get(tenant);
        return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.epoch());
    }

    private void applyBump(String tenant, long epoch) {
        merge(tenant, new Entry(epoch, nanoClock.getAsLong(), channelUp.getAsBoolean()));
        pendingBumps.remove(tenant);
        onBumped.accept(tenant, epoch);
    }

    private void markPending(String tenant) {
        pendingBumps.add(tenant);
        local.remove(tenant);
    }

    private boolean isFresh(Entry entry, long now) {
        long ttl = entry.pushBacked() && channelUp.getAsBoolean() ? trustedTtlNanos : untrustedTtlNanos;
        return now - entry.fetchedAtNanos() < ttl;
    }

    /** epoch 只进不退：较大者胜出；相同 epoch 取较新的取值时刻（续期）。 */
    private Entry merge(String tenant, Entry incoming) {
        return local.merge(tenant, incoming, (old, nu) -> {
            if (nu.epoch() != old.epoch()) {
                return nu.epoch() > old.epoch() ? nu : old;
            }
            return nu.fetchedAtNanos() - old.fetchedAtNanos() >= 0 ? nu : old;
        });
    }
}
