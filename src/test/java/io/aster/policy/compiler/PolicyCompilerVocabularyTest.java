package io.aster.policy.compiler;

import aster.core.identifier.IdentifierIndex;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.replay.core.ReplayExecutionCore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 编译路径的领域词汇翻译（ADR 0014 线C，保存门禁与执行同口径）：带词汇时本地化术语
 * 翻成规范名后编译通过；不带词汇时同一源码编译失败，与改造前一致。
 */
class PolicyCompilerVocabularyTest {

    /** 结构体用规范名定义，规则里用本地化术语 Fahrer/alter 引用——只有词汇翻译后才自洽。 */
    static final String SOURCE = """
        Module insurance.custom.

        Define Driver has age as Int.

        Rule evaluate given driver as Fahrer, produce Int:
          Return driver.alter.
        """;

    /** 与 cloud 发送的 DomainVocabulary JSON 同形（mapping 带 kind 字段）。 */
    static final Map<String, Object> VOCABULARY = Map.of(
        "id", "insurance.custom",
        "name", "Custom",
        "locale", "en-US",
        "version", "user",
        "structs", List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
        "fields", List.of(Map.of("canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
        "functions", List.of(),
        "enumValues", List.of());

    private final PolicyCompiler compiler = new PolicyCompiler(mock(PolicySourceRepository.class),
        new io.aster.policy.stability.StabilityEnforcement(),
        new io.aster.policy.analysis.RuleConflictDiagnostics());

    @Test
    void 带词汇时本地化术语编译通过() {
        IdentifierIndex index = ReplayExecutionCore.buildVocabularyIndex(VOCABULARY);
        assertThat(index).isNotNull();
        var result = compiler.compile(SOURCE, "en-US", null, index, false);
        assertThat(result.isSuccess()).as(String.valueOf(result.getErrors())).isTrue();
    }

    @Test
    void 不带词汇时同一源码编译失败() {
        assertThat(compiler.compile(SOURCE, "en-US", null).isSuccess()).isFalse();
        assertThat(compiler.compile(SOURCE, "en-US", null, null, false).isSuccess()).isFalse();
    }

    @Test
    void 空词汇与非法词汇退化为仅内置() {
        assertThat(ReplayExecutionCore.buildVocabularyIndex(null)).isNull();
        assertThat(ReplayExecutionCore.buildVocabularyIndex(Map.of())).isNull();
        assertThat(ReplayExecutionCore.buildVocabularyIndex(Map.of("structs", "not-a-list"))).isNull();
    }
}
