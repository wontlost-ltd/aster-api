package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import io.aster.policy.parser.DynamicCnlExecutor;
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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 语言包**可用性**（软上/下线）的**跨副本一致性**服务。
 *
 * <p><b>解决的问题</b>：admin 通过 {@code /api/v1/admin/lexicons/{id}/disable|enable} 软下线/恢复
 * 一门语言，底层是 {@link LexiconRegistry#markUnavailable}/{@code markAvailable}——但那是**每个
 * 副本各自的内存 {@code desiredDisabled} 集**。K3S 多副本下，admin 请求经负载均衡只打到**一个**
 * 副本→只有它改了状态，其余副本不知道→{@code /api/v1/lexicons} 各副本返回不同→前端语言开关
 * “刷新随机 on/off”。
 *
 * <p><b>方案</b>（复用 aster-api 已有 Redis，同 {@code UiMessagesService}/{@code PolicyCacheManager}
 * 的分布式失效广播范式，不引入 Kafka）：
 * <ul>
 *   <li><b>持久化（唯一真相源）</b>：禁用集存 Redis SET {@value #REDIS_DISABLED_SET}。重启/扩容/
 *       新建副本启动时读回，保证一致（纯广播无法覆盖新副本）。</li>
 *   <li><b>广播（活副本即时）</b>：disable/enable 在 channel {@value #AVAILABILITY_CHANNEL} 发瘦事件
 *       {@code "disable:<id>"} / {@code "enable:<id>"}；各副本订阅后本地 {@code markUnavailable}/
 *       {@code markAvailable}（幂等）→ 立即跨副本生效。</li>
 *   <li><b>周期对账（最终一致）</b>：Redis pub/sub 是 best-effort，**丢消息是正常语义**（订阅断开、
 *       pod 卡顿、启动“读 SET 后/订阅前”窗口）。活副本若漏一条事件，仅靠广播会**永久停在旧内存
 *       状态**。故除启动读回外，再：(a) 订阅成功后立即再对账一次（关启动窗口）；(b) 低频
 *       {@value #RECONCILE_PERIOD_SECONDS}s 定时对账，把本副本调到 SET 状态。SET 始终是真相源。</li>
 * </ul>
 *
 * <p><b>fail-open</b>：Redis 未配置 / 故障时，退化为**纯本副本内存**行为（与改造前一致，不抛、不拒）
 * ——可用性下线本就是运维便利特性，不是安全控制；不应让 Redis 抖动拒绝 admin 操作或拖垮启动。
 * en-US backbone 永远不可被下线（{@link LexiconRegistry#markUnavailable} 自身拒绝；本服务也不把它
 * 写进 Redis SET，避免持久真相源被永不可执行的禁用意图污染）。
 */
@ApplicationScoped
public class LexiconAvailabilityService {

    private static final Logger LOG = Logger.getLogger(LexiconAvailabilityService.class);

    /** Redis SET：当前被软下线的 lexicon id 集（持久真相源，跨重启/扩容）。 */
    static final String REDIS_DISABLED_SET = "aster:lexicon:disabled";

    /** Redis pub/sub channel：可用性变更广播。 */
    static final String AVAILABILITY_CHANNEL = "aster.lexicon.availability";

    /** en-US 是 backbone，永不可下线（与 {@link LexiconRegistry} 一致），归一化后比较。 */
    private static final String BACKBONE_ID = "en-us";

    /** 低频对账周期（秒）：兜底 pub/sub 丢消息，让活副本最终收敛到 Redis SET。 */
    static final long RECONCILE_PERIOD_SECONDS = 45L;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    RedisBroadcastSupervisor broadcastSupervisor;

    private PubSubCommands<String> pubSubCommands;
    private ResilientRedisSubscriber subscriber;
    private ExecutorService applyExecutor;
    private ScheduledExecutorService reconcileScheduler;

    /**
     * 启动：先从 Redis SET 读回持久禁用集并应用（保证本副本与集群一致），再订阅广播 channel，
     * 订阅后再对账一次（关“读 SET 后/订阅前”窗口），最后挂低频定时对账兜底丢消息。
     */
    void onStart(@Observes StartupEvent event) {
        if (redisUnavailable()) {
            LOG.debug("RedisDataSource 未配置：lexicon 可用性跨副本同步禁用（本副本内存行为不变）");
            return;
        }
        applyExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lexicon-availability-apply");
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
     * down 期间本副本内存行为仍有效，定时对账兜底。
     * 每次（重新）订阅成功后对账一次，关闭“读 SET 后/订阅前”及断线期间漏收广播的窗口。
     */
    private void initBroadcastChannel() {
        pubSubCommands = redisDataSource.get().pubsub(String.class);
        subscriber = broadcastSupervisor.subscribe("lexicon_availability_broadcast", AVAILABILITY_CHANNEL,
            payload -> applyExecutor.execute(() -> applyRemoteEvent(payload)),
            () -> applyExecutor.execute(this::reconcileFromRedisSet));
    }

    private void initReconcileSchedule() {
        reconcileScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lexicon-availability-reconcile");
            t.setDaemon(true);
            return t;
        });
        // 对账提交到 applyExecutor，与广播 apply 共用单线程 → 串行应用，避免对账与广播交错抖动。
        reconcileScheduler.scheduleWithFixedDelay(
            () -> applyExecutor.execute(this::reconcileFromRedisSet),
            RECONCILE_PERIOD_SECONDS, RECONCILE_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 把本副本可用性**对账到 Redis SET（唯一真相源）**：SET 中的 id 全 {@code markUnavailable}；
     * **本副本实际下线、但 SET 已不含**的 id 全 {@code markAvailable}（修漏掉的 enable 广播）。幂等。
     *
     * <p>差分的“本副本下线集”取自 {@link LexiconRegistry#disabledIds()}——注册表是唯一可靠的本地
     * 下线视图。<b>不</b>用 service 自行跟踪的集合：那会在 origin pod 漏记（本地 disable 后
     * self-broadcast 的 markUnavailable 返回 false，跟踪集不会记入该 id），导致该 pod 漏掉别处发来的
     * enable 时对账也无法恢复（Codex 第二轮复审抓出的残留缺口）。
     *
     * <p>fail-open：Redis 读失败则保持本副本当前状态（不抛、不清）。
     */
    private void reconcileFromRedisSet() {
        if (redisUnavailable()) {
            return;
        }
        Set<String> desired;
        try {
            desired = disabledSet().smembers(REDIS_DISABLED_SET);
        } catch (Exception e) {
            // 读 Redis 失败不致命：本副本保持当前可用性（fail-open），下次对账/广播补齐。
            LOG.warn("对账读取 Redis 持久禁用集失败（fail-open，本副本保持当前可用性）", e);
            return;
        }
        for (String id : desired) {
            applyMarkUnavailable(id);
        }
        // 本副本实际下线、但 SET 已不含的 → 恢复（覆盖丢失的 enable 事件）。
        // 用 disabledIds() 归一化集合与 SET 差分；SET 成员若大小写不一致，先归一化再比较。
        Set<String> desiredNormalized = normalize(desired);
        Set<String> toEnable = new HashSet<>(LexiconRegistry.getInstance().disabledIds());
        toEnable.removeAll(desiredNormalized);
        for (String id : toEnable) {
            applyMarkAvailable(id);
        }
        if (!desired.isEmpty() || !toEnable.isEmpty()) {
            LOG.infof("lexicon 可用性对账完成：禁用集=%s，恢复=%s", desired, toEnable);
        }
    }

    /** 归一化 id 集合，口径必须与 {@link LexiconRegistry} 的 normalizeId 严格一致，便于集合差分。 */
    private static Set<String> normalize(Set<String> ids) {
        Set<String> out = new HashSet<>(ids.size());
        for (String id : ids) {
            String n = normalizeId(id);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** 单 id 归一化：与 {@link LexiconRegistry#normalizeId} 同口径（trim + 小写 ROOT + 下划线转连字符）。 */
    private static String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 收到其它副本的广播事件 → 本地应用（幂等）。格式 "disable:<id>" / "enable:<id>"。 */
    private void applyRemoteEvent(String payload) {
        if (payload == null) {
            return;
        }
        int sep = payload.indexOf(':');
        if (sep <= 0 || sep == payload.length() - 1) {
            LOG.warnf("忽略畸形可用性广播事件: %s", payload);
            return;
        }
        String op = payload.substring(0, sep);
        String id = payload.substring(sep + 1);
        switch (op) {
            case "disable" -> applyMarkUnavailable(id);
            case "enable" -> applyMarkAvailable(id);
            default -> LOG.warnf("忽略未知可用性广播 op: %s", payload);
        }
    }

    /**
     * lexicon 可用性变更的**唯一本地生效点**：改注册表状态 + 主动清 Core IR 编译缓存。
     *
     * <p>disable/enable/远程广播/周期对账四条路径全部经此，避免"改了注册表却漏清缓存"的
     * 遗漏类 bug（本仓跨副本一致性历史上多次栽在"某条路径漏改"）。
     *
     * <p>只在**本副本真正发生状态变化时**（{@code markUnavailable} 返回 true）才清缓存：
     * 幂等重放（已是该状态）不触发无谓清空，避免高频对账周期性抖掉热缓存。清缓存对正确性
     * 非必需（{@code CoreIrCacheKey} 含 lexicon 指纹自然失效），是主动释放陈旧编译产物。
     *
     * @return 本副本是否发生状态变化（透传 {@link LexiconRegistry#markUnavailable} 语义）
     */
    private boolean applyMarkUnavailable(String id) {
        boolean changed = LexiconRegistry.getInstance().markUnavailable(id);
        if (changed) {
            DynamicCnlExecutor.clearCompilationCaches();
        }
        return changed;
    }

    /** 见 {@link #applyMarkUnavailable}：恢复语言的对称生效点，状态真变化时同样主动清编译缓存。 */
    private boolean applyMarkAvailable(String id) {
        boolean changed = LexiconRegistry.getInstance().markAvailable(id);
        if (changed) {
            DynamicCnlExecutor.clearCompilationCaches();
        }
        return changed;
    }

    /**
     * 软下线一门语言：本地 markUnavailable + 持久到 Redis SET + 广播。en-US backbone 拒绝下线时
     * （core 返回 false）**不写 Redis、不广播**，避免持久真相源被永不可执行的禁用意图污染。
     *
     * @return 本副本是否发生状态变化（与 {@link LexiconRegistry#markUnavailable} 一致语义）
     */
    public boolean disable(String id) {
        boolean changed = applyMarkUnavailable(id);
        if (isBackbone(id)) {
            // core 已拒绝；不污染 Redis SET / 广播。changed 必为 false。
            return changed;
        }
        persistAndBroadcast(id, true);
        return changed;
    }

    /**
     * 恢复一门语言：本地 markAvailable + 从 Redis SET 移除 + 广播。
     *
     * @return 本副本是否发生状态变化
     */
    public boolean enable(String id) {
        boolean changed = applyMarkAvailable(id);
        persistAndBroadcast(id, false);
        return changed;
    }

    /**
     * 持久（Redis SET 唯一真相源）+ 广播（让其它副本立即应用）。Redis 不可用则降级为纯本副本
     * 内存（fail-open）——admin 操作不被 Redis 抖动拒绝。持久与广播相互独立：即便本实例订阅未建立
     * （{@code pubSubCommands == null}），仍临时取 pubsub 句柄尽力广播，缩小不一致窗口；广播失败也
     * fail-open（SET 已保底，其它副本下次对账/重启补齐）。
     */
    private void persistAndBroadcast(String id, boolean disable) {
        if (redisUnavailable()) {
            return;
        }
        try {
            if (disable) {
                disabledSet().sadd(REDIS_DISABLED_SET, id);
            } else {
                disabledSet().srem(REDIS_DISABLED_SET, id);
            }
        } catch (Exception e) {
            LOG.warnf(e, "持久 lexicon 可用性到 Redis 失败 id=%s disable=%s（本副本已生效）", id, disable);
        }
        try {
            PubSubCommands<String> ps = pubSubCommands != null
                ? pubSubCommands
                : redisDataSource.get().pubsub(String.class);
            ps.publish(AVAILABILITY_CHANNEL, (disable ? "disable:" : "enable:") + id);
        } catch (Exception e) {
            LOG.warnf(e, "广播 lexicon 可用性失败 id=%s disable=%s（本副本已生效，其它副本由对账/重启补齐）",
                id, disable);
        }
    }

    private static boolean isBackbone(String id) {
        return BACKBONE_ID.equals(normalizeId(id));
    }

    private boolean redisUnavailable() {
        return redisDataSource == null || redisDataSource.isUnsatisfied();
    }

    private SetCommands<String, String> disabledSet() {
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
}
