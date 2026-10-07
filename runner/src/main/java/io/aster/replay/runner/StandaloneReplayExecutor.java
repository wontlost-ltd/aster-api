package io.aster.replay.runner;

import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import java.util.List;
import java.util.Map;

/**
 * ReplayExecutor 的 standalone 实现（非 CDI）。仿 aster-api ReplayExecutorAdapter，
 * 但用 new DynamicCnlExecutor(null, true)（★modulesEnabled=true + null resolver）→
 * import-free fail-closed：policy 用跨模块 import 时，DynamicCnlExecutor.java:444 因
 * moduleResolver==null 抛 ModuleResolutionException（异常原样透传，runner 顶层映射 MODULE 错误）。
 * ★不用 no-arg——no-arg 是 (null,false)，modulesEnabled=false 会让 import 被静默忽略非拒绝。
 */
public final class StandaloneReplayExecutor implements ReplayExecutor {

    // (null, true)：null ModuleGraphResolver + modulesEnabled=true → import 触发 fail-closed 抛异常。
    private final DynamicCnlExecutor executor = new DynamicCnlExecutor(null, true);

    @Override
    public ReplayExecutorResult execute(
            String tenantId, String source, Object context,
            String functionName, String locale,
            aster.core.identifier.IdentifierIndex vocabIndex,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        DynamicCnlExecutor.ExecutionResult r = executor.executeWithTenantContext(
                tenantId, source, context, functionName, locale,
                vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted);
        return new ReplayExecutorResult(r.result(), r.moduleName(), r.functionName(), r.executionTimeMs(),
                r.ruleId(), r.controls());
    }
}
