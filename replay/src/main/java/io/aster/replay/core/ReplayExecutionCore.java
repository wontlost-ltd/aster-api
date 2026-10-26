package io.aster.replay.core;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.VocabularyLoader;
import aster.core.lexicon.SemanticTokenKind;
import aster.truffle.trace.TraceAccess;
import aster.truffle.trace.TraceCollector;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.replay.ReplayMetadata;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * replay 执行编排的 core 三阶段 API（P0-A S2-1a-0 Task 4，从
 * {@code PolicyEvaluationResource.evaluateSource} 逐字抽取）。
 *
 * <p>★本类不依赖 Quarkus/CDI/JAX-RS——只依赖 aster-lang core/truffle（已由
 * replay 的 build.gradle 声明）。日志用 {@link java.util.logging.Logger}
 * （JDK 内置，避免为 core 引入新日志依赖）。
 *
 * <p>三阶段对应 evaluateSource 原有的三段编排：
 * <ol>
 *   <li>{@link #execute}：建 vocabIndex/aliasSet → arm trace → 调 executor → finally drain。
 *       executor 抛出的异常<b>原样透传</b>（不捕获、不包装）——finally 已保证 drain 先执行，
 *       随后同一异常实例继续向上抛，由调用方（aster-api resource）现有四类 catch 处理。</li>
 *   <li>{@link #buildDecisionTrace}：把 drain 出的原始 steps 转成 {@link DecisionTrace}。</li>
 *   <li>{@link #computeReplayMetadata}：调 {@link ReplayMetadata#compute} 并对内部
 *       意外异常做诚实降级（NON_REPLAYABLE + reason）。</li>
 * </ol>
 */
public final class ReplayExecutionCore {

    private static final Logger LOG = Logger.getLogger(ReplayExecutionCore.class.getName());

    /**
     * 阶段一：执行 replay。建 vocabIndex（先）/aliasSet（后，保原有调用顺序）、
     * 按 {@code trace || effectiveReplayCapture} 决定是否 arm trace collector、
     * 调 {@code executor.execute(...)}，finally 中 drain。
     *
     * <p>★executor 抛出的运行时异常不捕获——finally 块保证 drain 先执行清理
     * ThreadLocal，随后同一异常实例继续向上抛（原样透传），不进入本方法的正常返回路径，
     * 调用方不会看到 {@link ExecutionPhaseResult}。
     */
    public ExecutionPhaseResult execute(ReplayExecutionRequest req, ReplayExecutor executor) {
        IdentifierIndex vocabIndex = buildVocabularyIndex(req.vocabulary());
        Map<SemanticTokenKind, List<String>> aliasSet = buildAliasSet(req.aliasSet());

        boolean captureTraceSteps = req.trace() || req.effectiveReplayCapture();
        TraceAccess.DrainResult traceDrainResult = null;
        if (captureTraceSteps) {
            TraceAccess.armCurrentThread(TraceCollector.withDefaults());
        } else {
            // worker 线程会复用；未请求 trace 时不 arm，但先清掉任何历史残留，避免后续 eval
            // 被旧 ThreadLocal 收集器意外捕获。正常路径下 requested 分支的 finally 会负责清理。
            TraceAccess.drainCurrentThread();
        }

        ReplayExecutorResult execResult;
        try {
            execResult = executor.execute(
                req.tenantId(),
                req.source(),
                req.context(),
                req.functionName(),
                req.locale(),
                vocabIndex,
                req.legacyEvaluateSentinel(),
                aliasSet,
                req.aliasesTrusted());
        } finally {
            if (captureTraceSteps) {
                traceDrainResult = TraceAccess.drainCurrentThread();
            }
        }

        return new ExecutionPhaseResult(execResult, traceDrainResult);
    }

    /**
     * 阶段二：把执行结果 + drain 结果组装为 {@link DecisionTrace}。
     * {@code captureTrace}（调用方传 {@code trace || effectiveReplayCapture}）为
     * {@code false} 时返回 {@code null}——trace=true 供前端展示，replayCapture=true
     * 供 traceHash 计算，两者任一为真都构建。
     */
    public DecisionTrace buildDecisionTrace(
            ReplayExecutorResult execResult, TraceAccess.DrainResult drain, boolean captureTrace) {
        if (!captureTrace) {
            return null;
        }
        List<DecisionTrace.TraceStep> steps = drain == null ? List.of() : toTraceSteps(drain.steps());
        return new DecisionTrace(
            execResult.moduleName(),
            execResult.functionName(),
            steps,
            execResult.result(),
            execResult.executionTimeMs());
    }

    /**
     * 阶段三：计算 {@link ReplayMetadata}。fail-loud——compute 内部对可归一化值
     * （如小数）不抛，只有真正意外的异常才落到这里的 catch，此时诚实降级为
     * NON_REPLAYABLE 并记录 "compute_threw: " 原因，不静默丢 replayMetadata。
     */
    public ReplayMetadata computeReplayMetadata(
            String toolchainId,
            Object context,
            ReplayExecutorResult execResult,
            DecisionTrace trace,
            TraceAccess.DrainResult drain) {
        try {
            return ReplayMetadata.compute(
                toolchainId,
                context,
                execResult.result(),
                trace,
                drain == null || drain.replayable());
        } catch (Exception rmEx) {
            LOG.warning(() -> "replayCapture 元数据计算意外失败，标记 NON_REPLAYABLE: " + rmEx.getMessage());
            return new ReplayMetadata(
                toolchainId,
                ReplayMetadata.CANONICALIZATION_VERSION,
                null, null, null, List.of(),
                ReplayMetadata.STATUS_NON_REPLAYABLE,
                List.of("compute_threw: " + rmEx.getMessage()),
                // M2 canonical 串：compute 抛异常兜底路径无 payload。
                null, null, null);
        }
    }

    // ---------------------------------------------------------------
    // trace/vocab/alias glue（从 PolicyEvaluationResource 逐字移入）
    // ---------------------------------------------------------------

    /**
     * 把请求携带的领域词汇表（Map 形式）构建为 IdentifierIndex。
     *
     * <p>ADR 0014 线C：发布的策略可携带其快照领域词汇，使执行端规范化阶段
     * 能翻译用户自定义术语。词汇为空或格式非法时返回 null（退化为仅内置），
     * 不阻断执行——执行端解析仍可在 builtin 词汇下进行。
     *
     * <p>公开为静态方法，供编译端点（保存门禁）复用同一构建口径，保证「编译通过」与「执行
     * 可解析」对同一份词汇得出一致结论。
     */
    public static IdentifierIndex buildVocabularyIndex(Map<String, Object> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) {
            return null;
        }
        try {
            DomainVocabulary vocab = VocabularyLoader.loadFromMap(vocabulary);
            return IdentifierIndex.build(vocab);
        } catch (Exception e) {
            // 词汇格式非法不应阻断执行，记录并退化为仅内置。
            LOG.warning(() -> "领域词汇表解析失败，退化为仅内置词汇: " + e.getMessage());
            return null;
        }
    }

    /**
     * 把请求里的别名 Map（kind 名 → 短语数组）转成 {@code Map<SemanticTokenKind, List<String>>}。
     *
     * <p>ADR 0022。无法识别的 kind 名跳过（不阻断执行；下游 UserAliasValidator 会对无效 kind 拒，
     * 但冻结版本本就已校验过，这里只做类型转换）。null/空 → null（无用户别名，退化为无别名解析）。
     */
    private Map<SemanticTokenKind, List<String>> buildAliasSet(Map<String, List<String>> aliasSet) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return null;
        }
        Map<SemanticTokenKind, List<String>> out = new EnumMap<>(SemanticTokenKind.class);
        for (var e : aliasSet.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            try {
                out.put(SemanticTokenKind.valueOf(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // 未知 kind 名跳过——不阻断执行。
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<DecisionTrace.TraceStep> toTraceSteps(List<Map<String, Object>> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return List.of();
        }
        List<DecisionTrace.TraceStep> steps = new ArrayList<>(rawSteps.size());
        for (Map<String, Object> raw : rawSteps) {
            if (raw == null) {
                continue;
            }
            steps.add(new DecisionTrace.TraceStep(
                traceSequence(raw.get("sequence")),
                traceExpression(raw.get("expression")),
                raw.get("result"),
                Boolean.TRUE.equals(raw.get("matched")),
                toChildTraceSteps(raw.get("children"))));
        }
        return List.copyOf(steps);
    }

    @SuppressWarnings("unchecked")
    private static List<DecisionTrace.TraceStep> toChildTraceSteps(Object rawChildren) {
        if (!(rawChildren instanceof List<?> children) || children.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> childMaps = new ArrayList<>(children.size());
        for (Object child : children) {
            if (child instanceof Map<?, ?> childMap) {
                childMaps.add((Map<String, Object>) childMap);
            }
        }
        return toTraceSteps(childMaps);
    }

    private static int traceSequence(Object rawSequence) {
        if (rawSequence instanceof Number number) {
            return number.intValue();
        }
        if (rawSequence instanceof String text) {
            return Integer.parseInt(text);
        }
        throw new IllegalArgumentException("trace step 缺少 sequence");
    }

    private static String traceExpression(Object rawExpression) {
        if (rawExpression instanceof String expression) {
            return expression;
        }
        return rawExpression == null ? "" : String.valueOf(rawExpression);
    }
}
