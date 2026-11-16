package io.aster.guard.snapshot;

import io.aster.policy.common.PIIRedactor;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.PiiMark;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0042 §11 D2 正反例表逐行 + §12 D4 / D6 的视图与哈希规则。 */
class InputViewRendererTest {
  private static final UUID DECISION = UUID.fromString("3f1c2a4e-0000-4000-8000-000000000001");
  private static final String CTX_HASH = "c".repeat(64);
  private static final PIIRedactor REDACTOR = new PIIRedactor();
  /** GuardContext has amount as Decimal, email as @pii(L2, email) Text, items as List of LineItem；
   *  LineItem has sku as Text, note as @pii(L1, free-text) Text。 */
  private static final InputClassification CLS = new InputClassification(
      List.of("context.amount", "context.email", "context.items[].note", "context.items[].sku"),
      List.of(new PiiMark("context.email", "L2", "email", "ir"),
          new PiiMark("context.items[].note", "L1", "free-text", "ir")));

  private static Map<String, Object> action(Map<String, Object> context) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("principal", Map.of("id", "a@b.cc", "type", "agent"));
    a.put("action", Map.of("name", "approve_loan"));
    a.put("resource", Map.of("type", "loan", "id", "L-1"));
    a.put("context", context);
    return a;
  }

  private static Map<String, Object> sample() {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("amount", 250000);
    ctx.put("email", "a@b.cc");
    ctx.put("items", List.of(Map.of("sku", "SKU-1", "note", "vip customer")));
    ctx.put("extra", Map.of("k@x.io", 1));
    return ctx;
  }

  private static InputViewRenderer.RenderedView render(Map<String, Object> ctx, boolean revealed) {
    return InputViewRenderer.render(DECISION, CTX_HASH, action(ctx), CLS, "evaluation", "evaluated", sources(),
        revealed, REDACTOR);
  }

  private static Map<String, Object> sources() {
    return Map.of("verified", List.of(), "factIssuers", List.of(), "factSources", List.of());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> context(InputViewRenderer.RenderedView v) {
    return (Map<String, Object>) v.input().get("context");
  }

  @Test
  void plainDeclaredAmount() {
    assertThat(context(render(sample(), false))).containsEntry("amount", 250000);
  }

  @Test
  void declaredEmailMasked() {
    assertThat(context(render(sample(), false)).get("email"))
        .isEqualTo(Map.of("$pii", Map.of("level", "L2", "category", "email")));
  }

  @Test
  void arrayLengthVisible() {
    Map<String, Object> ctx = sample();
    ctx.put("items", List.of(Map.of("sku", "SKU-1"), Map.of("sku", "SKU-2")));
    assertThat(context(render(ctx, false)).get("items"))
        .isEqualTo(List.of(Map.of("sku", "SKU-1"), Map.of("sku", "SKU-2")));
  }

  @Test
  void arrayElementPiiMasked() {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) context(render(sample(), false)).get("items");
    assertThat(items.get(0).get("note")).isEqualTo(Map.of("$pii", Map.of("level", "L1", "category", "free-text")));
  }

  @Test
  void nonObjectElementUnknown() {
    Map<String, Object> ctx = sample();
    ctx.put("items", List.of(Map.of("sku", "SKU-1"), "not-an-object"));
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) context(render(ctx, false)).get("items");
    assertThat(items.get(1)).isEqualTo(InputViewRenderer.unknown());
  }

  private static final Map<String, Object> UNKNOWN = Map.of("$pii", Map.of("level", "unknown"));

  @Test
  void undeclaredKeysCollapseIntoSingleEntry() {
    Map<String, Object> ctx = sample();
    ctx.put("ssn-123-45-6789", "secret-a");
    ctx.put("secret@x.io", Map.of("deep", "secret-b"));
    InputViewRenderer.RenderedView v = render(ctx, false);
    assertThat(context(v)).containsOnlyKeys("amount", "email", "items", "$undeclared");
    assertThat(context(v).get("$undeclared")).isEqualTo(UNKNOWN);
    assertNoneOf(v, "extra", "k@x.io", "ssn-123-45-6789", "secret@x.io", "deep", "secret-a", "secret-b");
  }

  @Test
  void noUndeclaredEntryWhenAllKeysDeclared() {
    Map<String, Object> ctx = sample();
    ctx.remove("extra");
    assertThat(context(render(ctx, false))).containsOnlyKeys("amount", "email", "items");
  }

  @Test
  void undeclaredKeysInsideDeclaredRecordCollapse() {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sku", "SKU-1");
    item.put("leak-name-1", 1);
    item.put("leak-name-2", Map.of("leak-name-3", 2));
    Map<String, Object> ctx = sample();
    ctx.put("items", List.of(item, Map.of("sku", "SKU-2", "leak-name-4", 4)));
    InputViewRenderer.RenderedView v = render(ctx, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) context(v).get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0)).containsOnlyKeys("sku", "$undeclared").containsEntry("$undeclared", UNKNOWN);
    assertThat(items.get(1)).containsOnlyKeys("sku", "$undeclared");
    assertNoneOf(v, "leak-name-1", "leak-name-2", "leak-name-3", "leak-name-4");
  }

  @Test
  void undeclaredKeysInNestedObjectsCollapseAtEachLevel() {
    InputClassification nested = new InputClassification(List.of("context.applicant.age", "context.applicant.home.zip"),
        List.of());
    Map<String, Object> home = new LinkedHashMap<>(Map.of("zip", "200000"));
    home.put("home-secret-key", "x");
    Map<String, Object> applicant = new LinkedHashMap<>();
    applicant.put("age", 30);
    applicant.put("home", home);
    applicant.put("applicant-secret-key", "y");
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("applicant", applicant);
    ctx.put("top-secret-key", "z");
    InputViewRenderer.RenderedView v = InputViewRenderer.render(DECISION, CTX_HASH, action(ctx), nested, "version",
        "direct", sources(), false, REDACTOR);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("applicant", Map.of("age", 30, "home", Map.of("zip", "200000", "$undeclared", UNKNOWN),
        "$undeclared", UNKNOWN));
    expected.put("$undeclared", UNKNOWN);
    assertThat(context(v)).isEqualTo(expected);
    assertNoneOf(v, "home-secret-key", "applicant-secret-key", "top-secret-key");
  }

  @Test
  void ambiguousKeyNamesAreTreatedAsUndeclared() {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("amount", 1);
    ctx.put("items[]", "a");
    ctx.put("a\u0001b", "b");
    ctx.put("", "c");
    InputViewRenderer.RenderedView v = render(ctx, false);
    assertThat(context(v)).containsOnlyKeys("amount", "$undeclared");
  }

  @Test
  void keyNamedLikeDeclaredPathCannotImpersonateIt() {
    // 键 "items.sku" 拼出的路径是 context.items.sku，而不是 context.items[].sku
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("items.sku", "impersonator-value");
    InputViewRenderer.RenderedView v = render(ctx, false);
    assertThat(context(v)).isEqualTo(Map.of("$undeclared", UNKNOWN));
  }

  /** 视图（含 piiMarks）里不得出现这些字符串。 */
  private static void assertNoneOf(InputViewRenderer.RenderedView v, String... needles) {
    String rendered = v.input().toString() + v.piiMarks();
    for (String needle : needles) {
      assertThat(rendered).as("view contains %s", needle).doesNotContain(needle);
    }
  }

  @Test
  void hashedFormDoesNotDependOnUndeclaredKeyNames() {
    // 哈希载荷只含渲染后的视图：未声明键名不同、值不同，哈希必须相同，否则键名泄进了哈希体。
    Map<String, Object> a = sample();
    a.put("name-alpha", Map.of("n1", 1));
    Map<String, Object> b = sample();
    b.put("name-beta", "scalar");
    b.put("name-gamma", List.of(1, 2));
    assertThat(render(a, false).hash()).isEqualTo(render(b, false).hash());
    assertThat(render(a, false).sizeBytes()).isEqualTo(render(b, false).sizeBytes());
  }

  @Test
  void nonObjectContextIsUnknown() {
    for (Object bad : new Object[] {"scalar-secret", List.of(Map.of("k-secret", 1))}) {
      Map<String, Object> a = action(null);
      a.put("context", bad);
      InputViewRenderer.RenderedView v = InputViewRenderer.render(DECISION, CTX_HASH, a, CLS, "evaluation",
          "evaluated", sources(), false, REDACTOR);
      assertThat(v.input().get("context")).isEqualTo(UNKNOWN);
      assertNoneOf(v, "scalar-secret", "k-secret");
    }
  }

  @Test
  void pinnedViewHashVector() {
    // 钉死掩码视图的哈希（已用 Python 对排序紧凑 JSON 独立复算一致）：未声明键合并为 $undeclared 后的 v1 规则输出；规则再变须同步升 MASK_RULES 并更新此向量。
    assertThat(render(sample(), false).hash()).isEqualTo("bece624c20aa4c6be370512262c0b34faef1ba3ab69a8c807e03f42f238b458e");
  }

  @Test
  void secondLayerRegexOnDeclaredText() {
    Map<String, Object> ctx = sample();
    ctx.put("items", List.of(Map.of("sku", "call 021-555-0100")));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) context(render(ctx, false)).get("items");
    assertThat((String) items.get(0).get("sku")).isEqualTo(REDACTOR.redact("call 021-555-0100"))
        .isNotEqualTo("call 021-555-0100");
  }

  @Test
  void principalIdExempt() {
    @SuppressWarnings("unchecked")
    Map<String, Object> principal = (Map<String, Object>) render(sample(), false).input().get("principal");
    assertThat(principal).containsEntry("id", "a@b.cc");
  }

  @Test
  void canonicalUuidTextNotRewritten() {
    Map<String, Object> ctx = sample();
    String uuid = "1234abcd-1234-4234-9234-123412341234";
    ctx.put("items", List.of(Map.of("sku", uuid)));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) context(render(ctx, false)).get("items");
    assertThat(items.get(0)).containsEntry("sku", uuid);
  }

  @Test
  void revealRestoresOnlyDeclaredPii() {
    InputViewRenderer.RenderedView v = render(sample(), true);
    assertThat(context(v)).containsEntry("email", "a@b.cc");
    assertThat(context(v)).doesNotContainKey("extra").containsEntry("$undeclared", UNKNOWN);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) context(v).get("items");
    assertThat(items.get(0)).containsEntry("note", "vip customer");
    assertThat(v.revealedFields()).isEqualTo(2);
    assertThat(render(sample(), false).revealedFields()).isZero();
  }

  @Test
  void marksSourceNoneHidesAllContext() {
    InputViewRenderer.RenderedView v = InputViewRenderer.render(DECISION, CTX_HASH, action(sample()), CLS, "none",
        "unbound", sources(), true, REDACTOR);
    assertThat(context(v)).isEqualTo(Map.of("$undeclared", UNKNOWN));
    assertNoneOf(v, "amount", "email", "items", "extra", "k@x.io");
    assertThat(v.piiMarks()).isEmpty();
    assertThat(v.revealedFields()).isZero();
  }

  @Test
  void hashStableAcrossKeyOrder() {
    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("extra", Map.of("k@x.io", 1));
    reversed.put("items", List.of(Map.of("note", "vip customer", "sku", "SKU-1")));
    reversed.put("email", "a@b.cc");
    reversed.put("amount", 250000);
    assertThat(render(reversed, false).hash()).isEqualTo(render(sample(), false).hash()).hasSize(64);
  }

  @Test
  void hashDiffersMaskedVsRevealed() {
    assertThat(render(sample(), true).hash()).isNotEqualTo(render(sample(), false).hash());
  }

  @Test
  void hashCoversInputSourcesBindingAndMarksSource() {
    String base = render(sample(), false).hash();
    String withVerified = InputViewRenderer.render(DECISION, CTX_HASH, action(sample()), CLS, "evaluation", "evaluated",
        Map.of("verified", List.of("context.amount"), "factIssuers", List.of(), "factSources", List.of()), false,
        REDACTOR).hash();
    String unbound = InputViewRenderer.render(DECISION, CTX_HASH, action(sample()), CLS, "evaluation", "unbound",
        sources(), false, REDACTOR).hash();
    assertThat(withVerified).isNotEqualTo(base);
    assertThat(unbound).isNotEqualTo(base);
  }

  @Test
  void depthBeyondEightIsUnknown() {
    // context(1).a(2).b(3).c(4).d(5).e(6).f(7).g(8).h(9)
    InputClassification deep = new InputClassification(List.of("context.a.b.c.d.e.f.g", "context.a.b.c.d.e.f.h.i"),
        List.of());
    Map<String, Object> h = Map.of("i", "deep");
    Map<String, Object> f = new LinkedHashMap<>(Map.of("g", "ok", "h", h));
    Map<String, Object> ctx = Map.of("a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", Map.of("f", f))))));
    InputViewRenderer.RenderedView v = InputViewRenderer.render(DECISION, CTX_HASH, action(ctx), deep, "version",
        "direct", sources(), false, REDACTOR);
    String rendered = v.input().get("context").toString();
    assertThat(rendered).contains("g=ok").doesNotContain("deep");
  }

  @Test
  void malformedMarkedValueIsUnknownEvenWhenRevealed() {
    Map<String, Object> ctx = sample();
    ctx.put("email", Map.of("hidden", "secret@x.io"));
    InputViewRenderer.RenderedView v = render(ctx, true);
    assertThat(context(v).get("email")).isEqualTo(InputViewRenderer.unknown());
    assertThat(v.input().toString()).doesNotContain("secret@x.io");
    ctx.put("email", List.of(Map.of("k", "secret@x.io")));
    assertThat(render(ctx, true).input().toString()).doesNotContain("secret@x.io");
  }

  @Test
  void nullMarkedValueIsNotCountedAsRevealed() {
    Map<String, Object> ctx = sample();
    ctx.put("email", null);
    ctx.put("items", List.of());
    assertThat(render(ctx, true).revealedFields()).isZero();
  }
}
