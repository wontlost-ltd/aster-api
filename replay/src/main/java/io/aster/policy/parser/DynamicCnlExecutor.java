package io.aster.policy.parser;

import aster.core.ast.Module;
import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.module.LinkException;
import aster.core.module.ModuleGraphLinker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.api.convert.NamedContextMapper;
import io.aster.policy.module.ModuleResolutionException;
import io.aster.replay.core.module.ModuleGraphResolver;
import io.aster.replay.core.parser.ReplayMappers;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态 CNL 执行器
 *
 * 完整实现 CNL 源代码的动态编译和执行流程：
 * 1. 解析 CNL → AST
 * 2. 降级 AST → Core IR
 * 3. 序列化 Core IR → JSON
 * 4. 使用 GraalVM Polyglot 执行 JSON
 *
 * 适用于 Dashboard 执行场景，无需预先部署策略
 *
 * <p>POJO（S2-1a-1 从 aster-api CDI bean 剥离）：不再持有 CDI 注解，改为构造注入。
 * aster-api 侧由 Task 3 的 CDI producer 负责生产实例并注入真实 {@link ModuleGraphResolver}。
 */
public class DynamicCnlExecutor {

    private static final Logger LOG = Logger.getLogger(DynamicCnlExecutor.class);
    private static final ObjectMapper MAPPER = ReplayMappers.DEFAULT;
    private static final int CACHE_MAX = 2048;

    // Core IR JSON → cached Truffle Source。Source 是不可变编译单元；Context 仍每次新建，
    // 只让共享 Engine 复用同一 coreJson 的 parse/code cache，不跨请求共享执行状态。
    private static final ConcurrentHashMap<String, Source> sourceCache = new ConcurrentHashMap<>();

    // CNL 编译产物缓存：只缓存 parse/lower/serialize 的稳定结果，不缓存执行结果。
    // key 覆盖 source/locale/aliasSet/identifierIndex/aliasesTrusted；含 import 的模块不写入。
    private static final ConcurrentHashMap<CoreIrCacheKey, CompiledCoreIr> coreIrCache =
        new ConcurrentHashMap<>();
    private static final AtomicLong coreIrCacheHits = new AtomicLong();
    private static final AtomicLong coreIrCacheMisses = new AtomicLong();
    private static final AtomicLong coreIrCacheBypasses = new AtomicLong();

    private final ModuleGraphResolver moduleResolver;
    private final boolean modulesEnabled;

    /**
     * 无参构造：无跨模块解析能力（moduleResolver=null）、modules 特性关闭。
     * 保留给现有测试站点（{@code new DynamicCnlExecutor()}）与无模块场景使用。
     */
    public DynamicCnlExecutor() {
        this(null, false);
    }

    /**
     * 构造注入：aster-api 侧由 Task 3 的 CDI producer 传入真实 {@link ModuleGraphResolver}
     * 实现（{@code ModuleResolver}）与 {@code aster.modules.enabled} 配置值。
     *
     * @param moduleResolver 跨模块图解析器，null 表示不支持跨模块 import（modulesEnabled 无关时可为 null）
     * @param modulesEnabled 是否启用跨模块 import 特性
     */
    public DynamicCnlExecutor(ModuleGraphResolver moduleResolver, boolean modulesEnabled) {
        this.moduleResolver = moduleResolver;
        this.modulesEnabled = modulesEnabled;
    }

    /**
     * 进程级共享 Engine。GraalVM 推荐模式：Engine 持有 AST/字节码 cache
     * 与运行时元数据（重资源、线程安全），可被多个 Context 共享；
     * Context 仍按调用新建以保证隔离性，但走 Engine 时只分配运行时状态，
     * 不再每次重做 AST 装载与类初始化。在 evaluate-source 这种"每调用
     * 一份源码"的场景下，未共享 Engine 会让每个并发请求都付一份完整的
     * Truffle 初始化代价（GraalVM 文档明确警告"never create per-request
     * engines"），是 2GB 堆下并发 4 即 OOM 的根因。
     *
     * 关闭 WarnInterpreterOnly 警告——CE 版本无 JIT 是已知情况。
     */
    private static final Engine SHARED_ENGINE = Engine.newBuilder("aster")
        .option("engine.WarnInterpreterOnly", "false")
        .build();

    /**
     * 动态执行结果
     */
    public record ExecutionResult(
        Object result,
        String moduleName,
        String functionName,
        long executionTimeMs
    ) {}

    private record CoreIrCacheKey(String key) {}

    private record CompiledCoreIr(
        String coreJson,
        CoreModel.Module coreModule,
        String moduleName,
        List<String> functionNames,
        String entryFunctionName,
        boolean cacheable
    ) {
        private CompiledCoreIr {
            functionNames = functionNames == null ? List.of() : List.copyOf(functionNames);
        }
    }

    record CacheStats(long coreHits, long coreMisses, long coreBypasses, int coreSize, int sourceSize) {}

    static void clearCachesForTest() {
        coreIrCache.clear();
        sourceCache.clear();
        coreIrCacheHits.set(0);
        coreIrCacheMisses.set(0);
        coreIrCacheBypasses.set(0);
    }

    /**
     * lexicon 热插拔时主动清编译产物缓存（Core IR + Source）。
     *
     * 正确性上非必需——CoreIrCacheKey 已含 lexicon 指纹，某 locale 被禁用/启用后
     * 该 locale 的旧 key 自然不再命中，指纹变化即天然失效。本方法是"锦上添花"：
     * 热插拔属低频运维事件，主动清让陈旧 key 立即释放堆内存（避免被禁 locale 的
     * 编译产物长期滞留缓存），并保证下一次编译走全新 lexicon 状态、无任何时序窗口。
     *
     * 只清编译缓存，不动 coreIrCacheHits/Misses/Bypasses 计数器——那是进程级性能
     * 遥测（累计命中率），与 lexicon 生命周期无关，清零会污染监控读数。
     */
    public static void clearCompilationCaches() {
        coreIrCache.clear();
        sourceCache.clear();
    }

    static CacheStats cacheStatsForTest() {
        return new CacheStats(
            coreIrCacheHits.get(),
            coreIrCacheMisses.get(),
            coreIrCacheBypasses.get(),
            coreIrCache.size(),
            sourceCache.size()
        );
    }

    static String coreIrCacheKeyForTest(
            String source, String locale, aster.core.identifier.IdentifierIndex identifierIndex,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        return coreIrCacheKey(source, locale, identifierIndex, aliasSet, aliasesTrusted).key();
    }

    // ── 以下 static execute* 重载仅测试在用（issue #175）───────────────────
    // 生产入口只有两个：实例方法 executeWithTenantContext（ReplayExecutorAdapter /
    // StandaloneReplayExecutor）与本类的 executeCoreIrJson（/evaluate-json）。
    // 这些 static 重载在 src/main 里**零调用方**，却因 public 而看起来像主 API，
    // 容易误导维护者。保留是因为 34 处测试在用、且它们承载 parity 价值；
    // 标注于此以免被当成生产路径。新代码请勿使用。

    /**
     * 动态执行 CNL 源代码（使用默认 locale）
     *
     * <p><b>仅供测试</b>（issue #175）：生产路径见 {@code executeWithTenantContext}
     * 与 {@link #executeCoreIrJson}。
     *
     * @param source CNL 源代码
     * @param context 评估上下文参数
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @return 执行结果
     */
    public static ExecutionResult execute(String source, Object[] context, String functionName) {
        return execute(source, context, functionName, null);
    }

    /**
     * 动态执行 CNL 源代码（支持多语言）
     *
     * @param source CNL 源代码
     * @param context 评估上下文参数
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 执行结果
     */
    public static ExecutionResult execute(String source, Object[] context, String functionName, String locale) {
        return executeInternal(source, context, functionName, locale, false, null, true, null, null, false);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式）
     *
     * 支持两种上下文格式：
     * 1. 命名格式: { "申请": {...}, "年龄": 25 } - 参数名与函数定义匹配
     * 2. 位置格式: [{...}, 25] - 按位置顺序传参
     *
     * @param source CNL 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(String source, Object context, String functionName, String locale) {
        return executeWithContext(source, context, functionName, locale, null);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式 + 领域词汇翻译）
     *
     * <p>ADR 0014 线C：发布的策略可携带其快照领域词汇，使执行端的规范化阶段
     * 能把用户自定义术语翻译为规范化名称。{@code identifierIndex} 为 null 时
     * 行为与仅内置一致。
     *
     * @param source 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(
            String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex) {
        return executeWithContext(source, context, functionName, locale, identifierIndex, true);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式 + 领域词汇翻译 + 入口兼容开关）
     *
     * @param source 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @param legacyEvaluateSentinel 是否把显式 evaluate 视为历史自动入口哨兵
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(
            String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel) {
        return executeInternal(
            source, context, functionName, locale, true, identifierIndex, legacyEvaluateSentinel,
            null, null, false);
    }

    /**
     * 直接执行 <b>Core IR JSON</b>，不经过 CNL 文本管线（issue #172）。
     *
     * <p>{@code /evaluate-json} 端点收到的 {@code policy} 本就是 Core IR JSON，此前却先
     * fork {@code node aster-convert} 把它转成 CNL、再解析回 Core IR。生产运行镜像
     * （{@code Dockerfile.jvm} = zulu-alpine JRE）**不含 Node**，`ASTER_CLI_PATH` 也从未
     * 配置，{@code resolveCliPath()} 最终回退到裸 {@code "aster-convert"} 必然
     * {@code IOException} —— 该端点在生产 100% 不可用。
     *
     * <p>这里直接反序列化 Core IR 并复用管线**后半段**（选函数 → 映射命名参数 →
     * GraalVM 执行）。注意 {@link #compileCoreIr} 本就以
     * {@code MAPPER.writeValueAsString(coreModule)} 把 Core IR 序列化成 JSON 再喂给
     * polyglot，故此处只是省掉「JSON → CNL → AST → Core IR → JSON」这一圈往返，
     * 执行语义与 CNL 路径同源。
     *
     * <p>不走 Core IR 缓存：输入本身就是编译产物，重复编译成本已经省掉；缓存键需要
     * 对整份 JSON 取哈希，收益不抵开销。
     *
     * @param coreIrJson Core IR JSON（{@code CoreModel.Module} 的序列化形式）
     * @param context 评估上下文（命名 Map 或位置数组）
     * @param functionName 目标函数名；null 时按 @entry / 单 Rule 规则推断
     */
    public static ExecutionResult executeCoreIrJson(
            String coreIrJson, Object context, String functionName) {
        long startTime = System.currentTimeMillis();
        try {
            CoreModel.Module coreModule;
            try {
                coreModule = MAPPER.readValue(coreIrJson, CoreModel.Module.class);
            } catch (Exception e) {
                throw new DynamicExecutionException(
                    "Core IR JSON 解析失败: " + e.getMessage(), e);
            }
            if (coreModule == null || coreModule.decls == null) {
                throw new DynamicExecutionException("Core IR JSON 不含任何声明");
            }

            List<String> functionNames = new java.util.ArrayList<>();
            String entryFunctionName = null;
            for (CoreModel.Decl decl : coreModule.decls) {
                if (decl instanceof CoreModel.Func func) {
                    functionNames.add(func.name);
                    if (entryFunctionName == null && isEntryFunc(func)) {
                        entryFunctionName = func.name;
                    }
                }
            }

            String targetFunction = selectTargetFunction(
                functionName, functionNames, entryFunctionName, false);

            List<CoreModel.Param> functionParams = findFunctionParams(coreModule, targetFunction);
            if (functionParams == null) {
                throw new DynamicExecutionException("未找到函数参数定义: " + targetFunction);
            }
            NamedContextMapper.MappingResult mappingResult =
                NamedContextMapper.mapContext(context, functionParams);
            if (!mappingResult.success()) {
                throw new DynamicExecutionException("参数映射失败: " + mappingResult.error());
            }

            // 重新序列化：入参 JSON 可能含多余空白/字段顺序差异，统一走 MAPPER 产出
            // 与 CNL 路径**字节同源**的 compact JSON，避免两条路径喂给 polyglot 的
            // 输入形态不同。
            String coreJson = MAPPER.writeValueAsString(coreModule);
            Object result = executeWithPolyglot(coreJson, targetFunction, mappingResult.positionalArgs());

            long executionTime = System.currentTimeMillis() - startTime;
            String moduleName = coreModule.name == null ? "" : coreModule.name;
            LOG.infof("Core IR 直接执行完成: %s.%s, 耗时 %dms", moduleName, targetFunction, executionTime);
            return new ExecutionResult(result, moduleName, targetFunction, executionTime);
        } catch (DynamicExecutionException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Core IR 直接执行失败: %s", e.getMessage());
            throw new DynamicExecutionException("Core IR 执行失败: " + e.getMessage(), e);
        }
    }

    /** 判断 Func 是否带 @entry 注解（与 CNL 路径的入口判定语义一致）。 */
    private static boolean isEntryFunc(CoreModel.Func func) {
        if (func.annotations == null) {
            return false;
        }
        for (var ann : func.annotations) {
            if (ann != null && "entry".equals(ann.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CDI entry point used by callers that have tenant context and module feature flags.
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel) {
        return executeWithTenantContext(tenantId, source, context, functionName, locale,
            identifierIndex, legacyEvaluateSentinel, null);
    }

    /**
     * 带用户别名（ADR 0022）的租户上下文执行。aliasSet 为已发布版本冻结的可信快照，
     * 按 allowStructural=true 应用（冻结版本 = 已授权+校验+进 envelope，执行时信任）。
     * null/空 aliasSet 时行为与无别名一致（向后兼容）。
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet) {
        // 兼容重载：aliasSet 视为可信冻结快照（既有内部调用方语义）。
        return executeWithTenantContext(tenantId, source, context, functionName, locale,
            identifierIndex, legacyEvaluateSentinel, aliasSet, true);
    }

    /**
     * 带用户别名 + 显式信任标志的租户上下文执行（ADR 0022 安全边界）。
     *
     * @param aliasesTrusted true=aliasSet 是已发布版本冻结的可信快照（allowStructural=true，
     *   结构词别名放行，因创建时已授权+校验）；false=未冻结的现场用户输入（allowStructural=false，
     *   结构词别名需授权，被 UserAliasValidator 拒）。区分「存储版本执行」与「trial 源码预览」。
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        return executeInternal(
            source, context, functionName, locale, true, identifierIndex, legacyEvaluateSentinel,
            tenantId, moduleResolver, modulesEnabled, aliasSet, aliasesTrusted);
    }

    /**
     * 内部执行方法
     *
     * @param source CNL 源代码
     * @param context 评估上下文
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param mapNamedContext 是否需要映射命名上下文
     * @param identifierIndex 领域词汇索引（null 表示不做用户词翻译）
     * @param legacyEvaluateSentinel 是否把显式 evaluate 视为历史自动入口哨兵
     * @return 执行结果
     */
    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleGraphResolver moduleResolver, boolean modulesEnabled) {
        return executeInternal(source, context, functionName, locale, mapNamedContext, identifierIndex,
            legacyEvaluateSentinel, tenantId, moduleResolver, modulesEnabled, null, true);
    }

    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleGraphResolver moduleResolver, boolean modulesEnabled,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet) {
        // 兼容重载：默认 aliasSet 可信（既有调用方语义）。
        return executeInternal(source, context, functionName, locale, mapNamedContext, identifierIndex,
            legacyEvaluateSentinel, tenantId, moduleResolver, modulesEnabled, aliasSet, true);
    }

    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleGraphResolver moduleResolver, boolean modulesEnabled,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        long startTime = System.currentTimeMillis();

        try {
            // cacheKey == null 表示 lexicon 指纹算不出（罕见异常路径）→ 完全跳过缓存，
            // 直接编译（既不查也不存），保守避免跨 lexicon 版本串用。
            CoreIrCacheKey cacheKey = coreIrCacheKey(source, locale, identifierIndex, aliasSet, aliasesTrusted);
            CompiledCoreIr compiled = cacheKey == null ? null : coreIrCache.get(cacheKey);
            if (compiled != null) {
                coreIrCacheHits.incrementAndGet();
                LOG.debugf("Core IR 缓存命中: module=%s, key=%s", compiled.moduleName(), cacheKey.key());
            } else {
                if (cacheKey != null) {
                    coreIrCacheMisses.incrementAndGet();
                }
                compiled = compileCoreIr(
                    source, locale, identifierIndex, aliasSet, aliasesTrusted,
                    functionName, legacyEvaluateSentinel, tenantId, moduleResolver, modulesEnabled);
                if (cacheKey != null && compiled.cacheable()) {
                    if (coreIrCache.size() >= CACHE_MAX) {
                        coreIrCache.clear();
                    }
                    CompiledCoreIr prev = coreIrCache.putIfAbsent(cacheKey, compiled);
                    if (prev != null) {
                        compiled = prev;
                    }
                } else {
                    coreIrCacheBypasses.incrementAndGet();
                }
            }

            // 2. 确定要执行的函数名（优先级：显式 functionName > @entry 注解 > 单 Rule > 诊断）
            String targetFunction = selectTargetFunction(
                functionName, compiled.functionNames(), compiled.entryFunctionName(), legacyEvaluateSentinel);

            LOG.infof("目标函数: %s.%s", compiled.moduleName(), targetFunction);

            // 4. 映射命名上下文到位置参数（如需）
            Object[] positionalContext;
            if (mapNamedContext) {
                // 查找目标函数的参数定义
                List<CoreModel.Param> functionParams = findFunctionParams(compiled.coreModule(), targetFunction);
                if (functionParams == null) {
                    throw new DynamicExecutionException("未找到函数参数定义: " + targetFunction);
                }

                // 使用 NamedContextMapper 映射
                NamedContextMapper.MappingResult mappingResult = NamedContextMapper.mapContext(context, functionParams);
                if (!mappingResult.success()) {
                    throw new DynamicExecutionException("参数映射失败: " + mappingResult.error());
                }

                positionalContext = mappingResult.positionalArgs();
                if (mappingResult.wasNamedFormat()) {
                    LOG.infof("命名上下文已映射为位置参数，参数数量: %d", positionalContext.length);
                }
                if (!mappingResult.warnings().isEmpty()) {
                    mappingResult.warnings().forEach(w -> LOG.warnf("参数映射警告: %s", w));
                }
            } else {
                // 直接使用位置参数
                positionalContext = context instanceof Object[] arr ? arr : new Object[] { context };
            }

            // 6. 使用 GraalVM Polyglot 执行
            LOG.debugf("使用 GraalVM Polyglot 执行...");
            Object result = executeWithPolyglot(compiled.coreJson(), targetFunction, positionalContext);

            long executionTime = System.currentTimeMillis() - startTime;
            LOG.infof("动态执行完成: %s.%s, 耗时 %dms", compiled.moduleName(), targetFunction, executionTime);

            return new ExecutionResult(
                result,
                compiled.moduleName(),
                targetFunction,
                executionTime
            );

        } catch (InProcessCnlParser.CnlParseException e) {
            throw new DynamicExecutionException("CNL 解析失败: " + e.getMessage(), e);
        } catch (ModuleResolutionException e) {
            throw new ModuleExecutionException(e);
        } catch (LinkException e) {
            throw new ModuleExecutionException(new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_CYCLE,
                e.getMessage(),
                e
            ));
        } catch (DynamicExecutionException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "动态执行失败: %s", e.getMessage());
            throw new DynamicExecutionException("动态执行失败: " + e.getMessage(), e);
        }
    }

    private static CompiledCoreIr compileCoreIr(
            String source, String locale, aster.core.identifier.IdentifierIndex identifierIndex,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted,
            String functionName, boolean legacyEvaluateSentinel,
            String tenantId, ModuleGraphResolver moduleResolver, boolean modulesEnabled) throws Exception {
        // 1. 解析 CNL → AST（传入 locale 以支持多语言，传入 index 以翻译用户词）。
        //    带用户别名（ADR 0022）时走 parseWithUserAliases。allowStructural 按 aliasesTrusted：
        //    冻结版本可信=true（结构词已授权）；trial 现场输入=false（结构词需授权，防绕过）。
        LOG.debugf("解析 CNL 源代码... locale=%s, vocab=%s, aliases=%s, trusted=%s",
            locale, identifierIndex != null, aliasSet != null && !aliasSet.isEmpty(), aliasesTrusted);
        InProcessCnlParser.ParseResult parseResult = (aliasSet != null && !aliasSet.isEmpty())
            ? InProcessCnlParser.parseWithUserAliases(source, locale, identifierIndex, aliasSet, aliasesTrusted)
            : InProcessCnlParser.parse(source, locale, identifierIndex);
        Module astModule = parseResult.module();

        // 先保留原入口诊断顺序：多入口/找不到入口在 lower/link 前报出。
        selectTargetFunction(
            functionName, parseResult.functionNames(), parseResult.entryFunctionName(), legacyEvaluateSentinel);

        // 3. 降级 AST → Core IR
        LOG.debugf("降级 AST → Core IR...");
        CoreLowering lowering = new CoreLowering();
        CoreModel.Module coreModule = lowering.lowerModule(astModule);
        List<Decl.Import> imports = importsOf(astModule);
        boolean cacheable = imports.isEmpty();
        if (!imports.isEmpty() && modulesEnabled) {
            if (moduleResolver == null) {
                throw new ModuleResolutionException(
                    ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                    "Module resolver is unavailable");
            }
            LOG.debugf("解析跨模块 imports: count=%d, tenant=%s", imports.size(), tenantId);
            var graph = moduleResolver.resolveGraph(tenantId, coreModule, imports, locale);
            coreModule = new ModuleGraphLinker().link(graph).merged();
            LOG.debugf("跨模块 imports 已 link: modules=%d, edges=%d", graph.modules().size(), graph.imports().size());
        }

        // 5. 序列化 Core IR → JSON。热路径只喂给 GraalVM eval，compact JSON 足够且减少重 parse 体积。
        LOG.debugf("序列化 Core IR → JSON...");
        String coreJson = MAPPER.writeValueAsString(coreModule);
        LOG.debugf("Core JSON 长度: %d 字符", coreJson.length());

        return new CompiledCoreIr(
            coreJson,
            coreModule,
            parseResult.moduleName(),
            parseResult.functionNames(),
            parseResult.entryFunctionName(),
            cacheable
        );
    }

    private static String selectTargetFunction(
            String functionName, List<String> functionNames, String entryFunctionName,
            boolean legacyEvaluateSentinel) {
        EntryPointSelector.Selection selection = EntryPointSelector.select(
            functionName, functionNames, entryFunctionName, legacyEvaluateSentinel);
        if (selection instanceof EntryPointSelector.Selected selected) {
            return selected.function();
        } else if (selection instanceof EntryPointSelector.Ambiguous ambiguous) {
            throw new AmbiguousEntryException(ambiguous.candidates());
        } else if (selection instanceof EntryPointSelector.NotFound notFound) {
            throw new DynamicExecutionException(
                "未找到指定函数 '" + notFound.requested() + "',可用: " + notFound.candidates());
        } else {
            throw new DynamicExecutionException("CNL 中未找到可执行的函数");
        }
    }

    private static CoreIrCacheKey coreIrCacheKey(
            String source, String locale, aster.core.identifier.IdentifierIndex identifierIndex,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        try {
            java.util.TreeMap<String, Object> payload = new java.util.TreeMap<>();
            payload.put("source", source);
            payload.put("locale", locale);
            payload.put("aliasSet", UserAliasValidator.canonicalJson(aliasSet));
            payload.put("identifierIndex", identifierIndexFingerprint(identifierIndex));
            payload.put("aliasesTrusted", aliasesTrusted);
            // lexicon 是可热插拔/下线的：同一 locale 在 lexicon 被替换/禁用/恢复后 parse 结果会变。
            // 纳入当前 locale 实际解析用的 lexicon 内容指纹，使 lexicon 变更后旧 Core IR 自然失效
            // （否则跨 lexicon 版本命中 = stale/错误 Core IR）。
            String lexiconFingerprint = InProcessCnlParser.lexiconFingerprintForLocale(locale);
            if (lexiconFingerprint == null) {
                // 指纹算不出 → 无法保证 lexicon 未变 → 放弃缓存该次（返回 null，调用方跳过缓存）。
                // 确定性处理，不引入随机源。
                return null;
            }
            payload.put("lexicon", lexiconFingerprint);
            return new CoreIrCacheKey(sha256(MAPPER.writeValueAsString(payload)));
        } catch (Exception e) {
            throw new DynamicExecutionException("构建 Core IR 缓存 key 失败: " + e.getMessage(), e);
        }
    }

    private static String identifierIndexFingerprint(
            aster.core.identifier.IdentifierIndex identifierIndex) throws Exception {
        if (identifierIndex == null) {
            return null;
        }
        java.util.TreeMap<String, Object> payload = new java.util.TreeMap<>();
        payload.put("vocabulary", identifierIndex.getVocabulary());
        payload.put("toCanonical", new java.util.TreeMap<>(identifierIndex.getToCanonicalMap()));
        payload.put("toLocalized", new java.util.TreeMap<>(identifierIndex.getToLocalizedMap()));
        return MAPPER.writeValueAsString(payload);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static Source sourceFor(String coreJson) {
        Source cached = sourceCache.get(coreJson);
        if (cached != null) {
            return cached;
        }
        // 容量兜底：coreJson 集随版本增长，超上限清空重建（近似 LRU，避免无界）。
        if (sourceCache.size() >= CACHE_MAX) {
            sourceCache.clear();
        }
        Source built;
        try {
            built = Source.newBuilder("aster", coreJson, "core-" + Integer.toHexString(coreJson.hashCode()))
                .cached(true)
                .build();
        } catch (Exception e) {
            built = Source.create("aster", coreJson);
        }
        Source prev = sourceCache.putIfAbsent(coreJson, built);
        return prev != null ? prev : built;
    }

    private static List<Decl.Import> importsOf(Module module) {
        if (module == null || module.decls() == null) {
            return List.of();
        }
        return module.decls().stream()
            .filter(Decl.Import.class::isInstance)
            .map(Decl.Import.class::cast)
            .toList();
    }

    /**
     * 使用 GraalVM Polyglot 执行 Core IR JSON
     *
     * AsterLanguage.parse() 使用 Loader 构建程序，返回值取决于入口函数：
     * - 有参函数：返回 LambdaValue（可执行），需要传参调用
     * - 无参函数：直接返回执行结果
     */
    private static Object executeWithPolyglot(String coreJson, String functionName, Object[] context) {
        // 审计 #98（Low，DEFERRED）：此处构建的 Polyglot Context 无墙钟/CPU 看门狗
        // （context.close(true)）。当前安全，因为 DSL 无循环/迭代构造，执行必然有界。
        // 一旦语言引入循环/迭代，必须在此加执行超时看门狗。本 PR 不改，仅记录 defer（见 #98）。
        // 沙箱权限（红队 P1-D：从 allowPublicAccess(true) 收紧，向生产 TrufflePolicyRuntime
        // 的 HostAccess.EXPLICIT 靠拢）。
        // 真正的危险面是 allowPublicAccess(true)——它放开**所有** public 方法/字段/构造器的
        // guest→host 反射访问，一旦有 host 对象泄漏进 guest 值就有 Java 方法调用/类逃逸面。
        // 改用 EXPLICIT 作基线（仅 @HostAccess.Export 方法可被 guest 调用；Builtins 表 +
        // Truffle DSL 节点均已标注），再**仅**重新开放三类数据 interop 访问器：
        //   - allowArrayAccess / allowListAccess / allowMapAccess
        // 因为 DynamicCnlExecutor 把评估上下文作为 Java Map/List 直接传给 guest 函数，
        // guest 的 MemberAccessNode 要读 `context.age` 需要 map 成员访问（否则报
        // "HostObject 不支持成员访问"）。这三者只暴露"读结构化数据"，不暴露任意方法/类，
        // 与 allowPublicAccess(true) 的攻击面有本质区别。
        // 另加 allowHostClassLookup(name -> false) 显式禁止按名查找 host 类。
        // PolyglotAccess.NONE / IOAccess.NONE / 无进程·线程·native 保持不变。
        // ★★ statementLimit 在 Aster 上**实测无效**，保留仅为与
        //     TrufflePolicyRuntime 配置对齐，**不得**作为执行上界依赖。
        //
        //     实测（limit=1/2/10，同一策略连续执行 100,000 次）：全部成功，从不触发。
        //     Aster AST 不产生 Truffle 可计数的 statement，故该限制形同虚设。
        //
        //     ★这同时意味着 TrufflePolicyRuntime 的 P1-R22 审计项
        //     （「+ ResourceLimits 防 DoS」）也是无效防护——生产 /evaluate
        //     自那次审计起就一直以为自己有执行上限。已另行记录，需单独处理。
        //
        //     What-If 重跑的真实执行上界改由 ReplayBatchService 的
        //     wall-clock 超时提供（ADR 0034 §12.4）。
        ResourceLimits limits = ResourceLimits.newBuilder()
            .statementLimit(10_000_000L, null)
            .build();
        try (Context polyglotContext = Context.newBuilder("aster")
                .engine(SHARED_ENGINE)  // 复用进程级 Engine，避免 per-request AOT 重做
                .resourceLimits(limits)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                    .allowArrayAccess(true)   // 允许读数组元素（结构化上下文）
                    .allowListAccess(true)    // 允许读 List 元素
                    .allowMapAccess(true)     // 允许读 Map 成员（context.field）
                    .build())
                .allowHostClassLookup(name -> false)        // 禁止按名查找 host 类（防类逃逸）
                .allowPolyglotAccess(PolyglotAccess.NONE)  // 禁止跨语言访问
                .allowIO(IOAccess.NONE)                     // 禁止文件/网络 I/O
                .allowCreateProcess(false)                  // 禁止创建子进程
                .allowCreateThread(false)                   // 禁止创建线程
                .allowNativeAccess(false)                   // 禁止本地代码访问
                .build()) {

            // 内置函数（算术 / 比较 / 逻辑 / 字符串拼接）由 aster-lang-truffle 的
            // Builtins 静态初始化块权威注册——含 int/double 提升、浮点除法、`+`
            // dual-mode 字符串拼接、运算符符号→canonical 名归一化。此处不再重复注册：
            // 历史上 aster-api 维护了一份并行的、仅整数/仅数值的拷贝（registerBuiltins），
            // 它把字符串强转 Number 而抛 ClassCastException（如 `"Hello, " + name`），
            // 且 REGISTRY 是共享静态表，重复 register 会**覆盖**掉 truffle 的正确实现。
            // 删除后由 truffle 唯一负责。

            // 评估 Core IR JSON - 返回入口函数的 LambdaValue 或无参函数的执行结果
            Value evalResult = polyglotContext.eval(sourceFor(coreJson));

            LOG.debugf("Polyglot eval 返回: canExecute=%b, isNull=%b, isHostObject=%b",
                evalResult.canExecute(), evalResult.isNull(), evalResult.isHostObject());

            Object result;
            if (evalResult.canExecute()) {
                // 返回值是可执行的函数，传参调用
                Value execResult;
                try {
                    if (context == null || context.length == 0) {
                        execResult = evalResult.execute();
                    } else {
                        execResult = evalResult.execute(context);
                    }
                } catch (NullPointerException | org.graalvm.polyglot.PolyglotException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("null")) {
                        // Polyglot 无法转换 null 返回值，视为函数无匹配结果
                        throw new DynamicExecutionException(
                            "函数返回 null（可能是 Match 语句无匹配分支或条件未覆盖所有情况）");
                    }
                    throw new DynamicExecutionException("Polyglot 执行失败: " + msg, e);
                }
                result = convertValue(execResult);
            } else if (evalResult.isHostObject()) {
                // 尝试获取底层 Java 对象
                Object hostObject = evalResult.asHostObject();
                LOG.debugf("Host object 类型: %s", hostObject.getClass().getName());

                if (hostObject instanceof aster.truffle.nodes.LambdaValue lambdaValue) {
                    // 直接调用 LambdaValue.apply()
                    LOG.infof("检测到 LambdaValue，直接调用 apply 方法");
                    Object[] args = context != null ? context : new Object[0];
                    result = lambdaValue.apply(args, null);
                } else {
                    // 其他 host object，直接返回
                    result = hostObject;
                }
            } else if (evalResult.hasMembers() && evalResult.hasMember("apply")) {
                // 检测到类 LambdaValue 对象（有 apply 成员），尝试调用 apply 方法
                LOG.infof("检测到具有 apply 成员的对象，尝试调用 apply");
                try {
                    Value applyMember = evalResult.getMember("apply");
                    if (applyMember.canExecute()) {
                        Value execResult;
                        if (context == null || context.length == 0) {
                            execResult = applyMember.execute();
                        } else {
                            execResult = applyMember.execute(context);
                        }
                        result = convertValue(execResult);
                    } else {
                        // apply 不可执行，尝试通过 as(Class) 获取 LambdaValue
                        result = invokeLambdaViaReflection(evalResult, context);
                    }
                } catch (Exception e) {
                    LOG.warnf("调用 apply 成员失败: %s, 尝试反射调用", e.getMessage());
                    result = invokeLambdaViaReflection(evalResult, context);
                }
            } else {
                // 返回值是无参函数的执行结果，直接使用
                result = convertValue(evalResult);
            }

            return result;

        } catch (DynamicExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new DynamicExecutionException("Polyglot 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过反射调用 LambdaValue（当 Polyglot 无法直接识别时）
     */
    private static Object invokeLambdaViaReflection(Value value, Object[] context) {
        try {
            // 尝试使用 as(Class) 获取 LambdaValue
            aster.truffle.nodes.LambdaValue lambdaValue = value.as(aster.truffle.nodes.LambdaValue.class);
            if (lambdaValue != null) {
                LOG.infof("通过 as(Class) 获取到 LambdaValue，调用 apply");
                Object[] args = context != null ? context : new Object[0];
                return lambdaValue.apply(args, null);
            }
        } catch (Exception e) {
            LOG.debugf("as(LambdaValue.class) 失败: %s", e.getMessage());
        }

        // 尝试通过反射直接调用 apply 方法
        try {
            // 获取底层对象
            Object underlyingObject = null;
            if (value.isProxyObject()) {
                underlyingObject = value.asProxyObject();
            }

            if (underlyingObject != null) {
                java.lang.reflect.Method applyMethod = underlyingObject.getClass().getMethod(
                    "apply", Object[].class, com.oracle.truffle.api.frame.VirtualFrame.class);
                Object[] args = context != null ? context : new Object[0];
                return applyMethod.invoke(underlyingObject, args, null);
            }
        } catch (Exception e) {
            LOG.debugf("反射调用失败: %s", e.getMessage());
        }

        throw new DynamicExecutionException("无法执行 LambdaValue: 不支持的 Polyglot 值类型");
    }

    /**
     * 转换 Polyglot Value 为 Java 对象
     */
    private static Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            int size = (int) value.getArraySize();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++) {
                array[i] = convertValue(value.getArrayElement(i));
            }
            return array;
        }
        if (value.hasMembers()) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValue(value.getMember(key)));
            }
            return map;
        }
        // 默认返回字符串表示
        return value.toString();
    }

    /**
     * 从 Core IR 模块中查找指定函数的参数列表
     *
     * @param module Core IR 模块
     * @param functionName 函数名
     * @return 参数列表，未找到返回 null
     */
    private static List<CoreModel.Param> findFunctionParams(CoreModel.Module module, String functionName) {
        if (module == null || module.decls == null) {
            return null;
        }

        for (CoreModel.Decl decl : module.decls) {
            if (decl instanceof CoreModel.Func func && func.name.equals(functionName)) {
                return func.params != null ? func.params : List.of();
            }
        }

        return null;
    }

    /**
     * 动态执行异常
     */
    public static class DynamicExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DynamicExecutionException(String message) {
            super(message);
        }

        public DynamicExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Structured module/linking failure.
     */
    public static class ModuleExecutionException extends DynamicExecutionException {
        private static final long serialVersionUID = 1L;

        private final ModuleResolutionException resolutionException;

        public ModuleExecutionException(ModuleResolutionException resolutionException) {
            super(resolutionException.getMessage(), resolutionException);
            this.resolutionException = resolutionException;
        }

        public ModuleResolutionException resolutionException() {
            return resolutionException;
        }
    }

    /**
     * 入口函数不唯一。
     */
    public static class AmbiguousEntryException extends DynamicExecutionException {
        private static final long serialVersionUID = 1L;
        // List.copyOf 产物是可序列化的不可变列表；编译器仅看接口类型故告警，此处明确抑制。
        @SuppressWarnings("serial")
        private final List<String> candidates;

        public AmbiguousEntryException(List<String> candidates) {
            super("未指定入口函数，候选函数不唯一: " + candidates);
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public List<String> getCandidates() {
            return candidates;
        }
    }
}
