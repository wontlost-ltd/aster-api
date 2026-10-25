package io.aster.policy.i18n;

import io.aster.common.redis.ResilientRedisSubscriber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
            return overlays.get(locale);
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
}
