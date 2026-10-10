package io.aster.policy.api;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.aster.policy.api.cache.PolicyCacheManager;
import io.aster.policy.api.convert.PolicyTypeConverter;
import io.aster.policy.api.model.BatchEvaluationResult;
import io.aster.policy.api.model.BatchRequest;
import io.aster.policy.api.model.CompositionStep;
import io.aster.policy.api.model.EvaluationAttempt;
import io.aster.policy.api.model.ParameterInfo;
import io.aster.policy.api.model.PolicyCompositionResult;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.api.model.PolicyValidationResult;
import io.aster.policy.api.model.StepResult;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.governance.RuleMetadata;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.runtime.ExecutionResult;
import io.aster.policy.runtime.TrufflePolicyRuntime;
import io.aster.policy.tenant.TenantContext;
import io.aster.workflow.DeterminismContext;
import io.aster.api.workflow.PostgresWorkflowRuntime;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.common.JacksonMappers;
import aster.core.ir.CoreModel;
import org.jboss.logging.Logger;

/**
 * Policy evaluation service with caching support (Reactive)
 *
 * 该服务负责策略评估并使用Caffeine缓存来提升性能。
 * 使用Mutiny的Uni实现reactive编程模型。
 * 缓存key基于策略模块、函数名和输入参数的哈希值。
 */
@ApplicationScoped
public class PolicyEvaluationService {

    @Inject
    PolicyCacheManager policyCacheManager;

    @Inject
    PolicyTypeConverter policyTypeConverter;

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    PolicySourceRepository policySourceRepository;

    @Inject
    PolicyCompiler policyCompiler;

    @Inject
    TrufflePolicyRuntime trufflePolicyRuntime;

    @Inject
    io.aster.policy.cache.CompiledPolicyCache compiledPolicyCache;

    @Inject
    io.aster.policy.cache.PolicyVersionResolutionCache versionResolutionCache;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "aster.policy.dynamic-loading.enabled", defaultValue = "false")
    boolean dynamicLoadingEnabled;

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationService.class);

    private static final java.util.List<String> HOT_POLICIES = java.util.List.of(
        "aster.insurance.life.generateLifeQuote",
        "aster.insurance.life.calculateRiskScore",
        "aster.insurance.auto.generateAutoQuote",
        "aster.healthcare.eligibility.checkServiceEligibility",
        "aster.healthcare.claims.processClaim",
        "aster.finance.loan.evaluateLoanEligibility",
        "aster.finance.creditcard.evaluateCreditCardApplication",
        "aster.finance.enterprise_lending.evaluateEnterpriseLoan",
        "aster.finance.personal_lending.evaluatePersonalLoan"
    );

    /** 在途的版本解析（键 tenant:module:function），见 {@link #resolveVersionId}。 */
    private final io.aster.policy.cache.SingleFlight<String> versionResolutions =
        new io.aster.policy.cache.SingleFlight<>();

    /** 在途的按版本加载 + 编译（键 tenant:module:function:versionId），见 {@link #compileOnce}。 */
    private final io.aster.policy.cache.SingleFlight<io.aster.policy.cache.CompiledPolicy> compilations =
        new io.aster.policy.cache.SingleFlight<>();

    /**
     * 评估策略（带缓存，reactive版本，优化了反射性能）
     *
     * @param policyModule 策略模块名
     * @param policyFunction 策略函数名
     * @param context 上下文参数
     * @return Uni包装的评估结果
     */
    @WithSpan("policy.evaluate")
    public Uni<PolicyEvaluationResult> evaluatePolicy(
            @SpanAttribute("tenant.id") String tenantId,
            @SpanAttribute("policy.module") String policyModule,
            @SpanAttribute("policy.function") String policyFunction,
            Object[] context) {

        Object[] normalizedContext = normalizeContext(tenantId, context);

        // 动态加载模式下，需要先解析活跃 versionId（作为结果缓存键）。
        if (dynamicLoadingEnabled) {
            // 热路径优化：版本解析走短 TTL 内存缓存命中时，直接跳过 DB 查询 +
            // requiringNew() 事务 + JDBC 连接占用——这是 k6 高并发下 /evaluate 把
            // 8 连接池压满超时的主因。命中即在当前线程继续，零 DB IO。
            PolicyVersionResolutionCache.Holder cached =
                versionResolutionCache.get(tenantId, policyModule, policyFunction);
            if (cached.isPresent()) {
                return evaluatePolicyWithVersionId(
                    tenantId, policyModule, policyFunction, cached.versionId(), normalizedContext);
            }
            return resolveVersionId(tenantId, policyModule, policyFunction)
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .chain(versionId -> evaluatePolicyWithVersionId(tenantId, policyModule, policyFunction, versionId, normalizedContext));
        }

        // 非动态加载模式，直接使用 null versionId
        return evaluatePolicyWithVersionId(tenantId, policyModule, policyFunction, null, normalizedContext);
    }

    /**
     * 版本解析缓存未命中：在 worker pool 的独立事务里解析，结果回填缓存，避免阻塞 IO 线程。
     * 同一策略的并发未命中合并为一次解析；调用方用 emitOn 把各订阅者分回 worker pool。
     */
    private Uni<String> resolveVersionId(String tenantId, String policyModule, String policyFunction) {
        return versionResolutions.run(resolutionKey(tenantId, policyModule, policyFunction), () -> {
            // stamp 先于 DB 读取：读取期间若发生失效，写回被丢弃
            long stamp = versionResolutionCache.stamp(tenantId, policyModule, policyFunction);
            return Uni.createFrom()
                .item(() -> QuarkusTransaction.requiringNew()
                    .call(() -> loadVersionId(tenantId, policyModule, policyFunction)))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .invoke(versionId -> versionResolutionCache.putIfUnchanged(
                    tenantId, policyModule, policyFunction, versionId, stamp));
        });
    }

    private static String resolutionKey(String tenantId, String policyModule, String policyFunction) {
        return tenantId + ":" + policyModule + ":" + policyFunction;
    }

    /**
     * 策略变更后失效版本解析：先让新请求不再加入在途解析，再推进失效代次并移除缓存条目，
     * 使读到旧版本的在途解析无法写回。module 与 function 都给定时只失效该策略，否则失效整个租户。
     */
    private void invalidateVersionResolution(String tenantId, String policyModule, String policyFunction) {
        boolean single = policyModule != null && !policyModule.isBlank()
            && policyFunction != null && !policyFunction.isBlank();
        if (single) {
            versionResolutions.forget(resolutionKey(tenantId, policyModule, policyFunction));
            versionResolutionCache.invalidate(tenantId, policyModule, policyFunction);
            return;
        }
        versionResolutions.forgetIf(key -> key.startsWith(tenantId + ":"));
        versionResolutionCache.invalidateTenant(tenantId);
    }

    /**
     * 内部方法：使用已解析的 versionId 评估策略
     */
    private Uni<PolicyEvaluationResult> evaluatePolicyWithVersionId(
            String tenantId,
            String policyModule,
            String policyFunction,
            String versionId,
            Object[] normalizedContext) {

        final PolicyCacheKey cacheKey = new PolicyCacheKey(tenantId, policyModule, policyFunction, versionId, normalizedContext);

        // 使用底层Caffeine缓存探测命中状态，避免fromCache标记失真
        Cache cache = policyCacheManager.getPolicyResultCache();
        final boolean cacheHit = policyCacheManager.isCacheHit(cacheKey);

        return cache
            .getAsync(cacheKey, this::evaluatePolicyWithKey)
            .invoke(result -> policyCacheManager.registerCacheEntry(cacheKey, tenantId))
            .onItem().transform(result -> adjustFromCacheFlag(result, cacheHit))
            .onFailure().invoke(throwable -> {
                if (!cacheHit) {
                    policyCacheManager.removeCacheEntry(cacheKey, tenantId);
                }
            });
    }

    /**
     * Internal method with cache key for proper caching
     */
    Uni<PolicyEvaluationResult> evaluatePolicyWithKey(PolicyCacheKey cacheKey) {
        return Uni.createFrom().item(() -> cachedCompiled(cacheKey))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .chain(hit -> hit != null ? Uni.createFrom().item(hit) : compileOnce(cacheKey))
            .map(compiled -> executeCompiled(cacheKey, compiled.getCoreJson(), compiled.getMetadata(),
                System.nanoTime(), resolveDeterminismContext() != null))
            .onFailure().transform(e -> new RuntimeException("Policy evaluation failed", e));
    }

    /**
     * 热路径：用 cacheKey 已带的 versionId 直接探编译缓存，命中即零 DB、不开事务、不占 JDBC 连接
     * （500 RPS 下每次都进事务会占满连接池，请求排队等连接、延迟爆炸）。探测与执行同在一次
     * worker 分派内完成，与命中路径只有一跳。未命中或无 versionId 返回 null。
     */
    private io.aster.policy.cache.CompiledPolicy cachedCompiled(PolicyCacheKey cacheKey) {
        String versionId = cacheKey.getVersionId();
        if (versionId == null) {
            return null;
        }
        return compiledPolicyCache.get(cacheKey.getTenantId(), cacheKey.getPolicyModule(),
            cacheKey.getPolicyFunction(), versionId).orElse(null);
    }

    /**
     * 编译缓存未命中：在独立事务中加载策略源并编译，同一版本的并发未命中合并为一次
     * （single-flight）。执行放在事务之外，连接只在加载与编译期间占用；结果在完成编译的线程上
     * 发出，用 emitOn 把各等待者分回 worker pool。
     */
    private Uni<io.aster.policy.cache.CompiledPolicy> compileOnce(PolicyCacheKey cacheKey) {
        String key = cacheKey.getTenantId() + ":" + cacheKey.getPolicyModule() + ":"
            + cacheKey.getPolicyFunction() + ":" + cacheKey.getVersionId();
        LOG.debugf("使用动态执行路径: %s.%s", cacheKey.getPolicyModule(), cacheKey.getPolicyFunction());
        return compilations.run(key, () -> Uni.createFrom()
                .item(() -> QuarkusTransaction.requiringNew().call(() -> loadAndCompile(cacheKey)))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
            .emitOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 纯执行已编译策略（零 DB 访问）：Truffle 执行 coreJson + 计时。
     * 编译缓存命中的热路径走这里，不占 JDBC 连接、不开事务。
     */
    private PolicyEvaluationResult executeCompiled(PolicyCacheKey cacheKey, String coreJson,
            CompilationMetadata metadata, long startMarker, boolean deterministicTiming) {
        ExecutionResult executionResult = trufflePolicyRuntime.execute(
            coreJson, cacheKey.getContext(), metadata);
        if (!executionResult.success()) {
            throw new RuntimeException(String.format(
                "策略执行失败: %s.%s - %s",
                cacheKey.getPolicyModule(), cacheKey.getPolicyFunction(), executionResult.error()));
        }
        long endMarker = System.nanoTime();
        long durationNanos = deterministicTiming ? 0L : Math.max(0, endMarker - startMarker);
        // 治理字段（ADR 0039/0046）：ruleId / controls 读被执行函数的 Core IR 注解，profile 读模块声明；
        // 解析失败只降级为无元数据，不影响评估结果。
        CoreModel.Module module = parseCoreModule(coreJson);
        RuleMetadata meta = RuleMetadata.of(module, cacheKey.getPolicyFunction());
        return new PolicyEvaluationResult(executionResult.result(), durationNanos / 1_000_000.0, false,
            VerdictView.from(executionResult.result()).orElse(null), meta.ruleId(), meta.controls(), meta.profile());
    }

    /**
     * 把 Core IR JSON 读成 {@link CoreModel.Module}，仅用于提取规则元数据。
     * 失败时记 warn 并返回 null（→ {@link RuleMetadata#NONE}），绝不让评估因元数据失败。
     */
    private static CoreModel.Module parseCoreModule(String coreJson) {
        if (coreJson == null) {
            return null;
        }
        try {
            return JacksonMappers.LENIENT.readValue(coreJson, CoreModel.Module.class);
        } catch (Exception e) {
            LOG.warnf(e, "解析 Core IR 以提取规则元数据失败，按无元数据处理");
            return null;
        }
    }

    /**
     * 加载策略版本ID（用于缓存键）
     */
    private String loadVersionId(String tenantId, String policyModule, String policyFunction) {
        try {
            java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                tenantId, policyModule, policyFunction
            );
            if (catalog.isEmpty()) {
                return null;
            }

            java.util.Optional<PolicyVersion> version = policySourceRepository.findActiveVersion(catalog.get().id);
            return version.map(v -> String.valueOf(v.id)).orElse(null);
        } catch (Exception e) {
            LOG.warnf(e, "加载版本ID失败: %s.%s", policyModule, policyFunction);
            return null;
        }
    }

    /**
     * 动态路径：从数据库加载策略版本并取得编译结果（编译缓存优先，未命中则编译并回填）。须在事务内调用。
     */
    private io.aster.policy.cache.CompiledPolicy loadAndCompile(PolicyCacheKey cacheKey) {
        try {
            // 1. 从数据库加载策略版本。
            // 热路径优化：cacheKey 已带 versionId（动态加载模式下 evaluatePolicy 已解析过活跃
            // 版本，见 loadVersionId + versionResolutionCache）时，直接按 id 单查 findVersionById，
            // **跳过 findActiveCatalog + findActiveVersion 的重复解析**（后者=catalog.findByIdOptional
            // + version.find 两次往返），避免与 evaluatePolicy 里已做的版本解析重复。
            // 仅 versionId 缺失（非动态加载路径）才回退到按 module/function 解析活跃版本。
            java.util.Optional<PolicyVersion> version;
            String preResolvedVersionId = cacheKey.getVersionId();
            Long preResolvedVersionIdLong = null;
            if (preResolvedVersionId != null) {
                // versionId 正常来自 loadVersionId 的 String.valueOf(version.id)（数字）；防御性捕获
                // 非数字（PolicyCacheKey 未约束 versionId 类型），非数字则回退按活跃版本解析。
                try {
                    preResolvedVersionIdLong = Long.valueOf(preResolvedVersionId);
                } catch (NumberFormatException nfe) {
                    preResolvedVersionIdLong = null;
                }
            }
            if (preResolvedVersionIdLong != null) {
                version = policySourceRepository.findVersionById(preResolvedVersionIdLong);
                // ★租户隔离铁律（IDOR）：findVersionById 是裸主键查询，不带租户约束。必须显式校验
                // 查回的版本归属当前 cacheKey 的租户 + module/function 一致——否则被污染的 versionId
                // 可让 A 租户请求加载 B 租户版本。校验失败即当作未找到（不泄漏存在性）。
                if (version.isPresent()) {
                    PolicyVersion v = version.get();
                    boolean sameTenant = java.util.Objects.equals(v.tenantId, cacheKey.getTenantId());
                    boolean sameModule = java.util.Objects.equals(v.moduleName, cacheKey.getPolicyModule());
                    boolean sameFunction = java.util.Objects.equals(v.functionName, cacheKey.getPolicyFunction());
                    if (!sameTenant || !sameModule || !sameFunction) {
                        version = java.util.Optional.empty();
                    }
                }
                if (version.isEmpty()) {
                    throw new RuntimeException(String.format(
                        "策略版本未找到: %s.%s (版本: %s, 租户: %s)",
                        cacheKey.getPolicyModule(),
                        cacheKey.getPolicyFunction(),
                        preResolvedVersionId,
                        cacheKey.getTenantId()
                    ));
                }
            } else {
                java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                    cacheKey.getTenantId(),
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction()
                );

                if (catalog.isEmpty()) {
                    throw new RuntimeException(String.format(
                        "策略未找到: %s.%s (租户: %s)",
                        cacheKey.getPolicyModule(),
                        cacheKey.getPolicyFunction(),
                        cacheKey.getTenantId()
                    ));
                }

                version = policySourceRepository.findActiveVersion(catalog.get().id);
                if (version.isEmpty()) {
                    throw new RuntimeException(String.format(
                        "策略活跃版本未找到: %s.%s (租户: %s)",
                        cacheKey.getPolicyModule(),
                        cacheKey.getPolicyFunction(),
                        cacheKey.getTenantId()
                    ));
                }
            }

            String versionId = String.valueOf(version.get().id);

            // 2. 检查编译缓存
            java.util.Optional<io.aster.policy.cache.CompiledPolicy> cachedPolicy =
                compiledPolicyCache.get(
                    cacheKey.getTenantId(),
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    versionId
                );

            if (cachedPolicy.isPresent()) {
                LOG.debugf("使用缓存的编译结果: %s.%s (version=%s)",
                    cacheKey.getPolicyModule(), cacheKey.getPolicyFunction(), versionId);
                return cachedPolicy.get();
            }

            // 缓存未命中，编译策略并缓存
            CompilationResult compilationResult = policyCompiler.compile(version.get().id);
            if (!compilationResult.isSuccess()) {
                throw new RuntimeException(String.format(
                    "策略编译失败: %s.%s - %s",
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    compilationResult.getErrors()
                ));
            }
            io.aster.policy.cache.CompiledPolicy compiledPolicy = new io.aster.policy.cache.CompiledPolicy(
                versionId,
                version.get().sourceHash,
                compilationResult.getCoreJson(),
                compilationResult.getMetadata()
            );
            compiledPolicyCache.put(
                cacheKey.getTenantId(),
                cacheKey.getPolicyModule(),
                cacheKey.getPolicyFunction(),
                compiledPolicy
            );
            return compiledPolicy;

        } catch (Exception e) {
            throw new RuntimeException(String.format(
                "动态策略执行异常: %s.%s",
                cacheKey.getPolicyModule(),
                cacheKey.getPolicyFunction()
            ), e);
        }
    }

    /**
     * 使缓存失效（针对特定策略，reactive版本）
     */
    public Uni<Void> invalidateCache(String tenantId, String policyModule, String policyFunction, Object[] context) {
        Object[] normalizedContext = normalizeContext(tenantId, context);
        PolicyCacheKey cacheKey = new PolicyCacheKey(tenantId, policyModule, policyFunction, normalizedContext);
        // 策略变更 → 版本解析缓存也要立即失效，避免短 TTL 窗口内继续解析到旧版本。
        invalidateVersionResolution(tenantId, policyModule, policyFunction);
        return invalidateCacheWithKey(cacheKey);
    }

    /**
     * 按租户维度批量失效缓存，可选过滤模块与函数
     */
    public Uni<Void> invalidateCache(String tenantId, String policyModule, String policyFunction) {
        // 版本解析的失效不依赖结果缓存快照：在途解析尚未产生任何结果缓存键
        invalidateVersionResolution(tenantId, policyModule, policyFunction);
        Set<PolicyCacheKey> keys = policyCacheManager.snapshotTenantCacheKeys(tenantId);
        if (keys.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        boolean filterByModule = policyModule != null && !policyModule.isBlank();
        boolean filterByFunction = policyFunction != null && !policyFunction.isBlank();

        java.util.List<PolicyCacheKey> targets = keys.stream()
            .filter(key -> !filterByModule || Objects.equals(key.getPolicyModule(), policyModule))
            .filter(key -> !filterByFunction || Objects.equals(key.getPolicyFunction(), policyFunction))
            .collect(java.util.stream.Collectors.toList());

        if (targets.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        java.util.List<Uni<Void>> invalidations = new java.util.ArrayList<>();
        Cache cache = policyCacheManager.getPolicyResultCache();
        for (PolicyCacheKey key : targets) {
            invalidations.add(cache.invalidate(key)
                .invoke(() -> policyCacheManager.removeCacheEntry(key, key.getTenantId())));
        }

        return Uni.combine().all().unis(invalidations).discardItems();
    }

    /**
     * 仅用于测试：返回指定租户当前缓存键快照
     */
    public Set<PolicyCacheKey> snapshotTenantCacheKeys(String tenantId) {
        return policyCacheManager.snapshotTenantCacheKeys(tenantId);
    }

    /**
     * Internal method with cache key for cache invalidation
     */
    Uni<Void> invalidateCacheWithKey(PolicyCacheKey cacheKey) {
        return policyCacheManager.getPolicyResultCache().invalidate(cacheKey)
            .invoke(() -> policyCacheManager.removeCacheEntry(cacheKey, cacheKey.getTenantId()));
    }

    private Object[] normalizeContext(String tenantId, Object[] context) {
        if (context == null || context.length == 0) {
            return new Object[0];
        }

        Object[] copy = Arrays.copyOf(context, context.length);
        Object first = copy[0];
        String normalizedTenant = normalizeTenant(tenantId);
        if (first instanceof String tenantMarker &&
                normalizeTenant(tenantMarker).equals(normalizedTenant)) {
            return Arrays.copyOfRange(copy, 1, copy.length);
        }

        return copy;
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    private PolicyEvaluationResult adjustFromCacheFlag(PolicyEvaluationResult original, boolean fromCache) {
        if (original == null || original.isFromCache() == fromCache) {
            return original;
        }
        return new PolicyEvaluationResult(
            original.getResult(),
            original.getExecutionTimeMs(),
            fromCache,
            original.getDecision(),
            original.getRuleId(),
            original.getControls(),
            original.getProfile()
        );
    }

    /**
     * 根据当前 workflow 上下文决定是否启用确定性随机计时。
     *
     * 仅当显式处于工作流上下文中时才返回 DeterminismContext，
     * 避免普通策略执行时误用全局默认上下文导致计时错误。
     *
     * @return DeterminismContext 如果处于工作流上下文中，否则返回 null
     */
    private DeterminismContext resolveDeterminismContext() {
        try {
            // 使用 isInWorkflowContext() 检查是否有显式绑定的上下文
            // 避免误用全局默认上下文（非 replay 模式）
            if (workflowRuntime != null && workflowRuntime.isInWorkflowContext()) {
                return workflowRuntime.getDeterminismContext();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 批量评估多个策略（并行执行，显著提升吞吐量，任一失败则全部失败）
     *
     * @param requests 策略评估请求列表
     * @return Uni包装的评估结果列表
     */
    @WithSpan("policy.evaluateBatch")
    public Uni<java.util.List<PolicyEvaluationResult>> evaluateBatch(
            java.util.List<BatchRequest> requests) {

        // 并行执行所有策略评估
        java.util.List<Uni<PolicyEvaluationResult>> unis = requests.stream()
            .map(req -> evaluatePolicy(req.tenantId, req.policyModule, req.policyFunction, req.context))
            .collect(java.util.stream.Collectors.toList());

        // 合并所有Uni结果使用现代API（任一失败则全部失败）
        return Uni.join().all(unis).andFailFast();
    }

    /**
     * 批量评估多个策略（并行执行，收集所有成功和失败结果）
     *
     * @param requests 策略评估请求列表
     * @return Uni包装的批量评估结果（包含成功和失败的结果）
     */
    public Uni<BatchEvaluationResult> evaluateBatchWithFailures(
            java.util.List<BatchRequest> requests) {

        // 为每个请求创建带错误处理的Uni
        java.util.List<Uni<EvaluationAttempt>> attempts = new java.util.ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            final int index = i;
            final BatchRequest req = requests.get(i);

            Uni<EvaluationAttempt> attempt = evaluatePolicy(req.tenantId, req.policyModule, req.policyFunction, req.context)
                .onItem().transform(result -> new EvaluationAttempt(
                    index,
                    req.policyModule,
                    req.policyFunction,
                    result,
                    null
                ))
                .onFailure().recoverWithItem(error -> new EvaluationAttempt(
                    index,
                    req.policyModule,
                    req.policyFunction,
                    null,
                    error.getMessage()
                ));

            attempts.add(attempt);
        }

        // 并行执行所有评估（不会因失败而中断）
        return Uni.join().all(attempts).andCollectFailures()
            .onItem().transform(attemptResults -> {
                // 分离成功和失败结果
                java.util.List<EvaluationAttempt> successes = new java.util.ArrayList<>();
                java.util.List<EvaluationAttempt> failures = new java.util.ArrayList<>();

                for (EvaluationAttempt attempt : attemptResults) {
                    if (attempt.getError() == null) {
                        successes.add(attempt);
                    } else {
                        failures.add(attempt);
                    }
                }

                return new BatchEvaluationResult(
                    successes,
                    failures,
                    successes.size(),
                    failures.size(),
                    requests.size()
                );
            });
    }

    /**
     * 策略组合执行（顺序执行多个策略，可选择前一个策略的结果作为下一个策略的输入）
     *
     * @param steps 策略组合步骤列表（按顺序执行）
     * @param initialContext 初始上下文参数
     * @return Uni包装的最终评估结果和中间结果列表
     */
    public Uni<PolicyCompositionResult> evaluateComposition(
            String tenantId,
            java.util.List<CompositionStep> steps,
            Object[] initialContext) {

        if (steps == null || steps.isEmpty()) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("Composition steps cannot be empty")
            );
        }

        // 从初始上下文开始，顺序链接所有步骤
        return evaluateCompositionStep(tenantId, steps, 0, initialContext,
            new PolicyCompositionResult(new java.util.ArrayList<>(), null));
    }

    /**
     * 递归执行组合步骤
     */
    private Uni<PolicyCompositionResult> evaluateCompositionStep(
            String tenantId,
            java.util.List<CompositionStep> steps,
            int stepIndex,
            Object[] context,
            PolicyCompositionResult accumulator) {

        if (stepIndex >= steps.size()) {
            return Uni.createFrom().item(accumulator);
        }

        CompositionStep step = steps.get(stepIndex);

        // 执行当前步骤
        return evaluatePolicy(tenantId, step.policyModule, step.policyFunction, context)
            .chain(stepResult -> {
                // 记录步骤结果
                accumulator.getStepResults().add(new StepResult(
                    step.policyModule,
                    step.policyFunction,
                    stepResult.getResult(),
                    stepResult.getExecutionTimeMs(),
                    stepIndex
                ));

                // 更新最终结果
                accumulator.setFinalResult(stepResult.getResult());

                // 确定下一步的上下文
                Object[] nextContext;
                if (step.useResultAsInput && stepIndex < steps.size() - 1) {
                    // 使用当前步骤的结果作为下一步的输入
                    nextContext = new Object[]{stepResult.getResult()};
                } else {
                    // 继续使用原始上下文
                    nextContext = context;
                }

                // 递归执行下一步
                return evaluateCompositionStep(tenantId, steps, stepIndex + 1, nextContext, accumulator);
            });
    }

    /**
     * 清空所有缓存（reactive版本）
     */
    @CacheInvalidateAll(cacheName = "policy-results")
    public Uni<Void> clearAllCache() {
        // 清空策略缓存
        policyCacheManager.clearAllCache();
        // 与按策略失效同序：先撤下在途解析，再推进代次清空，使读到旧版本的在途解析无法写回
        versionResolutions.forgetIf(k -> true);
        versionResolutionCache.clear();
        // 返回completed Uni，缓存清空由注解处理
        return Uni.createFrom().voidItem();
    }

    /**
     * 验证策略是否存在并可以加载
     *
     * @param policyModule 策略模块名
     * @param policyFunction 策略函数名
     * @return Uni包装的验证结果
     */
    public Uni<PolicyValidationResult> validatePolicy(String policyModule, String policyFunction) {
        return Uni.createFrom().item(() -> {
            try {
                // 从数据库查找策略（使用当前租户上下文）
                String currentTenant = tenantContext.getCurrentTenant();
                java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                    currentTenant,
                    policyModule,
                    policyFunction
                );

                if (catalog.isEmpty()) {
                    return new PolicyValidationResult(
                        false,
                        "Policy not found in database",
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                // 获取活跃版本
                java.util.Optional<PolicyVersion> version = policySourceRepository.findActiveVersion(catalog.get().id);
                if (version.isEmpty()) {
                    return new PolicyValidationResult(
                        false,
                        "No active version found for policy",
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                // 编译策略以获取元数据
                CompilationResult compilationResult = policyCompiler.compile(version.get().id);
                if (!compilationResult.isSuccess()) {
                    return new PolicyValidationResult(
                        false,
                        "Policy compilation failed: " + compilationResult.getErrors(),
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                CompilationMetadata metadata = compilationResult.getMetadata();

                // 获取返回类型
                String returnType = metadata.getReturnType() != null ? metadata.getReturnType() : "unknown";
                String returnTypeFullName = returnType;

                // 从 Core JSON 解析参数信息
                java.util.List<ParameterInfo> parameters = extractParametersFromCoreJson(
                    compilationResult.getCoreJson(),
                    policyFunction
                );

                return new PolicyValidationResult(
                    true,
                    "Policy exists and is valid and callable",
                    policyModule,
                    policyFunction,
                    parameters,
                    returnType,
                    returnTypeFullName
                );
            } catch (Exception e) {
                return new PolicyValidationResult(
                    false,
                    "Policy validation failed: " + e.getMessage(),
                    policyModule,
                    policyFunction,
                    null,
                    null,
                    null
                );
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 从 Core JSON 解析函数参数信息
     *
     * Core JSON 结构示例:
     * {
     *   "decls": [
     *     {
     *       "@type": "Func",
     *       "name": "evaluateLoanEligibility",
     *       "params": [
     *         { "name": "申请", "type": "..." },
     *         { "name": "年龄", "type": "..." }
     *       ]
     *     }
     *   ]
     * }
     */
    private java.util.List<ParameterInfo> extractParametersFromCoreJson(String coreJson, String functionName) {
        java.util.List<ParameterInfo> parameters = new java.util.ArrayList<>();
        if (coreJson == null || coreJson.isBlank()) {
            return parameters;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(coreJson);
            com.fasterxml.jackson.databind.JsonNode decls = root.get("decls");

            if (decls == null || !decls.isArray()) {
                return parameters;
            }

            for (com.fasterxml.jackson.databind.JsonNode decl : decls) {
                // 支持两种格式：@type (旧版) 和 kind (新版 Core IR)
                String type = decl.has("@type") ? decl.get("@type").asText()
                    : (decl.has("kind") ? decl.get("kind").asText() : null);
                String name = decl.has("name") ? decl.get("name").asText() : null;

                // 查找 Func 类型声明
                if ("Func".equals(type) && functionName.equals(name)) {
                    com.fasterxml.jackson.databind.JsonNode params = decl.get("params");
                    if (params != null && params.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode param : params) {
                            String paramName = param.has("name") ? param.get("name").asText() : "unknown";
                            String paramType = param.has("type") ? param.get("type").toString() : "Object";
                            parameters.add(new ParameterInfo(paramName, paramType, paramType));
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "解析 Core JSON 参数信息失败: %s", functionName);
        }

        return parameters;
    }

}
