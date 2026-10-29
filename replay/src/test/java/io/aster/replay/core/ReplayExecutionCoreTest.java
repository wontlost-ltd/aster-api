package io.aster.replay.core;

import aster.core.lexicon.SemanticTokenKind;
import aster.truffle.trace.TraceAccess;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.replay.ReplayMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReplayExecutionCore 三阶段单元测试（P0-A S2-1a-0 Task 4）。
 *
 * <p>重点覆盖 evaluateSource 现状（Task 3 字符化测试已钉死）无法从 REST 边界直接验证的
 * <b>内部时序契约</b>：executor 抛异常时，{@link ReplayExecutionCore#execute} 必须先在
 * finally 中 drain trace（清理 ThreadLocal，防跨请求残留），再把同一异常实例原样向上抛
 * ——不吞、不包装、不进入 {@link ExecutionPhaseResult} 正常返回路径（调用方后续阶段
 * {@code buildDecisionTrace}/{@code computeReplayMetadata} 因此根本不会被调用，这一点
 * 由 Java 的 try/catch 控制流本身保证，此处用「execute 之后 ThreadLocal 已清理」间接证明
 * finally-drain 确实执行过）。
 */
class ReplayExecutionCoreTest {

    private final ReplayExecutionCore core = new ReplayExecutionCore();

    private static ReplayExecutionRequest request(boolean trace, boolean replayCapture) {
        return new ReplayExecutionRequest(
            "tenant-1", "Module probe.\nRule main given x as Int, produce Int:\n  Return x.",
            Map.of("x", 1), "main", "en-US",
            null, null, true, true, trace, replayCapture);
    }

    /** 正常路径下永远返回结果、不抛异常的假 executor。 */
    private static final class SucceedingExecutor implements ReplayExecutor {
        @Override
        public ReplayExecutorResult execute(String tenantId, String source, Object context,
                String functionName, String locale, aster.core.identifier.VocabularyScopes vocabIndex,
                boolean legacyEvaluateSentinel, Map<SemanticTokenKind, List<String>> aliasSet,
                boolean aliasesTrusted) {
            return new ReplayExecutorResult(42, "probe", "main", 5L);
        }
    }

    /** 抛出给定异常实例的假 executor，用于验证 core 的异常透传契约。 */
    private static final class ThrowingExecutor implements ReplayExecutor {
        private final RuntimeException toThrow;

        ThrowingExecutor(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public ReplayExecutorResult execute(String tenantId, String source, Object context,
                String functionName, String locale, aster.core.identifier.VocabularyScopes vocabIndex,
                boolean legacyEvaluateSentinel, Map<SemanticTokenKind, List<String>> aliasSet,
                boolean aliasesTrusted) {
            throw toThrow;
        }
    }

    @Test
    @DisplayName("executor 抛异常：execute() 原样透传同一异常实例（不捕获不包装）")
    void executeRethrowsSameExceptionInstanceOnExecutorFailure() {
        RuntimeException boom = new IllegalStateException("boom");
        ThrowingExecutor executor = new ThrowingExecutor(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> core.execute(request(true, false), executor));

        // ★原样透传：必须是同一实例，不是被 wrap 过的新异常（否则 evaluateSource 现有四类
        // catch 的 instanceof 匹配会失效，破坏错误码/HTTP 映射的现状行为）。
        assertSame(boom, thrown, "core.execute 必须原样透传 executor 抛出的同一异常实例");
    }

    @Test
    @DisplayName("executor 抛异常且 captureTraceSteps=true：finally 已 drain，ThreadLocal 不残留")
    void executeDrainsTraceBeforeRethrowingWhenCaptureRequested() {
        RuntimeException boom = new RuntimeException("boom-with-trace");
        ThrowingExecutor executor = new ThrowingExecutor(boom);

        assertThrows(RuntimeException.class, () -> core.execute(request(true, false), executor));

        // finally 块必须已经调用 TraceAccess.drainCurrentThread() 清空 ThreadLocal——
        // 再次 drain 应返回「未 arm」的空结果（steps 为空、replayable=false，见
        // TraceAccess.drainCurrentThread 对 collector==null 的处理），证明没有残留到
        // 下一次请求。若 finally 未执行，上一个 armCurrentThread 的 collector 仍挂在
        // ThreadLocal 上，这里会拿到非空 collector 而非默认的未 arm 状态。
        TraceAccess.DrainResult residual = TraceAccess.drainCurrentThread();
        assertTrue(residual.steps().isEmpty(), "executor 异常路径后不应有残留 trace steps");
    }

    @Test
    @DisplayName("executor 抛异常：调用方（resource）永远不会走到 buildDecisionTrace/computeReplayMetadata")
    void exceptionPathNeverReachesLaterPhases() {
        RuntimeException boom = new RuntimeException("boom-skip-later-phases");
        ThrowingExecutor executor = new ThrowingExecutor(boom);
        ReplayExecutionRequest req = request(true, true);

        // 模拟 evaluateSource 的调用顺序：try { phase = core.execute(...); decisionTrace =
        // core.buildDecisionTrace(...); metadata = core.computeReplayMetadata(...); }
        // catch (...) { ... }。异常必须在 execute() 这一步就中断整个 try 块，后两阶段的
        // 调用语句永远不会执行——用一个 boolean 哨兵证明控制流确实提前退出。
        boolean[] laterPhaseReached = {false};
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            ExecutionPhaseResult phase = core.execute(req, executor);
            laterPhaseReached[0] = true; // 只有 execute() 正常返回才会执行到这里
            core.buildDecisionTrace(phase.execResult(), phase.traceDrainResult(), true);
            core.computeReplayMetadata("toolchain", req.context(), phase.execResult(), null, phase.traceDrainResult());
        });

        assertSame(boom, thrown);
        assertTrue(!laterPhaseReached[0], "execute() 抛异常后不应到达 buildDecisionTrace/computeReplayMetadata");
    }

    @Test
    @DisplayName("正常路径：三阶段依次产出 execResult → decisionTrace → replayMetadata")
    void happyPathThreePhasesProduceConsistentResults() {
        SucceedingExecutor executor = new SucceedingExecutor();
        ReplayExecutionRequest req = request(false, true); // trace=false, replayCapture=true

        ExecutionPhaseResult phase = core.execute(req, executor);
        assertEquals(42, phase.execResult().result());
        assertEquals("probe", phase.execResult().moduleName());

        // captureTrace = trace || effectiveReplayCapture = false || true = true
        DecisionTrace decisionTrace = core.buildDecisionTrace(
            phase.execResult(), phase.traceDrainResult(), req.trace() || req.effectiveReplayCapture());
        assertEquals("probe", decisionTrace.moduleName());
        assertEquals(42, decisionTrace.finalResult());

        ReplayMetadata metadata = core.computeReplayMetadata(
            "toolchain-x", req.context(), phase.execResult(), decisionTrace, phase.traceDrainResult());
        assertEquals("toolchain-x", metadata.runtimeToolchainId());
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, metadata.replayabilityStatus());
    }

    @Test
    @DisplayName("trace=false 且 replayCapture=false：buildDecisionTrace 返回 null（不构建）")
    void buildDecisionTraceReturnsNullWhenNeitherFlagSet() {
        SucceedingExecutor executor = new SucceedingExecutor();
        ReplayExecutionRequest req = request(false, false);

        ExecutionPhaseResult phase = core.execute(req, executor);
        DecisionTrace decisionTrace = core.buildDecisionTrace(
            phase.execResult(), phase.traceDrainResult(), req.trace() || req.effectiveReplayCapture());

        assertNull(decisionTrace, "trace=false 且 effectiveReplayCapture=false 时不应构建 DecisionTrace");
        assertNull(phase.traceDrainResult(), "未请求 trace 时 execute() 不应产出 traceDrainResult");
    }

    @Test
    @DisplayName("M2.1b: buildDecisionTrace 递归转换嵌套 truffle drain map shape")
    void buildDecisionTraceConvertsNestedDrainMaps() {
        // 原 PolicyEvaluationResourceTrialIdentityTest#toTraceStepsConvertsNestedDrainMaps
        // 的等价覆盖——toTraceSteps 已随 Task 4 移入本类并降级为 private 实现细节，
        // 经公开入口 buildDecisionTrace 验证同一份嵌套 children 转换契约。
        List<Map<String, Object>> rawSteps = List.of(Map.of(
            "sequence", 1,
            "expression", "score >= 680",
            "result", true,
            "matched", true,
            "children", List.of(Map.of(
                "sequence", 2,
                "expression", "income >= 50000",
                "result", 50000L,
                "matched", false,
                "children", List.of()))));
        TraceAccess.DrainResult drain = new TraceAccess.DrainResult(rawSteps, false, false, true);
        ReplayExecutorResult execResult = new ReplayExecutorResult(true, "aster.finance.loan", "approveLoan", 7L);

        DecisionTrace decisionTrace = core.buildDecisionTrace(execResult, drain, true);

        assertEquals(1, decisionTrace.steps().size());
        DecisionTrace.TraceStep root = decisionTrace.steps().get(0);
        assertEquals(1, root.sequence());
        assertEquals("score >= 680", root.expression());
        assertEquals(Boolean.TRUE, root.result());
        assertTrue(root.matched());
        assertEquals(1, root.children().size());
        DecisionTrace.TraceStep child = root.children().get(0);
        assertEquals(2, child.sequence());
        assertEquals("income >= 50000", child.expression());
        assertEquals(50000L, child.result());
        assertEquals(false, child.matched());
        assertTrue(child.children().isEmpty());
    }

    /** 记录 core 传给 executor 的词汇作用域。 */
    private static final class CapturingExecutor implements ReplayExecutor {
        aster.core.identifier.VocabularyScopes seen;

        @Override
        public ReplayExecutorResult execute(String tenantId, String source, Object context,
                String functionName, String locale, aster.core.identifier.VocabularyScopes vocabIndex,
                boolean legacyEvaluateSentinel, Map<SemanticTokenKind, List<String>> aliasSet,
                boolean aliasesTrusted) {
            seen = vocabIndex;
            return new ReplayExecutorResult(1, "probe", "main", 1L);
        }
    }

    private static ReplayExecutionRequest requestWithVocabulary(Map<String, Object> vocabulary) {
        return new ReplayExecutionRequest(
            "tenant-1", "Module probe.\nRule main given x as Int, produce Int:\n  Return x.",
            Map.of("x", 1), "main", "en-US", vocabulary, null, true, true, false, false);
    }

    @Test
    @DisplayName("ADR 0051：请求词汇构建为按角色作用域交给 executor；空词汇等同无词汇")
    void executeBuildsVocabularyScopesFromRequest() {
        Map<String, Object> vocabulary = Map.of(
            "id", "v", "name", "V", "locale", "de-DE", "version", "user",
            "structs", List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
            "fields", List.of(Map.of("canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")));
        CapturingExecutor executor = new CapturingExecutor();
        core.execute(requestWithVocabulary(vocabulary), executor);
        assertEquals("Driver", aster.core.identifier.VocabularyScopes
            .lookup(executor.seen.types(), "Fahrer").exact().get(0).canonical());
        assertEquals("age", aster.core.identifier.VocabularyScopes
            .lookup(executor.seen.fields().get("Driver"), "alter").exact().get(0).canonical());

        core.execute(requestWithVocabulary(Map.of("id", "v", "name", "V", "locale", "de-DE", "version", "user")),
            executor);
        assertNull(executor.seen, "无任何条目的词汇必须与无词汇一致");
    }
}
