package io.aster.policy.compiler;

import io.aster.policy.repository.PolicySourceRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PolicyCompiler 结构词别名信任开关（aster-cloud#604）：只有调用方显式传 allowStructural=true
 * 时结构词别名才放行；三参入口保持默认拒绝，既有调用方语义不变。
 */
class PolicyCompilerStructuralAliasTest {

    private static final String SOURCE = """
        Module M.

        Rule p given age as Int, produce Bool:
          provided that age at least 18:
            Return true.
          Return false.""";
    private static final String ALIASES = "{\"IF\":[\"provided that\"]}";

    private final PolicyCompiler compiler = new PolicyCompiler(mock(PolicySourceRepository.class),
        new io.aster.policy.stability.StabilityEnforcement(),
        new io.aster.policy.analysis.RuleConflictDiagnostics());

    @Test
    void 授权后结构词别名编译通过() {
        var result = compiler.compile(SOURCE, "en-US", ALIASES, null, true);
        assertThat(result.isSuccess()).as(String.valueOf(result.getErrors())).isTrue();
    }

    @Test
    void 未授权时结构词别名被拒() {
        var result = compiler.compile(SOURCE, "en-US", ALIASES, null, false);
        assertThat(result.isSuccess()).isFalse();
        assertThat(String.join("\n", result.getErrors())).contains("IF");
    }

    @Test
    void 三参入口默认不信任结构词别名() {
        var result = compiler.compile(SOURCE, "en-US", ALIASES);
        assertThat(result.isSuccess()).isFalse();
    }
}
