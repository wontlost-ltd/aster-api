package io.aster.policy.replay;

import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * ReplayExecutor 的 aster-api 实现：委托现有 DynamicCnlExecutor，把其
 * ExecutionResult 机械映射为 core 拥有的 ReplayExecutorResult。
 *
 * <p>★首刀 executor 本体不移——本 adapter 是 core 接口与 aster-api 现有
 * executor 之间的边界，避免 core 反依赖 DynamicCnlExecutor。
 */
@ApplicationScoped
public class ReplayExecutorAdapter implements ReplayExecutor {

    @Inject
    DynamicCnlExecutor dynamicCnlExecutor;

    // ★收「已建」vocabIndex/aliasSet（core 建好传入），adapter 只透传 + 映射结果。
    @Override
    public ReplayExecutorResult execute(
            String tenantId, String source, Object context,
            String functionName, String locale,
            aster.core.identifier.IdentifierIndex vocabIndex,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        DynamicCnlExecutor.ExecutionResult r = dynamicCnlExecutor.executeWithTenantContext(
                tenantId, source, context, functionName, locale,
                vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted);
        return new ReplayExecutorResult(r.result(), r.moduleName(), r.functionName(), r.executionTimeMs(),
                r.ruleId(), r.controls());
    }
}
