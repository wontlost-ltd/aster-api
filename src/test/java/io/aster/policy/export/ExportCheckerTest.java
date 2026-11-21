package io.aster.policy.export;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.embed.InProcessCnlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 闭世界导出检查（ADR 0059 §8）：E730 / E731 / E732 / E737 各一个最小模块；conformance 正例在 jre 下零诊断。 */
class ExportCheckerTest {
  private static final ExportBuiltins JRE = ExportBuiltins.forTarget("jre").orElseThrow();

  private static CoreModel.Module compile(String... lines) {
    return new CoreLowering().lowerModule(InProcessCnlParser.parse(String.join("\n", lines), "en-US").module());
  }

  private static List<ExportDiagnostic> check(CoreModel.Module m) {
    return ExportChecker.checkModule(m, "jre", JRE);
  }

  @Test
  void e730DeclaredIo() {
    List<ExportDiagnostic> ds = check(compile("Module t.io.", "", "Rule fetch given x as Int, produce Int. It performs io:",
        "  Return x."));
    assertThat(ds).extracting(ExportDiagnostic::code).containsExactly("E730");
    assertThat(ds.get(0).message())
        .isEqualTo("Function 'fetch' declares IO capability io; exported packages cannot perform IO");
    assertThat(ds.get(0).line()).isEqualTo(3);
  }

  @Test
  void e731Workflow() {
    List<ExportDiagnostic> ds = check(compile("Module t.wf.", "", "Rule run given n as Int, produce Int. It performs io:",
        "  workflow:", "    step first:", "      Return ok of n.", "  .", "  Return n."));
    assertThat(ds).extracting(ExportDiagnostic::code).contains("E731", "E730");
    ExportDiagnostic e731 = ds.stream().filter(d -> d.code().equals("E731")).findFirst().orElseThrow();
    assertThat(e731.message()).isEqualTo("Function 'run' uses workflow (workflow / async); exported packages cannot await");
    assertThat(e731.line()).isEqualTo(4);
  }

  @Test
  void e732UnknownNameWithPosition() {
    List<ExportDiagnostic> ds = check(compile("Module t.name.", "", "Rule shout given x as Text, produce Text:",
        "  Let y be x.", "  Return IO.print(y)."));
    assertThat(ds).extracting(ExportDiagnostic::code).containsExactly("E732");
    assertThat(ds.get(0).message()).isEqualTo("Name 'IO.print' in function 'shout' does not resolve to a module function, "
        + "local binding, type constructor or a pure builtin of the jre engine");
    assertThat(ds.get(0).line()).isEqualTo(5);
    assertThat(ds.get(0).column()).isNotNull();
  }

  @Test
  void localsFunctionsConstructorsAndAliasesResolve() {
    List<ExportDiagnostic> ds = check(compile("Module t.ok.", "",
        "Define Tier as one of Gold, Silver.", "",
        "Rule twice given n as Int, produce Int:", "  Return n times 2.", "",
        "Rule f given xs as List of Int, t as Tier, produce Int:",
        "  Let total be List.length(xs).",
        "  Match t:",
        "    When Gold, Return twice(total).",
        "    When Silver, Return total plus 1."));
    assertThat(ds).isEmpty();
  }

  @Test
  void e737MainShadowsEntryOnJre() {
    CoreModel.Module m = compile("Module t.main.", "", "Rule main given x as Int, produce Int:", "  Return x.", "",
        "Rule other given x as Int, produce Int:", "  Return x.");
    assertThat(ExportChecker.checkEntry(m, "other", "jre")).extracting(ExportDiagnostic::message)
        .containsExactly("Entry 'other' is shadowed by function 'main' on the JVM engine");
    assertThat(ExportChecker.checkEntry(m, "main", "jre")).isEmpty();
    assertThat(ExportChecker.checkEntry(m, "other", "node")).isEmpty();
  }

  @Test
  void conformancePositivesHaveNoDiagnosticsOnJre() throws Exception {
    for (String pkg : List.of("b-credit-limit", "b-zh", "b-alias", "a-inputs", "a-multi-entry", "a-decimal")) {
      CoreModel.Module m = EntrySchemaGeneratorTest.platformModule(pkg);
      assertThat(check(m)).as(pkg).isEmpty();
    }
  }
}
