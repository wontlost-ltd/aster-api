package io.aster.policy.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.aster.policy.compiler.CompilationMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * 编译策略缓存
 *
 * <p>L1 内存缓存，避免重复编译策略。缓存内容是不可变的编译产物
 * （coreJson + 元数据），键为 {@code tenant:module:function:versionId}。
 *
 * <p><b>有界 + 权重驱逐（内存墙治理）</b>：早期实现用无界 {@code ConcurrentHashMap}，
 * 高基数多租户场景下（每个被访问过的策略常驻堆，无 TTL 无驱逐）会持续吃满堆内存，
 * 是内存墙与 OOM 风险的根因。现改为 Caffeine 有界缓存：{@code maximumWeight}
 * （按字节的堆预算，Caffeine 采用 Window-TinyLFU 近似驱逐策略，非严格 LRU）+
 * {@code expireAfterAccess} 冷条目回收，把堆占用钳制在预算内。
 *
 * <p><b>为何按字节（weight）而非按条数（maximumSize）</b>：生产真实策略的 coreJson
 * 大小差异极大——实测最简策略编译后 coreJson pretty ~4.7KB，而一个真实业务策略
 * （生产 finance.loan，5 条带结构化返回的规则）编译后 coreJson pretty 达 <b>~23KB</b>
 * （源码 27× 膨胀）。若按固定条数上限：真实策略下 8192 条 × 25KB ≈ 205MB 会触发 OOM，
 * 小策略下又严重浪费。按字节 weight 则以堆预算为界，自适应任意大小分布，防 OOM 最硬。
 * weight 计入所有 String 内容字节（coreJson 主导 + key/versionId/sourceHash/metadata）
 * + 固定对象/节点开销。<b>非 Latin1 字符串按 UTF-16（2 字节/字符）计</b>——多语言策略
 * （中文/天城文字面量）的 coreJson 含非 ASCII 字符时整条 String 退化为 UTF-16 存储，
 * 若只按字符数会低估一半堆占用（Codex 审查 P1）。这是本仓首个 weigher，由真实大小差异证明其必要。
 *
 * <p><b>驱逐安全性</b>：键含不可变的 {@code versionId}（DB 主键），且策略内容一旦
 * 创建即冻结，故重编译产生恒定结果——驱逐一个条目最坏只是下次访问重编译一次，
 * 绝不影响正确性（与 {@code policy-compile-by-version} Quarkus 缓存的不变式一致）。
 * 生产从不显式 invalidate 本缓存（version-scoped 键永不失效）。
 *
 * <p><b>容量调优</b>：默认预算 {@code max-bytes=96MB}（约占 384MB 堆 1/4，为其余缓存
 * +工作集+GC 头空间留余量）。按 23KB/真实策略估算约容纳 ~4000 条真实业务策略、或
 * ~24000 条小策略。堆更大的部署调大 {@code aster.policy.compile-cache.max-bytes}。
 */
@ApplicationScoped
public class CompiledPolicyCache {

    private static final Logger LOG = Logger.getLogger(CompiledPolicyCache.class);

    /** 无 CDI 时（单元测试直接 new）的默认堆预算（字节）：96MB ≈ 384MB 堆的 1/4。 */
    static final long DEFAULT_MAX_BYTES = 96L * 1024 * 1024;

    /** 无 CDI 时的默认冷条目回收时长。 */
    static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofHours(2);

    /**
     * 每条目的固定对象/节点开销（字节），叠加到各 String 内容字节上作为 weight。覆盖
     * CompiledPolicy/CompilationMetadata 对象头 + 各 String 对象头 + Caffeine 节点开销。
     * 各 String 的**内容字节**（coreJson/key/versionId/sourceHash/metadata）单独按
     * {@link #stringHeapBytes} 精确计入，故此常数只需覆盖固定的对象/节点头。
     */
    static final int ENTRY_OVERHEAD_BYTES = 512;

    private final Cache<String, CompiledPolicy> cache;

    /**
     * 无参构造：供单元测试直接实例化（{@code new CompiledPolicyCache()}），
     * 使用默认堆预算与回收时长。
     */
    public CompiledPolicyCache() {
        this(DEFAULT_MAX_BYTES, (int) DEFAULT_EXPIRE_AFTER_ACCESS.toMinutes());
    }

    /**
     * CDI 注入构造：从配置读取堆预算（字节）与冷条目回收时长（分钟）。
     *
     * @param maxBytes             缓存堆预算上限（字节），按 coreJson 字节 weight 驱逐
     * @param expireAfterAccessMin 冷条目在多少分钟未被访问后回收（0 表示不启用按访问过期）
     */
    @Inject
    public CompiledPolicyCache(
            @ConfigProperty(name = "aster.policy.compile-cache.max-bytes", defaultValue = "100663296") long maxBytes,
            @ConfigProperty(name = "aster.policy.compile-cache.expire-after-access-min", defaultValue = "120") int expireAfterAccessMin) {
        // 类型化的 weigher/removalListener 会收窄 Caffeine 的类型参数为 <String, CompiledPolicy>，
        // 故直接在一条链上构建并赋给类型化字段（与 ApiKeyVerifierService 同款），
        // 避免中间 Caffeine<Object,Object> 变量导致的类型推断冲突。
        // 配置错成 0/负数会静默退化成 1 字节缓存（几乎缓存不了任何东西）→ 显式告警并回退默认，
        // 避免排障时被"缓存命中率骤降"误导（Codex 审查 P3）。
        long effectiveMaxBytes = maxBytes;
        if (maxBytes <= 0) {
            LOG.warnf("aster.policy.compile-cache.max-bytes 配置为 %d（应为正数），回退默认 %d 字节",
                    maxBytes, DEFAULT_MAX_BYTES);
            effectiveMaxBytes = DEFAULT_MAX_BYTES;
        }
        Caffeine<String, CompiledPolicy> builder = Caffeine.newBuilder()
                .maximumWeight(effectiveMaxBytes)
                .weigher((String key, CompiledPolicy value) -> entryWeight(key, value))
                .recordStats()
                .removalListener((String key, CompiledPolicy value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        LOG.debugf("编译缓存驱逐: %s (原因=%s)", key, cause);
                    }
                });
        if (expireAfterAccessMin > 0) {
            builder = builder.expireAfterAccess(Duration.ofMinutes(expireAfterAccessMin));
        }
        this.cache = builder.build();
        LOG.infof("CompiledPolicyCache 初始化: maxBytes=%d (%.0fMB), expireAfterAccessMin=%d",
                effectiveMaxBytes, effectiveMaxBytes / (1024.0 * 1024.0), expireAfterAccessMin);
    }

    /**
     * 估算一个 String 的堆占用字节数。JDK CompactStrings 下：纯 Latin1（每字符 ≤ 0xFF，
     * 含 ASCII/JSON）用 byte[] 存 1 字节/字符；一旦含**任一**非 Latin1 字符（如中文、
     * 天城文——多语言策略引擎的真实场景），整条 String 退化为 UTF-16 存储 2 字节/字符。
     * 故按是否含非 Latin1 字符区分 1×/2× length，使 weight 成为堆字节的可靠上界。
     */
    static long stringHeapBytes(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) > 0xFF) {
                return 2L * len; // 含非 Latin1 → 整条 UTF-16 存储
            }
        }
        return len; // 纯 Latin1 → 1 字节/字符
    }

    /**
     * 估算一个缓存条目的堆占用字节数（作为 Caffeine weight）。计入所有 String 内容字节
     * （coreJson 主导，叠加 key/versionId/sourceHash/metadata 三段——metadata 的
     * parameterSchema 理论上非常量级）+ 固定对象/节点开销。非 Latin1 字符串按 UTF-16 计
     * （见 {@link #stringHeapBytes}），保证 weight 不低估、预算是堆占用上界。
     * Caffeine weight 为 int，超大条目按 Integer.MAX_VALUE 封顶（384MB 堆下不会触及）。
     */
    private static int entryWeight(String key, CompiledPolicy value) {
        long w = ENTRY_OVERHEAD_BYTES + stringHeapBytes(key);
        if (value != null) {
            w += stringHeapBytes(value.getCoreJson());
            w += stringHeapBytes(value.getVersionId());
            w += stringHeapBytes(value.getSourceHash());
            w += stringHeapBytes(value.getSourceEnvelopeSha256());
            CompilationMetadata meta = value.getMetadata();
            if (meta != null) {
                w += stringHeapBytes(meta.getFunctionSignature());
                w += stringHeapBytes(meta.getParameterSchema());
                w += stringHeapBytes(meta.getReturnType());
            }
        }
        return (int) Math.min(w, Integer.MAX_VALUE);
    }

    /**
     * 获取缓存的编译策略
     */
    public Optional<CompiledPolicy> get(String tenantId, String module, String function, String versionId) {
        String key = buildKey(tenantId, module, function, versionId);
        CompiledPolicy policy = cache.getIfPresent(key);

        if (policy != null) {
            LOG.debugf("编译缓存命中: %s", key);
        }

        return Optional.ofNullable(policy);
    }

    /**
     * 缓存编译策略
     */
    public void put(String tenantId, String module, String function, CompiledPolicy policy) {
        String key = buildKey(tenantId, module, function, policy.getVersionId());
        cache.put(key, policy);
        // 每次编译缓存写入一条日志——高基数多租户冷启动/驱逐后重编译时会非常吵，
        // 降到 debug（与 get 命中日志同级）。缓存总体状态由 getStats()/驱逐日志观测。
        LOG.debugf("编译策略已缓存: %s", key);
    }

    /**
     * 失效指定策略的所有版本缓存
     */
    public void invalidate(String tenantId, String module, String function) {
        String prefix = buildPrefix(tenantId, module, function);
        int removed = 0;

        // Caffeine 的 asMap() 视图支持前缀扫描 + 删除，语义与原 ConcurrentHashMap 一致。
        var it = cache.asMap().keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (key.startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            LOG.infof("失效编译缓存: %s, 移除 %d 个版本", prefix, removed);
        }
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        long size = cache.estimatedSize();
        cache.invalidateAll();
        LOG.infof("清空编译缓存，共 %d 个策略", size);
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        // estimatedSize 是近似值；先 cleanUp 结算挂起的驱逐，使小规模计数精确
        // （单元测试依赖精确条数）。
        cache.cleanUp();
        return new CacheStats(cache.estimatedSize());
    }

    private String buildKey(String tenantId, String module, String function, String versionId) {
        return String.format("%s:%s:%s:%s", tenantId, module, function, versionId);
    }

    private String buildPrefix(String tenantId, String module, String function) {
        return String.format("%s:%s:%s:", tenantId, module, function);
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(long size) {
        @Override
        public String toString() {
            return "CacheStats{size=" + size + '}';
        }
    }
}
