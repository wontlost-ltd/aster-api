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
}
