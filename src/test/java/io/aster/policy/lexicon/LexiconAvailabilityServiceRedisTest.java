package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * LexiconAvailabilityService 跨副本一致性集成测试（真 Redis Testcontainer）。
 *
 * <p>验证 K3S 多副本"语言开关刷新随机 on/off"根因的修复：
 * <ul>
 *   <li><b>持久（新副本一致）</b>：disable 写 Redis SET → 新建一个 service 实例（模拟重启/扩容的
 *       新副本）调 applyPersistedDisabledSet 能读回并应用 → 新副本与集群一致。</li>
 *   <li><b>广播（活副本即时）</b>：disable/enable 在 channel 发事件，订阅方应用。这里用同进程
 *       publish + 另一实例订阅验证 round-trip（生产中是另一副本）。</li>
 * </ul>
 */
@QuarkusTest
@RedisEnabledTest
@DisplayName("LexiconAvailabilityService 跨副本一致性（真 Redis）")
class LexiconAvailabilityServiceRedisTest {

    @Inject
    RedisDataSource redis;

    @Inject
    LexiconAvailabilityService service;

    @Inject
    io.aster.common.redis.RedisBroadcastSupervisor broadcastSupervisor;

    @BeforeEach
    void cleanup() {
        // 清持久禁用集 + 恢复 registry baseline。
        redis.key(String.class).del(LexiconAvailabilityService.REDIS_DISABLED_SET);
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        LexiconRegistry.getInstance().markAvailable("de-DE");
    }

    @AfterEach
    void restore() {
        redis.key(String.class).del(LexiconAvailabilityService.REDIS_DISABLED_SET);
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        LexiconRegistry.getInstance().markAvailable("de-DE");
    }

    @Test
    @DisplayName("disable 持久到 Redis SET，新副本启动读回 → 跨重启/扩容一致")
    void disablePersistsAndNewReplicaReadsBack() throws Exception {
        // 1) admin disable zh-CN（经服务：本地 + 持久 Redis SET）。
        service.disable("zh-CN");

        Set<String> persisted = redis.set(String.class)
            .smembers(LexiconAvailabilityService.REDIS_DISABLED_SET);
        assertThat(persisted).as("禁用集应持久到 Redis SET").contains("zh-CN");

        // 2) 模拟一个**全新副本**：在本地"恢复" zh-CN（新副本启动时 desiredDisabled 为空），
        //    然后让一个 *未订阅广播* 的全新 service 实例（仅注入真 Redis）跑 reconcileFromRedisSet
        //    ——它必须从 Redis SET 读回并 markUnavailable。用全新实例隔离 @Inject service 的自订阅
        //    回调（否则 disable() 的自广播会异步重入，掩盖"持久读回"这一被测路径）。
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isTrue();

        LexiconAvailabilityService freshReplica = newUnsubscribedReplica();
        invokeReconcile(freshReplica);

        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("新副本启动读回 Redis 禁用集后 zh-CN 应软下线（跨副本一致）")
            .isFalse();
    }

    @Test
    @DisplayName("enable 从 Redis SET 移除，新副本不再读回该禁用")
    void enableRemovesFromPersistedSet() throws Exception {
        service.disable("de-DE");
        assertThat(redis.set(String.class).smembers(LexiconAvailabilityService.REDIS_DISABLED_SET))
            .contains("de-DE");

        service.enable("de-DE");
        assertThat(redis.set(String.class).smembers(LexiconAvailabilityService.REDIS_DISABLED_SET))
            .as("enable 应从持久禁用集移除")
            .doesNotContain("de-DE");

        // 全新副本读回 → de-DE 不在禁用集 → 保持可用。
        LexiconRegistry.getInstance().markAvailable("de-DE");
        invokeReconcile(newUnsubscribedReplica());
        assertThat(LexiconRegistry.getInstance().has("de-DE")).isTrue();
    }

    @Test
    @DisplayName("disable 广播事件被订阅方应用（活副本即时同步）")
    void disableBroadcastAppliedBySubscriber() throws Exception {
        // service 自身在 onStart 已订阅 AVAILABILITY_CHANNEL。直接 publish 一个 disable 事件，
        // 订阅回调应把 zh-CN markUnavailable（模拟"另一副本"发来的广播）。
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isTrue();

        // 订阅在启动后由后台异步建立：先等其就绪，避免广播早于 SUBSCRIBE 到达而丢失。
        await().atMost(Duration.ofSeconds(10)).until(() ->
            broadcastSupervisor.isUp(LexiconAvailabilityService.AVAILABILITY_CHANNEL));
        redis.pubsub(String.class).publish(
            LexiconAvailabilityService.AVAILABILITY_CHANNEL, "disable:zh-CN");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(LexiconRegistry.getInstance().has("zh-CN"))
                .as("收到 disable 广播后订阅方应软下线 zh-CN")
                .isFalse());
    }

    @Test
    @DisplayName("对账修复漏掉的 enable：仅改 Redis SET（不发广播）后，对账把本副本纠回可用")
    void reconcileRecoversFromMissedEnable() throws Exception {
        // 用一个未订阅广播的实例模拟“只靠对账”的活副本：它收不到任何 pub/sub。
        LexiconAvailabilityService replica = newUnsubscribedReplica();

        // 1) SET 含 zh-CN → 对账把本副本下线。
        redis.set(String.class).sadd(LexiconAvailabilityService.REDIS_DISABLED_SET, "zh-CN");
        invokeReconcile(replica);
        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("对账读 SET 后 zh-CN 应下线").isFalse();

        // 2) 另一副本 enable 了 zh-CN（SET 移除），但**本副本漏掉了 enable 广播**——仅改 SET。
        redis.set(String.class).srem(LexiconAvailabilityService.REDIS_DISABLED_SET, "zh-CN");

        // 3) 下一轮对账必须发现“本副本实际下线、SET 已移除”→ markAvailable，纠回可用（最终一致）。
        invokeReconcile(replica);
        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("对账应修复漏掉的 enable：zh-CN 恢复可用（SET 是唯一真相源）")
            .isTrue();
    }

    @Test
    @DisplayName("origin pod 漏掉 enable 也能对账恢复（差分取自注册表真值，非 service 跟踪集）")
    void reconcileRecoversOnOriginPodMissingEnable() throws Exception {
        // 复现 Codex 第二轮抓的残留缺口：本副本是**处理 admin disable 的 origin pod**——它本地直接
        // disable（markUnavailable 返回 true）。若 service 用自跟踪集，origin 路径下 self-broadcast 的
        // disable 事件 markUnavailable 返回 false → 跟踪集漏记该 id → 后续漏掉别处的 enable 时对账无法
        // 恢复。本测用未订阅广播的实例隔离 self-broadcast 竞态，直接验证关键不变量：**对账的差分取自
        // LexiconRegistry.disabledIds() 真值，与“谁先 disable 的、跟踪集是否记过”无关**。
        LexiconAvailabilityService replica = newUnsubscribedReplica();

        // 1) origin 路径：本地直接下线 + 持久（SADD），不经对账写任何跟踪集。
        LexiconRegistry.getInstance().markUnavailable("zh-CN");
        redis.set(String.class).sadd(LexiconAvailabilityService.REDIS_DISABLED_SET, "zh-CN");
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isFalse();

        // 2) 另一副本 enable（SET 移除），origin pod 漏掉 enable 广播——仅改 SET。
        redis.set(String.class).srem(LexiconAvailabilityService.REDIS_DISABLED_SET, "zh-CN");

        // 3) 对账：disabledIds() 真值含 zh-CN（步骤1 的 markUnavailable），SET 已不含 → markAvailable 恢复。
        //    若对账依赖 service 自跟踪集（origin 路径漏记），这里会**留下错误下线**——正是被修缺口。
        invokeReconcile(replica);
        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("origin pod 漏 enable 后对账应恢复 zh-CN（差分基于注册表真值，不漏记）")
            .isTrue();
    }

    private static void invokeReconcile(LexiconAvailabilityService svc) throws Exception {
        var m = LexiconAvailabilityService.class.getDeclaredMethod("reconcileFromRedisSet");
        m.setAccessible(true);
        m.invoke(svc);
    }

    /**
     * 构造一个**未订阅广播**的 service 实例，仅把真 {@link RedisDataSource} 注入它的
     * {@code Instance<RedisDataSource>} 字段——精确模拟"新副本只跑 applyPersistedDisabledSet
     * 读回持久集"这一条路径，不引入 @Inject service 的自订阅回调干扰。
     */
    private LexiconAvailabilityService newUnsubscribedReplica() throws Exception {
        LexiconAvailabilityService fresh = new LexiconAvailabilityService();
        var field = LexiconAvailabilityService.class.getDeclaredField("redisDataSource");
        field.setAccessible(true);
        field.set(fresh, new SingleInstance<>(redis));
        return fresh;
    }

    /** 极简 {@code Instance<T>}：恒有解（isUnsatisfied=false）且 get() 返回固定值。仅测试用。 */
    private static final class SingleInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final T value;

        SingleInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype,
                java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(
                jakarta.enterprise.util.TypeLiteral<U> subtype,
                java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void destroy(T instance) {
            // 测试用固定实例，无需销毁。
        }

        @Override
        public jakarta.enterprise.inject.Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return java.util.List.of(value).iterator();
        }
    }
}
