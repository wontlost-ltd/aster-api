package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import io.aster.policy.parser.InProcessCnlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输入分类推导（ADR 0042 §12 D2）：声明树与 PII 标记只来自被执行 IR 的类型注解。 */
class InputClassifierTest {

  private static final String APPLICANT_TYPES = String.join("\n",
      "Define LineItem has sku as Text, note as @pii(L1, name) Text.",
      "Define Applicant has amount as Decimal, email as @pii(L2, email) Text, items as List of LineItem.", "");

  private static CoreModel.Module compiled(String source) {
    return InProcessCnlParser.parse(source).core();
  }

  private static String module(String name, String types, String rule) {
    return String.join("\n", "Module " + name + ".", "", types, rule, "");
  }

  private static final String DECIDE_ONE = String.join("\n",
      "Rule decide given applicant as Applicant, produce Verdict:",
      "  If applicant.amount greater than 10:",
      "    Return Verdict.require_approval(\"Credit Officer\", \"big\").",
      "  Return Verdict.allow().", "");

  @Test
  void declaredFieldsAndMarks() {
    CoreModel.Module m = compiled(module("t.cls", APPLICANT_TYPES, DECIDE_ONE));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", List.of("applicant"));
    assertNotNull(c);
    assertEquals(List.of("context.applicant.amount", "context.applicant.email", "context.applicant.items[].note",
        "context.applicant.items[].sku"), c.declared());
    assertEquals(List.of(new PiiMark("context.applicant.email", "L2", "email", "ir"),
        new PiiMark("context.applicant.items[].note", "L1", "name", "ir")), c.marks());
  }

  @Test
  void classifiesEveryNamedParameter() {
    String rule = String.join("\n",
        "Rule decide given applicant as Applicant, guarantor as Applicant, produce Verdict:",
        "  If applicant.amount greater than guarantor.amount:",
        "    Return Verdict.deny(\"x\").",
        "  Return Verdict.allow().", "");
    CoreModel.Module m = compiled(module("t.cls2", APPLICANT_TYPES, rule));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", List.of("applicant", "guarantor"));
    assertNotNull(c);
    assertTrue(c.declared().contains("context.guarantor.amount"));
    assertTrue(c.marks().contains(new PiiMark("context.guarantor.email", "L2", "email", "ir")));
    assertTrue(c.marks().contains(new PiiMark("context.applicant.email", "L2", "email", "ir")));
  }

  @Test
  void unmatchedParameterYieldsNoDeclaredPaths() {
    String rule = String.join("\n",
        "Rule decide given applicant as Applicant, guarantor as Applicant, produce Verdict:",
        "  Return Verdict.allow().", "");
    CoreModel.Module m = compiled(module("t.cls3", APPLICANT_TYPES, rule));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", Arrays.asList("applicant", null));
    assertNotNull(c);
    assertTrue(c.declared().stream().noneMatch(p -> p.startsWith("context.guarantor") || p.startsWith("context.null")));
    assertTrue(c.declared().contains("context.applicant.email"));
  }

  @Test
  void guardShapeMapsContextRecord() {
    String types = String.join("\n",
        "Define GuardContext has email as @pii(L2, email) Text, amount as Int.",
        "Define GuardRequest has principal_id as Text, principal_roles as List of Text, action as Text, "
            + "context as GuardContext.", "");
    String rule = String.join("\n",
        "Rule decide given req as GuardRequest, produce Verdict:",
        "  Return Verdict.allow().", "");
    CoreModel.Module m = compiled(module("t.guard", types, rule));
    InputClassification c = InputClassifier.guardShape(m, "decide", Map.of(
        "principal_id", "principal.id", "principal_roles", "principal.roles", "action", "action.name",
        "context", "context"));
    assertNotNull(c);
    assertEquals(List.of("action.name", "context.amount", "context.email", "principal.id", "principal.roles"),
        c.declared());
    assertEquals(List.of(new PiiMark("context.email", "L2", "email", "ir")), c.marks());
  }

  @Test
  void mapTypedContextHasNoDeclaredChildren() {
    String types = "Define GuardRequest has principal_id as Text, context as Map Text to Text.\n";
    String rule = String.join("\n",
        "Rule decide given req as GuardRequest, produce Verdict:",
        "  Return Verdict.allow().", "");
    CoreModel.Module m = compiled(module("t.map", types, rule));
    InputClassification c = InputClassifier.guardShape(m, "decide", Map.of("principal_id", "principal.id",
        "context", "context"));
    assertNotNull(c);
    assertEquals(List.of("principal.id"), c.declared());
    assertTrue(c.marks().isEmpty());
  }

  @Test
  void recursiveTypeTerminates() {
    CoreModel.Data node = data("Node", field("label", typeName("Text")), field("next", typeName("Node")));
    CoreModel.Module m = moduleOf(List.of(node), func("decide", param("n", typeName("Node"))));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", List.of("n"));
    assertNotNull(c);
    // 环在第二次展开同名类型处截断：next 不是声明叶子（视为未声明，打码为 unknown）
    assertEquals(List.of("context.n.label"), c.declared());
  }

  @Test
  void overLimitReturnsNull() {
    List<CoreModel.Field> fields = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      fields.add(field("f" + i, typeName("Text")));
    }
    CoreModel.Module m = moduleOf(List.of(data("Big", fields.toArray(CoreModel.Field[]::new))),
        func("decide", param("b", typeName("Big"))));
    assertNull(InputClassifier.evidenceShape(m, "decide", List.of("b")));
  }

  @Test
  void depthBeyondLimitReturnsNull() {
    List<CoreModel.Decl> decls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      decls.add(data("L" + i, field("v", typeName("Text")), field("down", typeName("L" + (i + 1)))));
    }
    decls.add(data("L10", field("v", typeName("Text"))));
    CoreModel.Module m = moduleOf(decls, func("decide", param("x", typeName("L0"))));
    assertNull(InputClassifier.evidenceShape(m, "decide", List.of("x")));
  }

  @Test
  void optionAndMaybeUnwrapToBaseType() {
    String types = "Define Contact has email as @pii(L2, email) Text?, phone as Option of @pii(L3, phone) Text.\n";
    String rule = String.join("\n",
        "Rule decide given c as Contact, produce Verdict:",
        "  Return Verdict.allow().", "");
    CoreModel.Module m = compiled(module("t.opt", types, rule));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", List.of("c"));
    assertNotNull(c);
    assertTrue(c.marks().contains(new PiiMark("context.c.email", "L2", "email", "ir")), c.marks().toString());
  }

  @Test
  void inferredTypesAreNotDeclared() {
    CoreModel.Field inferred = field("score", typeName("Int"));
    inferred.typeInferred = true;
    CoreModel.Module m = moduleOf(List.of(data("R", field("name", typeName("Text")), inferred)),
        func("decide", param("r", typeName("R"))));
    InputClassification c = InputClassifier.evidenceShape(m, "decide", List.of("r"));
    assertNotNull(c);
    assertEquals(List.of("context.r.name"), c.declared());
  }

  @Test
  void missingFunctionReturnsNull() {
    CoreModel.Module m = moduleOf(List.of(), func("decide", param("r", typeName("Text"))));
    assertNull(InputClassifier.evidenceShape(m, "other", List.of("r")));
    assertNull(InputClassifier.evidenceShape(null, "decide", List.of("r")));
  }

  @Test
  void jsonRoundTripIsStable() {
    InputClassification c = new InputClassification(List.of("context.a", "context.b"),
        List.of(new PiiMark("context.b", "L2", "email", "ir")));
    String json = c.toJson();
    assertEquals("{\"declared\":[\"context.a\",\"context.b\"],"
        + "\"marks\":[{\"category\":\"email\",\"level\":\"L2\",\"path\":\"context.b\",\"source\":\"ir\"}]}", json);
    assertEquals(c, InputClassification.fromJson(json));
    assertNull(InputClassification.fromJson(null));
    assertFalse(c.isDeclared("context.c"));
    assertTrue(c.isDeclared("context.b"));
  }

  // ---- 手工 IR 构造（递归与超限用例：引擎编译期会拒绝或过于冗长） ----

  private static CoreModel.TypeName typeName(String name) {
    CoreModel.TypeName t = new CoreModel.TypeName();
    t.name = name;
    return t;
  }

  private static CoreModel.Field field(String name, CoreModel.Type type) {
    CoreModel.Field f = new CoreModel.Field();
    f.name = name;
    f.type = type;
    return f;
  }

  private static CoreModel.Data data(String name, CoreModel.Field... fields) {
    CoreModel.Data d = new CoreModel.Data();
    d.name = name;
    d.fields = List.of(fields);
    return d;
  }

  private static CoreModel.Param param(String name, CoreModel.Type type) {
    CoreModel.Param p = new CoreModel.Param();
    p.name = name;
    p.type = type;
    return p;
  }

  private static CoreModel.Func func(String name, CoreModel.Param... params) {
    CoreModel.Func f = new CoreModel.Func();
    f.name = name;
    f.params = List.of(params);
    return f;
  }

  private static CoreModel.Module moduleOf(List<? extends CoreModel.Decl> types, CoreModel.Func f) {
    CoreModel.Module m = new CoreModel.Module();
    m.name = "t.manual";
    List<CoreModel.Decl> decls = new ArrayList<>(types);
    decls.add(f);
    m.decls = decls;
    return m;
  }
}
