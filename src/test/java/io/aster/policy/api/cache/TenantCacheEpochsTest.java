package io.aster.policy.api.cache;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户结果缓存 epoch 的本地缓存、合并与降级语义（issue #484）。存储与时钟均为假实现，时序完全可控。
 */
class TenantCacheEpochsTest {

    private static final Duration TRUSTED = Duration.ofSeconds(5);
    private static final Duration UNTRUSTED = Duration.ofMillis(500);
    private static final Duration BACKOFF = Duration.ofSeconds(1);
    private static final String T = "tenant-a";

    /** 可控的共享存储：记录读次数，可切换为失败。 */
    static final class FakeStore implements TenantCacheEpochs.Store {
        final Map<String, Long> values = new HashMap<>();
        int reads;
        boolean failing;

        @Override
        public synchronized Uni<Long> read(String tenant) {
            reads++;
            if (failing) {
                return Uni.createFrom().failure(new IllegalStateException("redis down"));
            }
            return Uni.createFrom().item(values.computeIfAbsent(tenant, k -> 100L));
        }

        @Override
        public synchronized Uni<Long> bump(String tenant) {
            if (failing) {
                return Uni.createFrom().failure(new IllegalStateException("redis down"));
            }
            long next = values.getOrDefault(tenant, 100L) + 1;
            values.put(tenant, next);
            return Uni.createFrom().item(next);
        }

        /** 其他副本推进 epoch（本副本没收到推送）。 */
        synchronized void bumpElsewhere(String tenant) {
            values.merge(tenant, 101L, (old, ignored) -> old + 1);
        }
    }

    private final FakeStore store = new FakeStore();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicBoolean channelUp = new AtomicBoolean(true);
    private final List<String> published = new ArrayList<>();
    private final TenantCacheEpochs epochs = new TenantCacheEpochs(store, channelUp::get, clock::get,
        TRUSTED, UNTRUSTED, BACKOFF, (tenant, epoch) -> published.add(tenant + "=" + epoch));

    private OptionalLong current() {
        return epochs.current(T).await().indefinitely();
    }

    private void advance(Duration d) {
        clock.addAndGet(d.toNanos());
    }

    @Test
    void 订阅在线时本地epoch在可信时长内不回源_过期后回源拿到漏收的失效() {
        assertEquals(OptionalLong.of(100L), current());
        store.bumpElsewhere(T);
        advance(TRUSTED.minusMillis(1));
        assertEquals(OptionalLong.of(100L), current(), "可信时长内用本地 epoch");
        assertEquals(1, store.reads);

        advance(Duration.ofMillis(1));
        assertEquals(OptionalLong.of(101L), current(), "漏收推送的失效最迟在可信时长后生效");
        assertEquals(2, store.reads);
    }

    @Test
    void 订阅不在线时只按短TTL缓存() {
        channelUp.set(false);
        assertEquals(OptionalLong.of(100L), current());
        store.bumpElsewhere(T);
        advance(UNTRUSTED);
        assertEquals(OptionalLong.of(101L), current());
    }

    @Test
    void 订阅断开后此前取得的epoch立即降为短TTL() {
        assertEquals(OptionalLong.of(100L), current());
        store.bumpElsewhere(T);
        channelUp.set(false);
        advance(UNTRUSTED);
        assertEquals(OptionalLong.of(101L), current());
    }

    @Test
    void 订阅不在线时取得的epoch在订阅恢复后仍不可信() {
        channelUp.set(false);
        assertEquals(OptionalLong.of(100L), current());
        store.bumpElsewhere(T);
        channelUp.set(true);
        advance(UNTRUSTED);
        assertEquals(OptionalLong.of(101L), current(), "down 期间取得的值可能早于漏收的推送，只能按短 TTL 使用");
    }

    @Test
    void 订阅恢复回调丢弃本地epoch_下一次读取立即回源() {
        assertEquals(OptionalLong.of(100L), current());
        store.bumpElsewhere(T);
        epochs.onChannelUp();
        assertEquals(OptionalLong.of(101L), current());
    }

    @Test
    void 推送的新epoch立即生效_迟到的旧读数不会覆盖() {
        assertEquals(OptionalLong.of(100L), current());
        epochs.onRemoteEpoch(T, 105L);
        assertEquals(OptionalLong.of(105L), current());
        assertEquals(OptionalLong.of(105L), epochs.cached(T));

        epochs.onRemoteEpoch(T, 103L);
        assertEquals(OptionalLong.of(105L), epochs.cached(T), "epoch 只进不退");

        // 回源读到的旧值（推送之前发出的读取）同样不能把 epoch 拉回去。
        advance(TRUSTED);
        store.values.put(T, 104L);
        assertEquals(OptionalLong.of(105L), current());
    }

    @Test
    void 本副本失效后立即使用新epoch并广播() {
        assertEquals(OptionalLong.of(100L), current());
        epochs.invalidate(T).await().indefinitely();
        assertEquals(OptionalLong.of(101L), current());
        assertEquals(List.of(T + "=101"), published);
    }

    @Test
    void 读取存储失败时绕过缓存_退避期内不再回源_恢复后照常() {
        store.failing = true;
        assertEquals(OptionalLong.empty(), current(), "取不到 epoch 必须绕过结果缓存");
        assertEquals(OptionalLong.empty(), current());
        assertEquals(1, store.reads, "退避期内不再回源");

        store.failing = false;
        advance(BACKOFF);
        assertEquals(OptionalLong.of(100L), current());
    }

    @Test
    void 存储失败时本地未过期的epoch照常使用() {
        assertEquals(OptionalLong.of(100L), current());
        store.failing = true;
        assertEquals(OptionalLong.of(100L), current());
    }

    @Test
    void 失效写不进存储时该租户绕过缓存直到补发成功() {
        assertEquals(OptionalLong.of(100L), current());
        store.failing = true;
        epochs.invalidate(T).await().indefinitely();
        assertTrue(epochs.hasPendingBumps());
        assertEquals(OptionalLong.empty(), current(), "本副本的失效尚未生效，不能再用旧 epoch");

        // 待补发期间收到推送、或存储恢复可读，都不能提前恢复缓存：存储里仍是旧 epoch。
        store.failing = false;
        epochs.onRemoteEpoch(T, 100L);
        advance(TRUSTED);
        assertEquals(OptionalLong.empty(), current());
        assertTrue(published.isEmpty());

        epochs.retryPendingBumps(Duration.ofSeconds(1));
        assertFalse(epochs.hasPendingBumps());
        assertEquals(OptionalLong.of(101L), current());
        assertEquals(List.of(T + "=101"), published, "补发成功后照常广播");
    }

    @Test
    void 补发仍失败时保留待补发() {
        store.failing = true;
        epochs.invalidate(T).await().indefinitely();
        epochs.retryPendingBumps(Duration.ofSeconds(1));
        assertEquals(1, epochs.pendingBumpCount());
        assertEquals(OptionalLong.empty(), current());
    }

    @Test
    void 租户之间互不影响() {
        assertEquals(OptionalLong.of(100L), current());
        assertEquals(OptionalLong.of(100L), epochs.current("tenant-b").await().indefinitely());
        epochs.invalidate("tenant-b").await().indefinitely();
        assertEquals(OptionalLong.of(100L), current());
        assertEquals(OptionalLong.of(101L), epochs.current("tenant-b").await().indefinitely());
    }
}
