package io.aster.policy.parser;

import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方案 D（ADR 0022 §11）用户自定义关键词别名：执行器侧的信任边界。
 *
 * <p>别名编译机制本身（别名版与规范版降到同一 Core IR、null/空 aliasSet 等价等）由 core 的
 * {@code aster.core.embed.UserAliasCompileTest} 覆盖（解析器在 core，本仓不再保留副本）。这里只验证 aster-api
 * 独有的一层：{@link DynamicCnlExecutor} 按 aliasesTrusted 决定 allowStructural（trial 现场输入 vs 冻结版本）。
 */
class UserAliasCompileTest {

    // ---- 安全边界：executor 按 aliasesTrusted 决定 allowStructural（trial vs 冻结版本）----

    @Test
    void executor_untrustedStructuralAlias_rejected() {
        // trial/现场输入（aliasesTrusted=false）：结构词别名不可信 → 校验拒 → parse 抛异常，
        // executor 包成 DynamicExecutionException。防直连 API-key 的 trial 调用绕过 per-user
        // 授权注入结构词别名。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.RETURN, List.of("the answer is"));
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  the answer is x.";

        DynamicCnlExecutor executor = new DynamicCnlExecutor();
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> executor.executeWithTenantContext(
                "tenant-1", src, Map.of("x", 5), "p", "en-US", null, false, aliasSet, false),
            "未授权结构词别名的 trial 执行应被拒绝");
    }

    @Test
    void executor_trustedStructuralAlias_executes() {
        // 冻结版本（aliasesTrusted=true）：结构词别名可信 → 正常执行。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.RETURN, List.of("the answer is"));
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  the answer is x.";

        DynamicCnlExecutor executor = new DynamicCnlExecutor();
        DynamicCnlExecutor.ExecutionResult result = executor.executeWithTenantContext(
            "tenant-1", src, Map.of("x", 5), "p", "en-US", null, false, aliasSet, true);

        assertEquals(5, result.result(), "授权结构词别名应正常执行");
    }
}
