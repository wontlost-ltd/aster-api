package io.aster.policy.i18n;

import io.aster.common.redis.RedisBroadcastSupervisor;
import io.aster.common.redis.ResilientRedisSubscriber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * UiMessagesService 覆盖层读失败与恢复（#391）。
 *
 * <p>用可控的假 Redis 覆盖层（覆写包内可见的 {@code redisAvailable} / {@code readOverlay}）模拟
 * 冷启动时 Redis 读超时：读失败必须与"资源缺失"区分开，locale 先吐 classpath 基线、不 404，
 * 并经退避重试或 resubscribe 补齐最终拿到覆盖层。
 */
@DisplayName("UiMessagesService 覆盖层读失败重试")
class UiMessagesOverlayRetryTest {

    private static final List<String> PRELOAD = List.of("en-US", "zh-CN", "de-DE", "hi-IN");
    private static final Duration FAST = Duration.ofMillis(5);

    /**
     * 假 Redis：down 时每次读都像 quarkus.redis.timeout 到期那样抛异常。
     * {@code @Vetoed}：父类的 {@code @ApplicationScoped} 会被继承，不排除会在 QuarkusTest 里形成歧义 bean。
     */
    @Vetoed
    static final class FlakyRedisService extends UiMessagesService {
        volatile boolean down;
        final Map<String, String> overlays = new ConcurrentHashMap<>();
        final AtomicInteger failedReads = new AtomicInteger();
        /** 模拟 classpath 基线缺失的 locale。 */
        final Set<String> classpathMissing = ConcurrentHashMap.newKeySet();
        /** 非 null 时，下一次读取在拿到值之后卡在此闸门上（模拟慢读），读到值即通知 readTaken。 */
        volatile CountDownLatch slowReadGate;
        final CountDownLatch readTaken = new CountDownLatch(1);

        @Override
        boolean redisAvailable() {
            return true;
        }

        @Override
        String readOverlay(String locale) {
            if (down) {
                failedReads.incrementAndGet();
                throw new IllegalStateException("Redis read timed out after 100ms");
            }
            String value = overlays.get(locale);
            CountDownLatch gate = slowReadGate;
            if (gate != null) {
                slowReadGate = null;
                readTaken.countDown();
                awaitQuietly(gate);
            }
            return value;
        }

        @Override
        void writeOverlay(String locale, String json) {
            overlays.put(locale, json);
        }

        @Override
        void deleteOverlay(String locale) {
            overlays.remove(locale);
        }

        @Override
        Optional<UiMessagesService.MessagesEntry> loadFromClasspath(String locale) {
            return classpathMissing.contains(locale) ? Optional.empty() : super.loadFromClasspath(locale);
        }
    }

    /**
     * 假 supervisor：用真实的 {@link ResilientRedisSubscriber} 跑订阅 / 断线 / 重订阅流程，
     * 只把 Redis 连接换成测试可控的 connector。
     */
    @Vetoed
    static final class FakeSupervisor extends RedisBroadcastSupervisor {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        volatile boolean subscribeFails = true;
        final AtomicReference<Runnable> onDrop = new AtomicReference<>();
        final AtomicReference<Consumer<String>> onMessage = new AtomicReference<>();

        @Override
        public synchronized ResilientRedisSubscriber subscribe(String name, String channel,
                                                               Consumer<String> handler, Runnable onUp) {
            onMessage.set(handler);
            ResilientRedisSubscriber sub = new ResilientRedisSubscriber(name, channel, drop -> {
                if (subscribeFails) {
                    throw new IllegalStateException("SUBSCRIBE timed out");
                }
                onDrop.set(drop);
                return () -> { };
            }, scheduler, new ResilientRedisSubscriber.Backoff(FAST, FAST, () -> 1.0), onUp);
            sub.start();
            return sub;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final FlakyRedisService svc = new FlakyRedisService();

    @AfterEach
    void tearDown() {
        svc.shutdown();
    }

    private void useBackoff(Duration initial, Duration max) {
        svc.overlayRetryBackoff = new ResilientRedisSubscriber.Backoff(initial, max, () -> 1.0);
    }

    private static String baseline(String locale) {
        return new UiMessagesService().baselineJson(locale).orElseThrow();
    }

    @Test
    @DisplayName("冷启动 Redis 超时：全部 locale 先吐 classpath 基线，恢复后退避重试拿到覆盖层")
    void coldStartTimeoutThenRecovery() {
        useBackoff(FAST, FAST.multipliedBy(4));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        svc.registry = registry;
        svc.overlays.put("en-US", "{\"common\":{\"save\":\"Overlay Save\"}}");
        svc.down = true;

        svc.onStart(null);

        // 读失败 ≠ 资源缺失：每个 locale 立即可吐（基线），且记为覆盖层待确认。
        for (String locale : PRELOAD) {
            assertThat(svc.get(locale)).as(locale).isPresent();
            assertThat(registry.get(UiMessagesService.LOADED_METRIC).tag("locale", locale).gauge().value())
                .as("gauge " + locale).isEqualTo(1.0);
        }
        assertThat(svc.get("en-US").orElseThrow().json()).isEqualTo(baseline("en-US"));
        assertThat(svc.overlayPendingLocales()).containsExactlyInAnyOrderElementsOf(PRELOAD);

        // 故障期内持续退避重试（不止启动那一次读）。
        await().atMost(Duration.ofSeconds(5))
            .until(() -> svc.failedReads.get() > PRELOAD.size() * 2);

        svc.down = false;
        await().atMost(Duration.ofSeconds(5)).until(() -> svc.overlayPendingLocales().isEmpty());

        assertThat(svc.get("en-US").orElseThrow().json()).contains("Overlay Save");
        assertThat(svc.get("de-DE").orElseThrow().json()).isEqualTo(baseline("de-DE"));
    }

    @Test
    @DisplayName("resubscribe 补齐：重解析此前读失败的 locale（含从未加载的），不只已加载的")
    void resubscribeReloadsPreviouslyFailedLocale() {
        // 重试间隔拉到 1 小时，确保恢复只能来自 resubscribe 回调。
        useBackoff(Duration.ofHours(1), Duration.ofHours(1));
        svc.down = true;
        svc.onStart(null);
        // xx-XX 无 classpath 基线：读失败时内存为空 → 404，但必须记为待确认而非"缺失"。
        svc.handleReload("{\"locale\":\"xx-XX\"}");
        assertThat(svc.get("xx-XX")).isEmpty();
        assertThat(svc.overlayPendingLocales()).contains("xx-XX", "de-DE");

        svc.down = false;
        svc.overlays.put("de-DE", "{\"common\":{\"save\":\"Overlay Speichern\"}}");
        svc.overlays.put("xx-XX", "{\"common\":{\"save\":\"xx\"}}");
        svc.refreshExpectedLocales();

        assertThat(svc.get("de-DE").orElseThrow().json()).contains("Overlay Speichern");
        assertThat(svc.get("xx-XX")).isPresent();
        assertThat(svc.overlayPendingLocales()).isEmpty();
    }

    @Test
    @DisplayName("已加载覆盖层后 Redis 读失败：保留内存中的覆盖层，不被基线冲掉")
    void errorKeepsExistingOverlay() {
        useBackoff(Duration.ofHours(1), Duration.ofHours(1));
        svc.overlays.put("en-US", "{\"common\":{\"save\":\"Overlay Save\"}}");
        svc.onStart(null);
        assertThat(svc.get("en-US").orElseThrow().json()).contains("Overlay Save");

        svc.down = true;
        svc.handleReload("{\"locale\":\"en-US\"}");

        assertThat(svc.get("en-US").orElseThrow().json()).contains("Overlay Save");
        assertThat(svc.overlayPendingLocales()).containsExactly("en-US");
    }

    @Test
    @DisplayName("Redis 正常且无覆盖层：吐 classpath 基线，不进入重试")
    void missServesBaselineWithoutRetry() {
        svc.onStart(null);
        for (String locale : PRELOAD) {
            assertThat(svc.get(locale).orElseThrow().json()).isEqualTo(baseline(locale));
        }
        assertThat(svc.overlayPendingLocales()).isEmpty();
        assertThat(svc.failedReads.get()).isZero();
    }

    @Test
    @DisplayName("慢重试读与 deleteOverride 交错：旧覆盖层不会在删除后被写回，最终与 Redis 一致")
    void slowReadRacingDeleteDoesNotResurrectOverlay() throws Exception {
        svc.overlays.put("en-US", "{\"common\":{\"save\":\"Old Overlay\"}}");
        svc.onStart(null);
        assertThat(svc.get("en-US").orElseThrow().json()).contains("Old Overlay");

        CountDownLatch gate = new CountDownLatch(1);
        svc.slowReadGate = gate;
        Thread slowReload = new Thread(() -> svc.handleReload("{\"locale\":\"en-US\"}"));
        slowReload.start();
        assertThat(svc.readTaken.await(5, TimeUnit.SECONDS)).isTrue(); // 慢读已拿到旧覆盖层

        assertThat(svc.deleteOverride("en-US").removed()).isTrue();
        gate.countDown();
        slowReload.join(5_000);

        assertThat(svc.overlays).doesNotContainKey("en-US");
        assertThat(svc.get("en-US").orElseThrow().json()).isEqualTo(baseline("en-US"));
    }

    @Test
    @DisplayName("慢重试读与 writeOverride 交错：新写入不会被旧读取覆盖")
    void slowReadRacingWriteKeepsNewOverlay() throws Exception {
        svc.overlays.put("en-US", "{\"common\":{\"save\":\"Old Overlay\"}}");
        svc.onStart(null);

        CountDownLatch gate = new CountDownLatch(1);
        svc.slowReadGate = gate;
        Thread slowReload = new Thread(() -> svc.handleReload("{\"locale\":\"en-US\"}"));
        slowReload.start();
        assertThat(svc.readTaken.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(svc.writeOverride("en-US", "{\"common\":{\"save\":\"New Overlay\"}}")).isPresent();
        gate.countDown();
        slowReload.join(5_000);

        assertThat(svc.get("en-US").orElseThrow().json()).contains("New Overlay");
    }

    @Test
    @DisplayName("停机后不再执行任何已排期的覆盖层重试")
    void shutdownCancelsPendingRetries() {
        useBackoff(Duration.ofMillis(200), Duration.ofMillis(200));
        svc.down = true;
        svc.onStart(null);
        int readsAtStart = svc.failedReads.get();
        assertThat(svc.overlayPendingLocales()).isNotEmpty();

        svc.shutdown();

        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2))
            .until(() -> svc.failedReads.get() == readsAtStart);
    }

    @Test
    @DisplayName("classpath 缺失 + Redis 超时：gauge 先为 0（404），重试拿到覆盖层后变为 1")
    void gaugeTransitionsFromZeroToOne() {
        useBackoff(FAST, FAST.multipliedBy(4));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        svc.registry = registry;
        svc.classpathMissing.add("de-DE");
        svc.overlays.put("de-DE", "{\"common\":{\"save\":\"Overlay Speichern\"}}");
        svc.down = true;

        svc.onStart(null);

        assertThat(svc.get("de-DE")).isEmpty();
        assertThat(loadedGauge(registry, "de-DE")).isZero();
        assertThat(svc.overlayPendingLocales()).contains("de-DE");

        svc.down = false;
        await().atMost(Duration.ofSeconds(5)).until(() -> loadedGauge(registry, "de-DE") == 1.0);
        assertThat(svc.get("de-DE").orElseThrow().json()).contains("Overlay Speichern");
    }

    @Test
    @DisplayName("经真实订阅回调：订阅建立 / 断线重订阅都会补齐此前失败的 locale")
    void resubscribeCallbackReloadsFailedLocale() {
        // 重试间隔拉到 1 小时：恢复只能来自订阅回调。
        useBackoff(Duration.ofHours(1), Duration.ofHours(1));
        FakeSupervisor supervisor = new FakeSupervisor();
        svc.broadcastSupervisor = supervisor;
        svc.classpathMissing.add("de-DE");
        svc.down = true;
        try {
            svc.onStart(null);
            svc.subscribeReloadChannel();
            assertThat(svc.get("de-DE")).isEmpty();

            // Redis 恢复：订阅建立 → onUp → 执行器上补齐全部应就绪 locale。
            svc.overlays.put("de-DE", "{\"common\":{\"save\":\"v1\"}}");
            svc.down = false;
            supervisor.subscribeFails = false;
            await().atMost(Duration.ofSeconds(5)).until(() -> svc.get("de-DE").isPresent());
            assertThat(svc.overlayPendingLocales()).isEmpty();

            // 断线期间覆盖层变化（漏收热刷新）→ 重订阅成功后补齐。
            svc.overlays.put("de-DE", "{\"common\":{\"save\":\"v2\"}}");
            supervisor.onDrop.get().run();
            await().atMost(Duration.ofSeconds(5))
                .until(() -> svc.get("de-DE").orElseThrow().json().contains("v2"));

            // 热刷新消息同样经执行器生效。
            svc.overlays.put("en-US", "{\"common\":{\"save\":\"pushed\"}}");
            supervisor.onMessage.get().accept("{\"locale\":\"en-US\"}");
            await().atMost(Duration.ofSeconds(5))
                .until(() -> svc.get("en-US").orElseThrow().json().contains("pushed"));
        } finally {
            supervisor.scheduler.shutdownNow();
        }
    }

    private static double loadedGauge(SimpleMeterRegistry registry, String locale) {
        return registry.get(UiMessagesService.LOADED_METRIC).tag("locale", locale).gauge().value();
    }
}
