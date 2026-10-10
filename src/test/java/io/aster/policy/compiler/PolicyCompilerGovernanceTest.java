package io.aster.policy.compiler;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 编译端点带出治理诊断（ADR 0045 §3）：W704 为 warning，不改变编译成功。 */
@QuarkusTest
class PolicyCompilerGovernanceTest {
    @Inject
    PolicyCompiler compiler;

    static final String SRC = String.join("\n",
        "Module governance.probe.", "",
        "@id(\"GP-001\")", "@control(\"EU_AI_ACT:ART14\")", "@control(\"ACME:ART1\")",
        "Rule decide given amount, produce Verdict:",
        "  If amount at least 100:", "    Return Verdict.require_approval(\"Reviewer\", \"large\").",
        "  Return Verdict.allow().", "");

    @Test
    void 未登记控制点给W704且编译成功() {
        var result = compiler.compile(SRC, "en-US");
        assertThat(result.isSuccess()).isTrue();
        var w704 = result.getDiagnostics().stream().filter(d -> "W704".equals(d.code())).toList();
        assertThat(w704).hasSize(1);
        var d = w704.get(0);
        assertThat(d.message()).contains("ACME:ART1");
        assertThat(d.severity()).isEqualTo("warning");
        assertThat(d.blocking()).isFalse();
        assertThat(d.line()).isGreaterThanOrEqualTo(1);
        assertThat(d.column()).isGreaterThanOrEqualTo(1);
    }

    static final String DUP_ID_SRC = String.join("\n",
        "Module governance.dup.", "",
        "@id(\"R-1\")",
        "Rule first given amount, produce Verdict:",
        "  Return Verdict.allow().", "",
        "@id(\"R-1\")",
        "Rule second given amount, produce Verdict:",
        "  Return Verdict.allow().", "");

    static final String BLANK_ID_SRC = String.join("\n",
        "Module governance.blank.", "",
        "@id(\"\")",
        "Rule decide given amount, produce Verdict:",
        "  Return Verdict.allow().", "");

    @Test
    void 重复id的E701在编译通道降为warning且不阻断() {
        assertGovernanceWarning(DUP_ID_SRC, "E701");
    }

    @Test
    void 空id的E702在编译通道降为warning且不阻断() {
        assertGovernanceWarning(BLANK_ID_SRC, "E702");
    }

    static final String UNKNOWN_PROFILE_SRC = String.join("\n",
        "Module governance.nope.",
        "Profile \"nope\".", "",
        "@id(\"P-1\")",
        "Rule decide given amount, produce Verdict:",
        "  Return Verdict.allow().", "");

    static final String GOVERNED_MISSING_ID_SRC = String.join("\n",
        "Module governance.gov.",
        "Profile \"governed\".", "",
        "Rule decide given amount, produce Verdict:",
        "  Return Verdict.allow().", "");

    static final String NO_PROFILE_MISSING_ID_SRC = String.join("\n",
        "Module governance.plain.", "",
        "Rule decide given amount, produce Verdict:",
        "  Return Verdict.allow().", "");

    @Test
    void 未知档案的E705在编译通道保留error且编译成功() {
        var result = compiler.compile(UNKNOWN_PROFILE_SRC, "en-US");
        assertThat(result.isSuccess()).isTrue();
        var e705 = result.getDiagnostics().stream().filter(d -> "E705".equals(d.code())).toList();
        assertThat(e705).hasSize(1);
        assertThat(e705.get(0).severity()).isEqualTo("error");
        assertThat(e705.get(0).blocking()).isFalse();
    }

    @Test
    void 档案违规的E706为error而W700仍为warning() {
        var result = compiler.compile(GOVERNED_MISSING_ID_SRC, "en-US");
        assertThat(result.isSuccess()).isTrue();
        var e706 = result.getDiagnostics().stream().filter(d -> "E706".equals(d.code())).toList();
        assertThat(e706).isNotEmpty();
        assertThat(e706).allSatisfy(d -> {
            assertThat(d.severity()).isEqualTo("error");
            assertThat(d.blocking()).isFalse();
        });
        var w700 = result.getDiagnostics().stream().filter(d -> "W700".equals(d.code())).toList();
        assertThat(w700).isNotEmpty();
        assertThat(w700).allSatisfy(d -> assertThat(d.severity()).isEqualTo("warning"));
    }

    @Test
    void 未声明档案时治理诊断全部为warning() {
        var result = compiler.compile(NO_PROFILE_MISSING_ID_SRC, "en-US");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics()).anyMatch(d -> "W700".equals(d.code()));
        assertThat(result.getDiagnostics()).noneMatch(d -> "error".equals(d.severity()));
    }

    private void assertGovernanceWarning(String src, String code) {
        var result = compiler.compile(src, "en-US");
        assertThat(result.isSuccess()).isTrue();
        var hits = result.getDiagnostics().stream().filter(d -> code.equals(d.code())).toList();
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(d -> {
            assertThat(d.severity()).isEqualTo("warning");
            assertThat(d.blocking()).isFalse();
        });
    }
}
