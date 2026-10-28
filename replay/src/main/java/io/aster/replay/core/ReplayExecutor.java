package io.aster.replay.core;

import aster.core.lexicon.SemanticTokenKind;

import java.util.List;
import java.util.Map;

/**
 * replay 执行的 core 抽象。aster-api 提供委托现有 DynamicCnlExecutor 的 adapter；
 * 后续 β runner 提供复用同一 executor implementation 的 wiring（不另写 parser/executor）。
 *
 * <p>★异常契约：原样透传——不捕获、不包装 executor 抛出的 runtime exception。
 * core 只在 finally 中 drain trace，随后抛出同一异常实例，由 aster-api resource
 * 的现有四类 catch + HTTP 映射处理。
 */
public interface ReplayExecutor {
    /**
     * ★收「已建」词汇作用域 + aliasSet（与 DynamicCnlExecutor 一致）——raw→已建的
     * 构建逻辑归 core 的 ReplayExecutionCore（byte-parity 单一份），executor 收结果。
     *
     * @param aliasesTrusted 由调用方（adapter）从已验证的调用上下文派生，非业务输入。
     * @param vocabularyScopes 已建的词汇作用域（core 用 buildVocabularyScopes 建），null=无词汇。
     * @param aliasSet       已建的 kind→phrases 映射（core 用 buildAliasSet 建）。
     */
    ReplayExecutorResult execute(
            String tenantId,
            String source,
            Object context,
            String functionName,
            String locale,
            aster.core.identifier.VocabularyScopes vocabularyScopes,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet,
            boolean aliasesTrusted);
}
