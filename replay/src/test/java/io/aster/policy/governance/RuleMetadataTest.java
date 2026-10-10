package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
