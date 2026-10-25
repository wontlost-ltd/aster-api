package io.aster.policy.i18n;

import io.aster.common.redis.RedisBroadcastSupervisor;
import io.aster.common.redis.ResilientRedisSubscriber;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一语言包的界面文案（ui-messages）内存仓与热刷新（ADR 0018 Phase 2）。
 *
 * <p><b>启动加载</b>：从 classpath 资源 {@code ui-messages/<locale-id>.json} 读入内存
 * {@link ConcurrentHashMap}。这些资源由 <b>aster-api 自带</b>（{@code src/main/resources/
 * ui-messages/}），其真相源是 {@code @aster-cloud/ui-messages} npm 包 / aster-lang-locales
 * 仓的 {@code ui-messages/}（ADR 0018：界面文案走独立 npm 通道、<b>不进语言包 JVM jar</b>，
 * 故 api 不从 {@code runtimeOnly en/zh/de} 的 jar 读 messages —— 那些 jar 只提供 lexicon
 * SPI）。同步方式见 {@code src/main/resources/ui-messages/README.md}。
 * {@code /api/v1/messages} 直接吐内存 —— 零 DB、零阻塞。
 *
 * <p><b>热刷新</b>：复用现有 Redis pub/sub（同 {@code PolicyCacheManager} 的分布式
 * 失效广播模式，aster-api 已有 Redis，无需引入 Kafka）。发布方（locales 发版流水线
 * 或 admin 写入口）在 channel {@value #RELOAD_CHANNEL} 发**瘦事件**
 * {@code {"locale":"en-US","version":"<sha>"}} —— 只是失效信号，本服务收到后从
 * classpath **重新加载**该 locale 并原子替换内存条目，无需重启。
 *
 * <p><b>版本（KV key 来源）</b>：每个 locale 缓存其内容的 SHA-256。{@code /api/v1/messages}
 * 把它作为 ETag / KV 版本化 key（{@code messages:<locale>:v<sha 前 8 位>}）的来源 ——
 * 文案一变 sha 变 → 前端 KV key 自然换 → 边缘自然刷新（ADR 0018 ③）。
 *
 * <p><b>加载模型</b>：classpath 基线（镜像内必有）之上叠加 Redis 运行时覆盖层（ADR 0021）。
 * 每个 locale 的覆盖层读取有三种结果：命中 → 吐覆盖层；确认不存在 → 吐 classpath 基线；
 * <b>读失败</b>（如冷启动时 Redis 首次建连超过 {@code quarkus.redis.timeout}）→ 内存已有条目则保留，
 * 内存为空则先吐 classpath 基线，并把该 locale 记为"覆盖层待确认"，按
 * {@link RedisBroadcastSupervisor#DEFAULT_BACKOFF} 退避重试直到读成功。读失败绝不等同于"资源缺失"，
 * 因而 Redis 抖动不会让任何 locale 永久 404（#391）。
 *
 * <p>fetch 失败时前端 fallback 到内嵌 en（绝不白屏）—— 那是 cloud 侧的责任；
 * 本服务只保证"已加载的 locale 立即可吐，未加载的返回 empty”。
 */
@ApplicationScoped
public class UiMessagesService {

    private static final Logger LOG = Logger.getLogger(UiMessagesService.class);

    /** Redis pub/sub channel：messages 热刷新失效信号。 */
    static final String RELOAD_CHANNEL = "aster.i18n.messages.reload";

    /** classpath 资源前缀。 */
    private static final String RESOURCE_PREFIX = "ui-messages/";

    /**
     * 合法 locale id 字符集（安全白名单）：仅字母、数字、连字符、下划线（如 {@code en-US} /
     * {@code zh-CN} / {@code hi-IN}）。locale 源自 URL 路径参数 / Redis pub/sub 事件，会拼进
     * classpath 资源路径（{@link #loadFromClasspath}）——白名单从源头杜绝 {@code / . \}
     * 路径穿越（java/path-injection）。合法 locale 不受影响。
     */
    private static final java.util.regex.Pattern LOCALE_ID_PATTERN =
        java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Redis 运行时文案覆盖层 key 前缀（ADR 0021 方案 A）。admin 改的文案存这里，
     * 是 classpath 基线之上的运行时增量。掉电回退 classpath（不丢显示能力，只丢增量）。
     */
    private static final String REDIS_OVERRIDE_PREFIX = "aster:i18n:messages:";

    /** 启动时预加载的 locale（与语言包 jar 对齐）。运行时热刷新可加载任意 locale。 */
    private static final List<String> PRELOAD_LOCALES = List.of("en-US", "zh-CN", "de-DE", "hi-IN");

    /** 每个预加载 locale 一个 gauge：1 = 已可吐（基线或覆盖层），0 = 未加载（端点 404）。 */
    static final String LOADED_METRIC = "aster_ui_messages_loaded";

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    RedisBroadcastSupervisor broadcastSupervisor;

    @Inject
    MeterRegistry registry;

    /** 覆盖层读失败后的重试退避；包内可见，便于单测换成毫秒级。 */
    ResilientRedisSubscriber.Backoff overlayRetryBackoff = RedisBroadcastSupervisor.DEFAULT_BACKOFF;

    /** locale-id → 已加载的 messages 条目（JSON 原文 + sha256）。 */
    private final ConcurrentHashMap<String, MessagesEntry> store = new ConcurrentHashMap<>();

    /** 覆盖层读失败、尚待重试确认的 locale；空集 = 全部 locale 的覆盖层状态已确认。 */
    private final Set<String> overlayPending = ConcurrentHashMap.newKeySet();

    private PubSubCommands<String> pubSubCommands;
    private ResilientRedisSubscriber redisSubscriber;

    /** 单线程：热刷新事件、resubscribe 补齐与覆盖层重试全部串行在此执行。 */
    private ScheduledExecutorService reloadExecutor;

    /** 以下字段由 {@code this} 锁保护：同一时刻至多一个待执行的重试任务。 */
    private boolean overlayRetryScheduled;
    private int overlayRetryFailures;
    private boolean closed;

    /** 一个 locale 的界面文案：原始 JSON 文本 + 其 SHA-256（版本 / ETag 来源）。 */
    public record MessagesEntry(String json, String sha256) {}

    void onStart(@Observes StartupEvent event) {
        PRELOAD_LOCALES.forEach(this::registerLoadedGauge);
        // ADR 0021：先 Redis 运行时覆盖层、miss 回退 classpath 基线（重启后恢复增量）；
        // 覆盖层读失败时先吐基线并后台重试，见 reload。
        PRELOAD_LOCALES.forEach(this::reload);
        logStartupSummary();
        initReloadChannel();
    }

    /** 启动汇总：分别报告已加载、覆盖层待重试、两处都缺失的 locale，不把 Redis 故障误报为资源缺失。 */
    private void logStartupSummary() {
        for (String locale : PRELOAD_LOCALES) {
            MessagesEntry entry = store.get(locale);
            if (entry == null) {
                LOG.warnf("ui-messages 缺失: %s（classpath 无资源且 Redis 无覆盖层）；/api/v1/messages/%s 将返回 404",
                    locale, locale);
                continue;
            }
            LOG.infof("ui-messages 已加载: %s (%d bytes, sha=%s…)%s", locale, entry.json().length(),
                entry.sha256().substring(0, 8), overlayPending.contains(locale) ? "，Redis 覆盖层待重试确认" : "");
        }
    }

    /**
     * 取某 locale 的界面文案。未加载 → {@link Optional#empty()}（端点据此返回 404）。
     */
    public Optional<MessagesEntry> get(String locale) {
        if (locale == null || locale.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(locale.trim()));
    }

    /** 当前已加载的 locale 集合快照。 */
    public java.util.Set<String> loadedLocales() {
        return java.util.Set.copyOf(store.keySet());
    }

    /** 覆盖层读失败、尚待重试确认的 locale 快照（运维 / 测试观察用）。 */
    Set<String> overlayPendingLocales() {
        return Set.copyOf(overlayPending);
    }

    /** 应当就绪的 locale：预加载清单 ∪ 已加载 ∪ 覆盖层待确认（含读失败而从未加载的 locale）。 */
    private Set<String> expectedLocales() {
        Set<String> expected = new LinkedHashSet<>(PRELOAD_LOCALES);
        expected.addAll(store.keySet());
        expected.addAll(overlayPending);
        return expected;
    }

    /**
     * ADR 0020 优化 1：取某 locale 的版本（sha256 前 16 位），供前端拼版本化 KV key。
     * 未加载 → empty。
     *
     * <p>取 16 位（非 8 位）：版本标识的碰撞域要足够大（Codex 审查），16 位 = 64 bit，
     * KV key / manifest payload 都很小不在乎这点长度。前端 body ETag 一致性校验也按此
     * 前缀比对。
     */
    public Optional<String> shortSha(String locale) {
        return get(locale).map(e -> e.sha256().substring(0, 16));
    }

    /**
     * 从 classpath 读 {@code ui-messages/<locale>.json}，计算 sha256。
     * 资源缺失 → empty（不抛，便于 P1 未发版时优雅降级）。
     */
    private Optional<MessagesEntry> loadFromClasspath(String locale) {
        // 安全校验：locale 拼进 classpath 资源路径前必须落在白名单字符集，
        // 非法输入（含 / . \ 等）直接 empty（与"资源缺失"同款优雅降级），杜绝路径穿越。
        if (locale == null || !LOCALE_ID_PATTERN.matcher(locale).matches()) {
            return Optional.empty();
        }
        String resource = RESOURCE_PREFIX + locale + ".json";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                return Optional.empty();
            }
            byte[] bytes = is.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(new MessagesEntry(json, sha256Hex(bytes)));
        } catch (Exception e) {
            LOG.warnf(e, "加载 ui-messages 资源失败: %s", resource);
            return Optional.empty();
        }
    }

    // ──────────────────────────────────────── Redis 运行时覆盖层（ADR 0021）

    /**
     * Redis 读结果三态（区分 miss 与 error，Codex 审查关键点）：
     * <ul>
     *   <li>{@code present} —— key 存在，{@code entry} 是其内容</li>
     *   <li>{@code miss} —— key 不存在 → 调用方回退 classpath 基线</li>
     *   <li>{@code error} —— 瞬时 Redis 故障 → 调用方**保留当前内存**，不回退、不冲掉增量</li>
     * </ul>
     */
    private enum RedisLoadKind { PRESENT, MISS, ERROR }

    private record RedisLoad(RedisLoadKind kind, MessagesEntry entry) {
        static RedisLoad present(MessagesEntry e) { return new RedisLoad(RedisLoadKind.PRESENT, e); }
        static final RedisLoad MISS = new RedisLoad(RedisLoadKind.MISS, null);
        static final RedisLoad ERROR = new RedisLoad(RedisLoadKind.ERROR, null);
    }

    /** 包内可见：单测可覆写以模拟 Redis 可用。 */
    boolean redisAvailable() {
        return redisDataSource != null && redisDataSource.isResolvable() && !redisDataSource.isUnsatisfied();
    }

    /** 读某 locale 的覆盖层原文，key 不存在返回 null、故障抛异常。包内可见：单测可覆写以模拟超时。 */
    String readOverlay(String locale) {
        return redisStrings().get(REDIS_OVERRIDE_PREFIX + locale);
    }

    private ValueCommands<String, String> redisStrings() {
        return redisDataSource.get().value(String.class);
    }

    /** 读 Redis 运行时覆盖层。区分 miss（null）与 error（异常），见 {@link RedisLoad}。 */
    private RedisLoad loadFromRedis(String locale) {
        if (!redisAvailable()) {
            return RedisLoad.MISS; // 无 Redis = 无覆盖层 = 走 classpath 基线
        }
        try {
            String json = readOverlay(locale);
            if (json == null) {
                return RedisLoad.MISS;
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return RedisLoad.present(new MessagesEntry(json, sha256Hex(bytes)));
        } catch (Exception e) {
            // 首次失败打 WARN；同一故障期内的后续失败由重试路径统一计数，避免刷屏。
            if (overlayPending.contains(locale)) {
                LOG.debugf("ui-messages Redis 覆盖层重试读取失败 locale=%s: %s", locale, e.getMessage());
            } else {
                LOG.warnf("ui-messages Redis 覆盖层读取失败 locale=%s: %s（先吐内存/classpath 基线，后台重试）",
                    locale, e.getMessage());
            }
            return RedisLoad.ERROR;
        }
    }

    /**
     * 重新解析某 locale 并更新内存（ADR 0021：先 Redis 覆盖层、真 miss 回退 classpath 基线）。
     *
     * <p>覆盖层<b>读失败</b>时不动已有内存条目（不冲掉运行时增量）；内存为空则先放入 classpath 基线，
     * 保证该 locale 立即可吐；随后记为待确认并安排退避重试。
     *
     * @return 本次确认并写入内存的条目；读失败或两处都缺失 → empty
     */
    Optional<MessagesEntry> reload(String locale) {
        // 非法 locale（来自 pub/sub 事件）不读 Redis、不进重试集合，避免垃圾 key 在故障期被反复重试。
        if (locale == null || !LOCALE_ID_PATTERN.matcher(locale).matches()) {
            return Optional.empty();
        }
        RedisLoad redis = loadFromRedis(locale);
        if (redis.kind() == RedisLoadKind.ERROR) {
            store.computeIfAbsent(locale, l -> loadFromClasspath(l).orElse(null));
            overlayPending.add(locale);
            scheduleOverlayRetry();
            return Optional.empty();
        }
        overlayPending.remove(locale);
        Optional<MessagesEntry> entry = redis.kind() == RedisLoadKind.PRESENT
            ? Optional.of(redis.entry())
            : loadFromClasspath(locale);
        entry.ifPresent(e -> store.put(locale, e));
        return entry;
    }

    // ──────────────────────────────────────── 覆盖层读失败重试（#391）

    /** 安排一次覆盖层重试；已有待执行任务时直接复用，退避随连续失败次数增长。 */
    private synchronized void scheduleOverlayRetry() {
        if (overlayRetryScheduled || closed) {
            return;
        }
        overlayRetryFailures++;
        long delay = overlayRetryBackoff.delayMillis(overlayRetryFailures);
        try {
            executor().schedule(this::retryPendingOverlays, delay, TimeUnit.MILLISECONDS);
            overlayRetryScheduled = true;
        } catch (RejectedExecutionException e) {
            LOG.debug("ui-messages 重试调度器已关闭，放弃覆盖层重试");
            return;
        }
        LOG.debugf("ui-messages 覆盖层重试 #%d 将在 %dms 后执行 pending=%s",
            overlayRetryFailures, delay, overlayPending);
    }

    /** 重试全部待确认 locale；仍失败的会经 {@link #reload} 再次排期，全部确认后打一次恢复日志。 */
    void retryPendingOverlays() {
        synchronized (this) {
            overlayRetryScheduled = false;
        }
        List.copyOf(overlayPending).forEach(this::reload);
        if (overlayPending.isEmpty()) {
            logOverlayRecovered();
        }
    }

    private synchronized void logOverlayRecovered() {
        if (overlayRetryFailures == 0) {
            return;
        }
        LOG.infof("ui-messages Redis 覆盖层已恢复（重试 %d 轮），已加载: %s", overlayRetryFailures, loadedLocales());
        overlayRetryFailures = 0;
    }

    private synchronized ScheduledExecutorService executor() {
        if (reloadExecutor == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "ui-messages-reload");
                thread.setDaemon(true);
                return thread;
            });
            // 停机时丢弃尚未到期的覆盖层重试，只等在途任务收尾，不拖慢停机。
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            reloadExecutor = executor;
        }
        return reloadExecutor;
    }

    /** gauge 回查内存仓，覆盖层重试 / 热刷新改变加载状态后无需重新注册。 */
    private void registerLoadedGauge(String locale) {
        if (registry == null) {
            return;
        }
        Gauge.builder(LOADED_METRIC, this, s -> s.store.containsKey(locale) ? 1.0 : 0.0)
            .tag("locale", locale)
            .description("ui-messages 某 locale 是否已加载可吐（1=loaded, 0=未加载、端点 404）")
            .register(registry);
    }

    /**
     * admin 写运行时文案覆盖（ADR 0021 方案 A）。写 Redis → 本 pod 自更新内存（不等 pub/sub）
     * → 广播 publishReload 让其它 pod 重载。
     *
     * @param locale 目标 locale（调用方须已校验在可用集合）
     * @param json   完整 messages 树 JSON（调用方须已校验合法 + schema/ICU/占位符 parity）
     * @return 写入结果（entry + 是否成功广播到其它 pod），Redis 不可用 → empty（调用方返回 503）
     */
    public Optional<WriteResult> writeOverride(String locale, String json) {
        if (locale == null || locale.isBlank() || json == null) {
            return Optional.empty();
        }
        String key = locale.trim();
        if (!redisAvailable()) {
            LOG.warnf("ui-messages 写覆盖失败：Redis 不可用 locale=%s", key);
            return Optional.empty();
        }
        try {
            redisStrings().set(REDIS_OVERRIDE_PREFIX + key, json);
        } catch (Exception e) {
            LOG.warnf(e, "ui-messages 写覆盖 Redis set 失败 locale=%s", key);
            return Optional.empty();
        }
        // 写成功后本 pod 自更新内存（不等 pub/sub，避免发布失败时本 pod 还吐旧内容）。
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        MessagesEntry entry = new MessagesEntry(json, sha256Hex(bytes));
        store.put(key, entry);
        overlayPending.remove(key); // 刚写入的就是覆盖层真相，无需再重试确认
        // 广播给其它 pod；记录是否成功（Codex 审查：广播失败 → propagated=false，调用方标记
        // degraded，其它 pod 仍会在下次 reload/重启时从 Redis 真相源纠偏，不会永久不一致）。
        boolean propagated = publishReload(key);
        LOG.infof("ui-messages 运行时覆盖已写入: %s → sha=%s… propagated=%s",
            key, entry.sha256().substring(0, 8), propagated);
        return Optional.of(new WriteResult(entry, propagated));
    }

    /** 写入结果：新条目 + 是否成功广播到其它 pod。 */
    public record WriteResult(MessagesEntry entry, boolean propagated) {}

    /**
     * 取某 locale 的 classpath 基线文案（不含运行时覆盖），供写入端点做键集/占位符 parity
     * 校验。基线缺失（locale 未发版）→ empty。
     */
    public Optional<String> baselineJson(String locale) {
        if (locale == null || locale.isBlank()) {
            return Optional.empty();
        }
        return loadFromClasspath(locale.trim()).map(MessagesEntry::json);
    }

    /** 删除结果：是否删除成功 + 是否成功广播到其它 pod（Codex 复审：DELETE 也暴露 degraded）。 */
    public record DeleteResult(boolean removed, boolean propagated) {
        static final DeleteResult UNAVAILABLE = new DeleteResult(false, false);
    }

    /** 删除某 locale 的运行时覆盖（回退 classpath 基线，ADR 0021 回滚路径）。 */
    public DeleteResult deleteOverride(String locale) {
        if (locale == null || locale.isBlank() || !redisAvailable()) {
            return DeleteResult.UNAVAILABLE;
        }
        String key = locale.trim();
        try {
            redisDataSource.get().key(String.class).del(REDIS_OVERRIDE_PREFIX + key);
        } catch (Exception e) {
            LOG.warnf(e, "ui-messages 删除覆盖失败 locale=%s", key);
            return DeleteResult.UNAVAILABLE;
        }
        // 本 pod 自更新（回退 classpath）+ 广播。
        reload(key);
        boolean propagated = publishReload(key);
        LOG.infof("ui-messages 运行时覆盖已删除（回退 classpath 基线）: %s propagated=%s", key, propagated);
        return new DeleteResult(true, propagated);
    }

    // ──────────────────────────────────────── Redis 热刷新

    /**
     * 订阅交给 {@link RedisBroadcastSupervisor}：后台异步建立，失败或断线后退避重订阅，不阻塞启动；
     * down 期间启动加载的文案仍有效。每次（重新）订阅成功后重解析全部应就绪 locale，补齐漏收的热刷新。
     */
    private void initReloadChannel() {
        if (redisDataSource == null || redisDataSource.isUnsatisfied()) {
            LOG.debug("RedisDataSource 未配置，ui-messages 热刷新禁用（启动加载仍有效）");
            return;
        }
        ScheduledExecutorService executor = executor();
        pubSubCommands = redisDataSource.get().pubsub(String.class);
        redisSubscriber = broadcastSupervisor.subscribe("ui_messages_reload_broadcast", RELOAD_CHANNEL,
            payload -> executor.execute(() -> handleReload(payload)),
            () -> executor.execute(this::refreshExpectedLocales));
    }

    /**
     * 重新解析全部应就绪 locale（预加载清单 ∪ 已加载 ∪ 覆盖层待确认，而非仅已加载）。订阅（重新）建立后调用：
     * 既补齐 down 期间漏收的热刷新事件，也让此前读失败 / 未加载的 locale 借 Redis 恢复的时机就绪。
     */
    void refreshExpectedLocales() {
        expectedLocales().forEach(this::reload);
        if (overlayPending.isEmpty()) {
            logOverlayRecovered();
        }
    }

    /**
     * 处理热刷新瘦事件 {@code {"locale":"en-US"}}：重新加载该 locale（ADR 0021：先 Redis
     * 运行时覆盖层、miss 回退 classpath 基线）并原子替换内存条目。包内可见，便于单测直接触发。
     *
     * <p>**Redis error 不冲掉内存（Codex 审查）**：{@link #reload} 区分"真 miss"
     * （key 不存在 → 回退 classpath）与"读 error"（瞬时 Redis 故障 → 返回 empty + 告警）。
     * 这里对 empty 只告警、**保留当前内存条目**，避免瞬时 Redis 故障把运行时增量冲掉。
     */
    void handleReload(String payload) {
        if (payload == null || payload.isBlank() || !payload.stripLeading().startsWith("{")) {
            // 非 JSON 对象（含畸形 payload）直接忽略，避免 Vert.x 急切解析抛异常 + 噪声日志。
            return;
        }
        try {
            JsonObject msg = new JsonObject(payload);
            String locale = msg.getString("locale");
            if (locale == null || locale.isBlank()) {
                LOG.warnf("ui-messages 热刷新事件缺少 locale: %s", payload);
                return;
            }
            reload(locale.trim()).ifPresentOrElse(
                entry -> LOG.infof("ui-messages 热刷新: %s → sha=%s…", locale, entry.sha256().substring(0, 8)),
                // empty = 真 miss（classpath 也无）或 Redis error；两种都**不冲掉当前内存**，error 会后台重试。
                () -> LOG.warnf("ui-messages 热刷新无新内容（miss/error）：%s（保留当前内存）", locale)
            );
        } catch (Exception e) {
            LOG.warnf(e, "解析 ui-messages 热刷新事件失败: %s", payload);
        }
    }

    /**
     * 发布热刷新事件（供 admin 写入口 / 测试用）。瘦事件：只带 locale。
     * @return 是否广播成功（false = pub/sub 不可用或发布异常，调用方据此标记 propagation degraded）
     */
    public boolean publishReload(String locale) {
        if (pubSubCommands == null || locale == null || locale.isBlank()) {
            return false;
        }
        try {
            pubSubCommands.publish(RELOAD_CHANNEL,
                new JsonObject().put("locale", locale.trim()).encode());
            return true;
        } catch (Exception e) {
            LOG.debug("广播 ui-messages 热刷新事件失败", e);
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (this) {
            closed = true;
        }
        if (redisSubscriber != null) {
            redisSubscriber.close();
        }
        if (reloadExecutor != null) {
            reloadExecutor.shutdown();
            try {
                if (!reloadExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    reloadExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                reloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
