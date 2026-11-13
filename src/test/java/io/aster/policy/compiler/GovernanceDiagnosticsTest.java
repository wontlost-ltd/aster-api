package io.aster.policy.compiler;

import aster.core.typecheck.governance.ControlRegistry;
import aster.core.typecheck.governance.GovernanceChecker;
import io.aster.policy.analysis.RuleConflictDiagnostics;
import io.aster.policy.stability.StabilityEnforcement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E708（ADR 0046 §11 D2 第 2 条）在编译通道的严重级：与 E705/E706 同为档案码，error 且不阻断编译（cloud 保存门据此拒绝）。
 * 构造检查器时注入要求 verifiedInputs 的注册表，使用例不随内置注册表版本（1.3.0 起高风险档案要求）变化。
 */
class GovernanceDiagnosticsTest {

  /** 规则 decide 用动态键读 Map（{@code Map.get(req.meta, req.action)}），输入引用无法静态确定。 */
  private static final String DYNAMIC_KEY_SRC = String.join("\n",
      "Module governance.dynamic.",
      "Profile \"hr\".", "",
      "Define Req has action as Text, meta as Map Text to Text.", "",
      "@id(\"R-1\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule decide given req as Req, produce Verdict:",
      "  If Map.get(req.meta, req.action) equals to \"x\":",
      "    Return Verdict.deny(\"d\").",
      "  Return Verdict.allow().", "");

  private static GovernanceChecker checker(boolean verifiedInputs) {
    return new GovernanceChecker(new ControlRegistry("t", Set.of("EU_AI_ACT:ART14"),
        Map.of("EU_AI_ACT:ART14", "EU_AI_ACT"),
        Map.of("hr", new ControlRegistry.Profile("hr", true, true, List.of("EU_AI_ACT"), verifiedInputs))));
  }

  private static List<CompilationResult.Diagnostic> governance(CompilationResult result) {
    return result.getDiagnostics().stream()
        .filter(d -> d.code() != null && (d.code().startsWith("E70") || d.code().startsWith("W70")))
        .toList();
  }

  @Test
  void e708IsErrorSeverityWithSuccessTrue() {
    var compiler = new PolicyCompiler(null, new StabilityEnforcement(), new RuleConflictDiagnostics(), checker(true));
    var result = compiler.compile(DYNAMIC_KEY_SRC, "en-US");

    assertThat(result.isSuccess()).isTrue();
    var e708 = governance(result).stream().filter(d -> "E708".equals(d.code())).toList();
    assertThat(e708).hasSize(1);
    var d = e708.get(0);
    assertThat(d.severity()).isEqualTo("error");
    assertThat(d.blocking()).isFalse();
    assertThat(d.message()).isEqualTo(
        "Rule 'decide' reads inputs that cannot be resolved statically (dynamic-map-key); "
            + "profile 'hr' requires verified inputs");
    assertThat(d.line()).isGreaterThan(1);
  }

  /** 对照组：同一源文在不要求 verifiedInputs 的注册表下没有 E708。 */
  @Test
  void noE708WhenProfileDoesNotRequireVerifiedInputs() {
    var compiler = new PolicyCompiler(null, new StabilityEnforcement(), new RuleConflictDiagnostics(), checker(false));
    var result = compiler.compile(DYNAMIC_KEY_SRC, "en-US");

    assertThat(result.isSuccess()).isTrue();
    assertThat(governance(result)).noneMatch(d -> "E708".equals(d.code()));
  }
}
