package io.aster.policy.parser;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyLoader;
import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import io.aster.replay.core.ReplayExecutionCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0014 线C / ADR 0051：执行端按位置解析领域词汇的回归。
 *
 * 验证发布的策略携带快照领域词汇时，执行端规范化阶段能把用户自定义的本地化
 * 术语翻译为规范化名称；不携带词汇时，含本地化术语的源代码无法解析。
 *
 * 用例采用结构体/字段重命名：源代码用本地化名 `Fahrer`/`alter`（德语「司机/
 * 年龄」），词汇把它们映射到规范化 `Driver`/`age`。规范化后等价于直接用
 * 规范化名编写的源代码。
 */
class VocabularyExecutionTest {

    /** 源代码用本地化术语 Fahrer / alter 编写。 */
    private static final String SOURCE_LOCALIZED = """
        Module insurance.custom.

        Define Fahrer has alter as Int.

        Rule evaluate given driver as Fahrer, produce Int:
          Return driver.alter.
        """;

    /** 等价的规范化源代码（用作对照基线）。 */
    private static final String SOURCE_CANONICAL = """
        Module insurance.custom.

        Define Driver has age as Int.

        Rule evaluate given driver as Driver, produce Int:
          Return driver.age.
        """;

    /** 构建把 Fahrer→Driver、alter→age 的领域词汇索引。 */
    private static VocabularyScopes customIndex() {
        DomainVocabulary vocab = new DomainVocabulary(
            "insurance.custom",
            "Custom Insurance",
            "en-US",
            "user",
            List.of(IdentifierMapping.struct("Driver", "Fahrer")),
            List.of(IdentifierMapping.field("age", "alter", "Driver")),
            List.of(),
            List.of(),
            List.of(),
            null
        );
        return VocabularyScopes.build(vocab);
    }

    @Test
    void canonical_source_baseline_works() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_CANONICAL, context, "evaluate", "en-US");
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_translates_with_vocabulary() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US", customIndex());
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_reference_resolves_by_case_fold() {
        // ADR 0051 §6.3：引用位置精确未命中时按 Unicode 简单折叠唯一匹配（两引擎同一折叠表）。
        // 声明名的大小写受语法约束（类型名大写开头），故以字段引用 Alter 验证折叠匹配 alter。
        String foldedSource = """
            Module insurance.custom.

            Define Fahrer has alter as Int.

            Rule evaluate given driver as Fahrer, produce Int:
              Return driver.Alter.
            """;
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            foldedSource, context, "evaluate", "en-US", customIndex());
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_translates_via_loadFromMap_with_kind_field() {
        // 本地 E2E 实证的真实路径回归：REST 请求里的 vocabulary 是 Map（cloud/TS
        // 序列化的 DomainVocabulary，每个 mapping 带 kind 字段），经
        // VocabularyLoader.loadFromMap → VocabularyScopes 进执行端。此前 loader
        // 严格 FAIL_ON_UNKNOWN 会因 kind 解析失败 → 静默退化不翻译。
        Map<String, Object> vocabMap = Map.of(
            "id", "insurance.custom",
            "name", "Custom",
            "locale", "en-US",
            "version", "user",
            "structs", List.of(Map.of(
                "canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
            "fields", List.of(Map.of(
                "canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
            "functions", List.of(),
            "enumValues", List.of()
        );
        VocabularyScopes index = VocabularyScopes.build(VocabularyLoader.loadFromMap(vocabMap));

        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US", index);
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_fails_without_vocabulary() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        // 不提供词汇时，本地化术语 Fahrer/alter 不被翻译，源代码语义残缺 →
        // 解析或执行失败（具体异常类型取决于管道，但绝不能返回 42）。
        assertThatThrownBy(() -> DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US"))
            .isInstanceOf(RuntimeException.class);
    }

    static java.util.List<GermanReproductions.Repro> repros() {
        return GermanReproductions.ALL;
    }

    @ParameterizedTest
    @MethodSource("repros")
    void ADR第1节复现带词汇执行得到正确结果(GermanReproductions.Repro repro) {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(repro.source(), repro.context(), repro.entry(),
            "de-DE", ReplayExecutionCore.buildVocabularyScopes(repro.vocabulary()), false);
        assertThat(r.result()).isEqualTo(repro.expected());
    }

    @Test
    void 规则名经词汇解析为规范名后可按规范名执行() {
        var repro = GermanReproductions.ENUM_MEMBER_VS_RULE;
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(repro.source(), Map.of("x", 7), "high",
            "de-DE", ReplayExecutionCore.buildVocabularyScopes(repro.vocabulary()), false);
        assertThat(r.result()).isEqualTo(7);
        assertThat(r.functionName()).isEqualTo("high");
    }

    @Test
    void 名称解析错误使执行失败且不产出结果() {
        var vocab = ReplayExecutionCore.buildVocabularyScopes(GermanReproductions.ENUM_MEMBER_VS_RULE.vocabulary());
        assertThatThrownBy(() -> DynamicCnlExecutor.executeWithContext(GermanReproductions.BARE_ENUM_MEMBER,
            Map.of("x", 1), "einstufen", "de-DE", vocab, false))
            .isInstanceOf(DynamicCnlExecutor.DynamicExecutionException.class)
            .hasMessageContaining("E804");
    }
}
