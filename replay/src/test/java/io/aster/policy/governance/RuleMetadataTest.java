package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.ControlRegistry;
import io.aster.policy.parser.InProcessCnlParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// replay 模块测试只依赖 JUnit（无 AssertJ），断言语义与 brief 中 AssertJ 版本一致。
class RuleMetadataTest {
  private static CoreModel.Func func(String name, CoreModel.Annotation... anns) {
    var f = new CoreModel.Func(); f.name = name; f.annotations = List.of(anns); return f;
  }
  private static CoreModel.Annotation ann(String name, String value) {
    var a = new CoreModel.Annotation(); a.name = name; a.params = Map.of("$0", value); return a;
  }
  @Test void 读取被执行函数的id与controls() {
    var mod = new CoreModel.Module();
    mod.decls = List.of(func("other", ann("id", "X")), func("decide", ann("id", "VB-001"), ann("control", "EU_AI_ACT:ART14"), ann("control", "GDPR:ART6")));
    var meta = RuleMetadata.of(mod, "decide");
    assertEquals("VB-001", meta.ruleId());
    assertEquals(List.of("EU_AI_ACT:ART14", "GDPR:ART6"), meta.controls());
  }
  @Test void 无注解或函数不存在() {
    var mod = new CoreModel.Module(); mod.decls = List.of(func("decide"));
    assertEquals(new RuleMetadata(null, List.of()), RuleMetadata.of(mod, "decide"));
    assertEquals(new RuleMetadata(null, List.of()), RuleMetadata.of(mod, "nope"));
    assertEquals(new RuleMetadata(null, List.of()), RuleMetadata.of(null, "decide"));
    assertNull(RuleMetadata.NONE.profile());
  }
  @Test void 读取模块声明的profile() {
    var mod = new CoreModel.Module(); mod.profile = "governed";
    mod.decls = List.of(func("decide", ann("id", "G-1")));
    assertEquals(new RuleMetadata("G-1", List.of(), "governed"), RuleMetadata.of(mod, "decide"));
    assertEquals(new RuleMetadata(null, List.of(), "governed"), RuleMetadata.of(mod, "nope"));
  }
  @Test void 无函数体的档案Verdict外函数无inputRefs且默认注册表不要求verifiedInputs() {
    var mod = new CoreModel.Module(); mod.profile = "eu-ai-act-high-risk";
    mod.decls = List.of(func("decide", ann("id", "G-1")));
    var meta = RuleMetadata.of(mod, "decide");
    // 未声明返回 Verdict：不在 inputRefs 写入范围（与引擎同口径）
    assertNull(meta.inputRefs());
    assertFalse(meta.verifiedInputsRequired());
    assertNull(RuleMetadata.NONE.inputRefs());
    assertFalse(RuleMetadata.NONE.verifiedInputsRequired());
    assertEquals(new RuleMetadata("G-1", List.of(), "eu-ai-act-high-risk", null, false), meta);
  }

  // ---- Task 16b：inputRefs 与 verifiedInputs（ADR 0046 §11 D1/D2、§11.6 第 19 条） ----

  /** 读 action 与 context.amount、档案模块的 Verdict 规则；helper 不返回 Verdict。 */
  static final String PROFILED_SOURCE = String.join("\n",
      "Module meta.refs.",
      "Profile \"eu-ai-act-high-risk\".", "",
      "Define Req has action as Text, amount as Int.", "",
      "@id(\"R-1\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule decide given req as Req, produce Verdict:",
      "  If req.amount greater than 10:",
      "    Return Verdict.deny(req.action).",
      "  Return Verdict.allow().", "",
      "Rule helper given n as Int, produce Int:",
      "  Return n.", "");

  private static CoreModel.Module compiled(String source) {
    return InProcessCnlParser.parse(source).core();
  }

  private static CoreModel.Func funcOf(CoreModel.Module mod, String name) {
    return mod.decls.stream().filter(d -> d instanceof CoreModel.Func f && name.equals(f.name))
        .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
  }

  private static ControlRegistry registry(String profile, boolean verifiedInputs) {
    return new ControlRegistry("t", Set.of("EU_AI_ACT:ART14"), Map.of("EU_AI_ACT:ART14", "EU_AI_ACT"),
        Map.of(profile, new ControlRegistry.Profile(profile, true, true, List.of("EU_AI_ACT"), verifiedInputs)));
  }

  /** 编译通道产出的 IR：元数据的 inputRefs 与引擎写入 IR 的值逐项相同（引擎源码路径，未映射）。 */
  @Test void ruleMetadataReadsInputRefsFromIr() {
    var mod = compiled(PROFILED_SOURCE);
    assertEquals(List.of("action", "amount"), funcOf(mod, "decide").inputRefs, "引擎应已写入 inputRefs");
    var meta = RuleMetadata.of(mod, "decide");
    assertEquals(List.of("action", "amount"), meta.inputRefs());
    assertEquals("R-1", meta.ruleId());
    assertEquals("eu-ai-act-high-risk", meta.profile());
  }

  /** 不可信 IR（如 /evaluate-json 提交的 Core IR）伪造 inputRefs：一律按函数体重算，不采信 IR 自带值。 */
  @Test void forgedIrInputRefsAreRecomputed() {
    var mod = compiled(PROFILED_SOURCE);
    funcOf(mod, "decide").inputRefs = List.of();
    assertEquals(List.of("action", "amount"), RuleMetadata.of(mod, "decide").inputRefs());
    funcOf(mod, "decide").inputRefs = null;
    assertEquals(List.of("action", "amount"), RuleMetadata.of(mod, "decide").inputRefs());
  }

  /** 非 Verdict 函数即使 IR 带了 inputRefs 也不读（引擎不为它写入，伪造值同样忽略）。 */
  @Test void nonVerdictFunctionHasNullInputRefsEvenIfIrCarriesOne() {
    var mod = compiled(PROFILED_SOURCE);
    funcOf(mod, "helper").inputRefs = List.of();
    assertNull(RuleMetadata.of(mod, "helper").inputRefs());
  }

  @Test void noProfileModuleHasNullInputRefs() {
    var mod = compiled(PROFILED_SOURCE.replace("Profile \"eu-ai-act-high-risk\".", ""));
    assertNull(mod.profile);
    funcOf(mod, "decide").inputRefs = List.of("forged");
    var meta = RuleMetadata.of(mod, "decide", registry("eu-ai-act-high-risk", true));
    assertNull(meta.inputRefs());
    assertFalse(meta.verifiedInputsRequired());
  }

  /** verifiedInputsRequired 是模块级属性：函数不存在时的 moduleOnly 也要带上。 */
  @Test void moduleOnlyCarriesVerifiedInputsRequired() {
    var mod = compiled(PROFILED_SOURCE);
    var reg = registry("eu-ai-act-high-risk", true);
    assertTrue(RuleMetadata.of(mod, "decide", reg).verifiedInputsRequired());
    var moduleOnly = RuleMetadata.of(mod, "nope", reg);
    assertEquals(new RuleMetadata(null, List.of(), "eu-ai-act-high-risk", null, true), moduleOnly);
    assertEquals(new RuleMetadata(null, List.of(), "eu-ai-act-high-risk", null, true),
        RuleMetadata.of(mod, null, reg));
    assertFalse(RuleMetadata.of(mod, "decide", registry("eu-ai-act-high-risk", false)).verifiedInputsRequired());
  }

  /** 未知档案（注册表里没有）按不要求处理：编译期已报 E705（ADR 0046 §11.6 第 6 条）。 */
  @Test void unknownProfileIsNotRequired() {
    var mod = compiled(PROFILED_SOURCE);
    var meta = RuleMetadata.of(mod, "decide", registry("other", true));
    assertFalse(meta.verifiedInputsRequired());
    // inputRefs 的写入条件不看注册表（声明了档案即写）
    assertEquals(List.of("action", "amount"), meta.inputRefs());
    assertFalse(ProfileRequirementsLookup.verifiedInputsRequired(registry("other", true), null));
    assertFalse(ProfileRequirementsLookup.verifiedInputsRequired(registry("other", true), ""));
    assertFalse(ProfileRequirementsLookup.verifiedInputsRequired(null, "other"));
    assertTrue(ProfileRequirementsLookup.verifiedInputsRequired(registry("other", true), "other"));
  }

  /** 默认注册表（1.2.0 起有 verifiedInputs 键，全部为 false）下任何档案都不要求。 */
  @Test void defaultRegistryRequiresNothing() {
    ControlRegistry reg = ControlRegistry.loadDefault();
    assertNotNull(reg.profile("eu-ai-act-high-risk").orElse(null));
    reg.profiles().keySet().forEach(id ->
        assertFalse(ProfileRequirementsLookup.verifiedInputsRequired(reg, id), id));
    assertFalse(RuleMetadata.of(compiled(PROFILED_SOURCE), "decide").verifiedInputsRequired());
  }
}
