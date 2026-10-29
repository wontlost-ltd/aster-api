package io.aster.policy.compiler;

import aster.core.identifier.VocabularyScopes;
import com.fasterxml.jackson.databind.JsonNode;
import io.aster.common.JacksonMappers;
import io.aster.policy.parser.GermanReproductions;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.replay.core.ReplayExecutionCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 编译路径按位置解析领域词汇（ADR 0051）：保存门禁与执行同一构建口径，E8xx 作为编译错误返回，
 * W8xx 随成功结果返回；空词汇与只含字面量宏的词汇与无词汇一致。
 */
class PolicyCompilerVocabularyTest {

    /** 结构体用规范名定义，规则里用本地化术语 Fahrer/alter 引用——只有按位置解析后才自洽。 */
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

    static List<GermanReproductions.Repro> repros() {
        return GermanReproductions.ALL;
    }

    private final PolicyCompiler compiler = new PolicyCompiler(mock(PolicySourceRepository.class),
        new io.aster.policy.stability.StabilityEnforcement(),
        new io.aster.policy.analysis.RuleConflictDiagnostics());

    @Test
    void 带词汇时本地化术语编译通过() {
        VocabularyScopes scopes = ReplayExecutionCore.buildVocabularyScopes(VOCABULARY);
        assertThat(scopes).isNotNull();
        var result = compiler.compile(SOURCE, "en-US", null, scopes, false);
        assertThat(result.isSuccess()).as(String.valueOf(result.getErrors())).isTrue();
    }

    @Test
    void 不带词汇时本地化引用以名称解析错误失败() {
        var result = compiler.compile(SOURCE, "en-US", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).extracting(CompilationResult.Diagnostic::code)
            .anySatisfy(code -> assertThat(code).startsWith("E80"));
    }

    @ParameterizedTest
    @MethodSource("repros")
    void ADR第1节复现带词汇编译成功且只含规范名(GermanReproductions.Repro repro) {
        var result = compiler.compile(repro.source(), "de-DE", null,
            ReplayExecutionCore.buildVocabularyScopes(repro.vocabulary()), false);
        assertThat(result.isSuccess()).as("%s %s", result.getErrors(), result.getDiagnostics()).isTrue();
        List<String> names = declNames(result.getCoreJson());
        assertThat(names).containsAll(repro.canonicalNames()).doesNotContainAnyElementsOf(repro.writtenNames());
    }

    @Test
    void 复现4按所属struct解析同名字段() {
        var repro = GermanReproductions.SAME_FIELD_TWO_STRUCTS;
        var result = compiler.compile(repro.source(), "de-DE", null,
            ReplayExecutionCore.buildVocabularyScopes(repro.vocabulary()), false);
        assertThat(result.getCoreJson()).contains("\"fahrer.age\"").contains("\"wagen.vehicleAge\"");
    }

    @Test
    void 名称解析错误以编译错误形态返回并带码与位置() {
        var repro = GermanReproductions.ENUM_MEMBER_VS_RULE;
        var result = compiler.compile(GermanReproductions.BARE_ENUM_MEMBER, "de-DE", null,
            ReplayExecutionCore.buildVocabularyScopes(repro.vocabulary()), false);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anySatisfy(e -> assertThat(e).contains("E804"));
        var e804 = result.getDiagnostics().stream().filter(d -> "E804".equals(d.code())).findFirst().orElseThrow();
        assertThat(e804.severity()).isEqualTo("error");
        assertThat(e804.blocking()).isTrue();
        assertThat(e804.line()).isEqualTo(9);
        assertThat(e804.column()).isGreaterThan(1);
    }

    @Test
    void 遮蔽词汇的警告随成功结果返回() {
        String source = """
            Module m.

            Rule total given x as Int, produce Int:
              Return x.

            Rule apply given gesamt as Int, produce Int:
              Return gesamt.
            """;
        Map<String, Object> vocab = GermanReproductions.vocabulary(List.of(), List.of(),
            List.of(GermanReproductions.function("total", "gesamt")), List.of());
        var result = compiler.compile(source, "en-US", null, ReplayExecutionCore.buildVocabularyScopes(vocab), false);
        assertThat(result.isSuccess()).as("%s", result.getErrors()).isTrue();
        var w800 = result.getDiagnostics().stream().filter(d -> "W800".equals(d.code())).findFirst().orElseThrow();
        assertThat(w800.severity()).isEqualTo("warning");
        assertThat(w800.blocking()).isFalse();
    }

    @Test
    void 空词汇与非法词汇退化为无词汇() {
        assertThat(ReplayExecutionCore.buildVocabularyScopes(null)).isNull();
        assertThat(ReplayExecutionCore.buildVocabularyScopes(Map.of())).isNull();
        assertThat(ReplayExecutionCore.buildVocabularyScopes(Map.of("structs", "not-a-list"))).isNull();
        assertThat(ReplayExecutionCore.buildVocabularyScopes(
            GermanReproductions.vocabulary(List.of(), List.of(), List.of(), List.of()))).isNull();
    }

    @Test
    void 只含字面量宏的词汇与无词汇产出相同IR() {
        String source = GermanReproductions.PARAM_VS_STRUCT.source()
            .replace("Fahrer", "Driver").replace("alter", "age");
        Map<String, Object> literalOnly = new java.util.HashMap<>(
            GermanReproductions.vocabulary(List.of(), List.of(), List.of(), List.of()));
        literalOnly.put("literals", List.of(Map.of("canonical", "gold-tier", "localized", "goldstufe",
            "kind", "literal")));
        VocabularyScopes scopes = ReplayExecutionCore.buildVocabularyScopes(literalOnly);
        assertThat(scopes).isNotNull();
        assertThat(scopes.literals()).isNotEmpty();
        var none = compiler.compile(source, "de-DE", null, null, false);
        var withLiterals = compiler.compile(source, "de-DE", null, scopes, false);
        assertThat(none.isSuccess()).as("%s", none.getErrors()).isTrue();
        assertThat(withLiterals.getCoreJson()).isEqualTo(none.getCoreJson());
    }

    private static List<String> declNames(String coreJson) {
        try {
            JsonNode root = JacksonMappers.PRETTY.readTree(coreJson);
            List<String> names = new ArrayList<>();
            root.path("decls").forEach(d -> names.add(d.path("name").asText()));
            return names;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
