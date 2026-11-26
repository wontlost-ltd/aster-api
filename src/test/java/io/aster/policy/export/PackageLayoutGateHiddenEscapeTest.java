package io.aster.policy.export;

import aster.core.embed.PolicySourceCompiler;
import aster.core.identifier.VocabularyScopes;
import aster.core.layout.LayoutFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.test.JingyesiPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排版闸门的 L10（ADR 0059 修订 5、ADR 0064）：L10a 随 {@code checkPackage}，L10b 在 L9 之后用与本次导出相同的编译输入
 * 编译每个 {@code C⁻(S)}。
 *
 * <p>{@code src/test/resources/layout/display-escape.json} 是 aster-lang-test {@code conformance/package-v1/vectors/display-escape.json}
 * 的逐字节副本（与 aster-lang-core 1.0.56 {@code src/test/resources/layout/display-escape.json} 相同），sha256 钉在
 * {@link #VECTORS_SHA256}；上游更新时整份重新复制并更新该值。
 */
@QuarkusTest
class PackageLayoutGateHiddenEscapeTest {
  static final Path VECTORS = Path.of("src/test/resources/layout/display-escape.json");
  static final String VECTORS_SHA256 = "8e7b1e9afba5a443f74b16e154418fa2e3bb9a142322114165c63b5aac94d5fb";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PolicyCompiler compiler;
  @Inject ExportRuntimes runtimes;

  /** 数编译次数的编译器：委托给容器里的平台编译器，只多一个计数。 */
  static class CountingCompiler extends PolicyCompiler {
    final AtomicInteger calls = new AtomicInteger();
    private final PolicyCompiler delegate;

    CountingCompiler(PolicyCompiler delegate) {
      super(null, null, null);
      this.delegate = delegate;
    }

    @Override
    public CompilationResult compile(String sourceCnl, String locale, String aliasSetJson, VocabularyScopes scopes,
                                     boolean allowStructural) {
      calls.incrementAndGet();
      return delegate.compile(sourceCnl, locale, aliasSetJson, scopes, allowStructural);
    }
  }

  /** 数 {@code C⁻(S)} 编译次数的闸门（L10b 走 core 规范编译入口，不经 {@link PolicyCompiler}）。 */
  static class CountingGate extends PackageLayoutGate {
    final AtomicInteger variants = new AtomicInteger();

    CountingGate(PolicyCompiler compiler, ExportRuntimes runtimes) {
      super(compiler, runtimes);
    }

    @Override
    String variantPositionFreeSha256(String source, CanonicalInputs in) {
      variants.incrementAndGet();
      return super.variantPositionFreeSha256(source, in);
    }
  }

  /** 平台编译结果（闸门的 L8 / L9 输入），由测试用同一编译器预先算好，不计入闸门的编译次数。 */
  private record Platform(String coreJson, String positionFreeSha256) {
  }

  private Platform platform(PackageExportService.ExportSpec spec) throws Exception {
    Platform p = platformOrNull(spec);
    assertThat(p).as("规范源码须能编译：%s", spec.source()).isNotNull();
    return p;
  }

  /** 规范源码编译不了时返回 null（只允许出现在 L10a 向量上：闸门在编译前就拒了）。 */
  private Platform platformOrNull(PackageExportService.ExportSpec spec) throws Exception {
    CompilationResult r = compileLike(spec, spec.source());
    if (!compiles(r)) {
      return null;
    }
    return new Platform(r.getCoreJson(), PolicySourceCompiler.positionFreeSha256(r.getCoreJson()));
  }

  private CompilationResult compileLike(PackageExportService.ExportSpec spec, String source) {
    return compiler.compile(source, spec.locale(), PackageExportService.aliasSetJson(spec.aliasSet()),
        io.aster.replay.core.ReplayExecutionCore.buildVocabularyScopes(spec.vocabulary()), spec.allowStructural());
  }

  private static boolean compiles(CompilationResult r) {
    return r.isSuccess() && r.getDiagnostics().stream().noneMatch(d -> "error".equals(d.severity()));
  }

  private static PackageExportService.ExportSpec spec(String source, String locale, Map<String, List<String>> aliasSet,
                                                      boolean allowStructural, Map<String, Object> vocabulary) {
    return new PackageExportService.ExportSpec(source, locale, aliasSet, allowStructural, vocabulary, null, "jre", "zh",
        new PackageAssembler.ClaimedRef("pol-escape", 1, "APPROVED", "2026-11-26T00:00:00Z"), "tenant-escape-test");
  }

  static PackageExportService.ExportSpec spec(JsonNode c) {
    String source = LayoutFormat.parseStrict(c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8)).toCanonical();
    Map<String, List<String>> aliasSet = c.hasNonNull("aliasSet")
        ? JSON.convertValue(c.get("aliasSet"), new TypeReference<LinkedHashMap<String, List<String>>>() { })
        : null;
    Map<String, Object> vocabulary = c.hasNonNull("vocabulary")
        ? JSON.convertValue(c.get("vocabulary"), new TypeReference<LinkedHashMap<String, Object>>() { })
        : null;
    return spec(source, c.path("lexicon").asText("en-US"), aliasSet, c.path("allowStructural").asBoolean(false), vocabulary);
  }

  static JsonNode vectorCase(String id) throws Exception {
    for (JsonNode c : JSON.readTree(VECTORS.toFile()).path("cases")) {
      if (id.equals(c.path("id").asText())) {
        return c;
      }
    }
    throw new IllegalArgumentException(id);
  }

  @Test
  void vectorCopyIsPinned() throws Exception {
    byte[] bytes = Files.readAllBytes(VECTORS);
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(VECTORS_SHA256);
  }

  /**
   * 共享向量：闸门判定的第一条失败规则等于 {@code expect}（次序 L1–L7、L10a、L8、L9、L10b）；ok 则随包，其余省略且原因码为
   * {@code display_rules_failed}。编译次数：L10a 失败不编译；ok 恰为平台编译器 1 次（L9）加 2ⁿ − 1 个 {@code C⁻(S)}，{@code C} 本身不重编。
   */
  @TestFactory
  Stream<DynamicTest> sharedEscapeVectors() throws Exception {
    JsonNode cases = JSON.readTree(VECTORS.toFile()).path("cases");
    List<String> ids = new ArrayList<>();
    cases.forEach(c -> ids.add(c.path("id").asText()));
    // 修订 5 §6 的 E1–E21 中：E7 不再适用（两引擎字符串都不得跨行，#357），E8 只能以补空格向量覆盖（core
    // display-padding.json 的 escape-E8，padStructural 属 core），E21 测显示面标记（包一致性 show 的 stderr）——见向量 description
    assertThat(ids).containsExactly("E1", "E2", "E3", "E4", "E5", "E6", "E9", "E10", "E11", "E12", "E13", "E13b", "E14",
        "E15", "E15b", "E16", "E17", "E18", "E19", "E20a", "E20b");
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : cases) {
      tests.add(DynamicTest.dynamicTest(c.path("id").asText() + " → " + c.path("expect").asText(), () -> assertVector(c)));
    }
    return tests.stream();
  }

  private void assertVector(JsonNode c) throws Exception {
    PackageExportService.ExportSpec spec = spec(c);
    String layout = c.path("layoutText").textValue();
    String expect = c.path("expect").asText();
    Platform p = platformOrNull(spec);
    if (p == null) {
      assertThat(expect).as("只有 L10a 向量的规范源码可以编译不了").isEqualTo("L10a");
      p = new Platform("{}", "0".repeat(64));
    }
    int triples = c.path("triples").size();

    CountingCompiler counting = new CountingCompiler(compiler);
    CountingGate gate = new CountingGate(counting, runtimes);
    String rule = gate.checkRules(layout.getBytes(StandardCharsets.UTF_8), spec, p.coreJson(), p.positionFreeSha256());
    assertThat(rule == null ? "ok" : rule).isEqualTo(expect);
    switch (expect) {
      case "L10a" -> {
        assertThat(counting.calls.get()).as("L10a 不编译").isZero();
        assertThat(gate.variants.get()).isZero();
      }
      case "L9" -> assertThat(gate.variants.get()).as("L9 失败不进 L10b").isZero();
      case "ok" -> {
        assertThat(counting.calls.get()).as("平台编译器只编 L9 的一次").isEqualTo(1);
        assertThat(gate.variants.get()).as("2ⁿ − 1 个 C⁻(S)，C 本身不重编").isEqualTo((1 << triples) - 1);
      }
      default -> {
        assertThat(counting.calls.get()).isEqualTo(1);
        assertThat(gate.variants.get()).isBetween(1, (1 << triples) - 1);
      }
    }

    PackageLayoutGate.LayoutOutcome o = gate.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256());
    if ("ok".equals(expect)) {
      assertThat(o.status()).isEqualTo(PackageLayoutGate.INCLUDED);
      assertThat(o.layoutJson()).isEqualTo(layout.getBytes(StandardCharsets.UTF_8));
      assertThat(new String(o.displayAster(), StandardCharsets.UTF_8))
          .isEqualTo(LayoutFormat.parseStrict(layout.getBytes(StandardCharsets.UTF_8)).toDisplay());
    } else {
      assertThat(o.status()).isEqualTo(PackageLayoutGate.OMITTED);
      assertThat(o.reason()).isEqualTo(PackageLayoutGate.REASON_RULES_FAILED);
    }
  }

  /** E2（`版本` 去反引号后成了字段 version）与 E20b：被拒的是 L10b，不是更早的规则。 */
  @Test
  void l10bNegativeCasesAreRejectedAsL10b() throws Exception {
    for (String id : List.of("E2", "E4", "E5", "E20b")) {
      JsonNode c = vectorCase(id);
      PackageExportService.ExportSpec spec = spec(c);
      Platform p = platform(spec);
      PackageLayoutGate gate = new PackageLayoutGate(compiler, runtimes);
      assertThat(gate.checkRules(c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8), spec, p.coreJson(),
          p.positionFreeSha256())).as(id).isEqualTo("L10b");
    }
  }

  /**
   * E4 钉住 L10b 用的编译入口：{@code Return none.} 在 {@code produce Int} 下，平台 {@link PolicyCompiler}（带类型检查）报错，
   * 但 CLI {@code verify} 的规范编译能编译且 IR 不同。闸门若拿平台编译器判「编译失败」就会放行，签出 CLI 拒绝的包。
   */
  @Test
  void l10bUsesTheCliCanonicalCompileNotTheTypeCheckingCompiler() throws Exception {
    JsonNode c = vectorCase("E4");
    PackageExportService.ExportSpec spec = spec(c);
    String stripped = spec.source().replace("Return `none`.", "Return none.");
    assertThat(stripped).isNotEqualTo(spec.source());
    assertThat(compiles(compileLike(spec, stripped))).as("平台编译器（类型检查）拒绝该变体").isFalse();
    PackageLayoutGate gate = new PackageLayoutGate(compiler, runtimes);
    String variant = gate.variantPositionFreeSha256(stripped, PackageLayoutGate.CanonicalInputs.of(spec));
    assertThat(variant).as("规范编译能编译").isNotNull().isNotEqualTo(platform(spec).positionFreeSha256());
  }

  /** {@code C⁻(S)} 编译失败表示那种读法不是程序（修订 5 §2.3）：E1 / E20a 的每个变体都编译不了，排版照样随包。 */
  @Test
  void variantsThatDoNotCompileAreAccepted() throws Exception {
    for (String id : List.of("E1", "E20a")) {
      JsonNode c = vectorCase(id);
      PackageExportService.ExportSpec spec = spec(c);
      Platform p = platform(spec);
      PackageLayoutGate gate = new PackageLayoutGate(compiler, runtimes);
      for (JsonNode stripped : c.path("stripped")) {
        assertThat(gate.variantPositionFreeSha256(stripped.asText(), PackageLayoutGate.CanonicalInputs.of(spec)))
            .as("%s 的变体应编译失败：%s", id, stripped.asText()).isNull();
      }
      PackageLayoutGate.LayoutOutcome o = new PackageLayoutGate(compiler, runtimes)
          .evaluate(c.path("layoutText").textValue(), spec, p.coreJson(), p.positionFreeSha256());
      assertThat(o.status()).as(id).isEqualTo(PackageLayoutGate.INCLUDED);
    }
  }

  /** 编译 {@code C⁻(S)} 时抛出的意外异常同样失败封闭：省略排版，不让导出失败。 */
  @Test
  void aVerifierCrashOmitsTheLayout() throws Exception {
    JsonNode c = vectorCase("E3");
    PackageExportService.ExportSpec spec = spec(c);
    Platform p = platform(spec);
    PackageLayoutGate crashing = new PackageLayoutGate(compiler, runtimes) {
      @Override
      String variantPositionFreeSha256(String source, CanonicalInputs in) {
        throw new IllegalStateException("boom");
      }
    };
    PackageLayoutGate.LayoutOutcome o = crashing.evaluate(c.path("layoutText").textValue(), spec, p.coreJson(), p.positionFreeSha256());
    assertThat(o.status()).isEqualTo(PackageLayoutGate.OMITTED);
    assertThat(o.reason()).isEqualTo(PackageLayoutGate.REASON_RULES_FAILED);
  }

  /** 没有转义三元组的排版行为不变：《静夜思》仍随包，闸门只编译 L9 的那一次。 */
  @Test
  void aTripleFreeLayoutCompilesExactlyOnce() throws Exception {
    PackageExportService.ExportSpec spec = PackageLayoutGateTest.jingyesi("jre");
    Platform p = platform(spec);
    assertThat(p.positionFreeSha256()).isEqualTo(JingyesiPolicy.POSITION_FREE_SHA256);
    CountingCompiler counting = new CountingCompiler(compiler);
    CountingGate gate = new CountingGate(counting, runtimes);
    PackageLayoutGate.LayoutOutcome o = gate.evaluate(JingyesiPolicy.LAYOUT_JSON, spec, p.coreJson(), p.positionFreeSha256());
    assertThat(o.status()).isEqualTo(PackageLayoutGate.INCLUDED);
    assertThat(o.displayAster()).isEqualTo(JingyesiPolicy.DISPLAY.getBytes(StandardCharsets.UTF_8));
    assertThat(counting.calls.get()).isEqualTo(1);
    assertThat(gate.variants.get()).isZero();
  }

  /** 上限 4 个三元组：15 个非空子集全部编译（另有 L9 一次），全是同一 IR 时随包。 */
  @Test
  void fourTriplesCompileFifteenVariants() throws Exception {
    String layout = "{\"v\":1,\"spans\":[{\"text\":\"Module probe.sample.\\n\\nRule f given \"}," + escaped("count")
        + ",{\"text\":\" as Int, produce Int:\\n  Return \"}," + escaped("count")
        + ",{\"text\":\".\\n\\nRule g given \"}," + escaped("total")
        + ",{\"text\":\" as Int, produce Int:\\n  Return \"}," + escaped("total") + ",{\"text\":\".\\n\"}]}";
    PackageExportService.ExportSpec spec = spec(
        LayoutFormat.parseStrict(layout.getBytes(StandardCharsets.UTF_8)).toCanonical(), "en-US", null, false, null);
    Platform p = platform(spec);
    CountingCompiler counting = new CountingCompiler(compiler);
    CountingGate gate = new CountingGate(counting, runtimes);
    PackageLayoutGate.LayoutOutcome o = gate.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256());
    assertThat(o.status()).isEqualTo(PackageLayoutGate.INCLUDED);
    assertThat(counting.calls.get()).isEqualTo(1);
    assertThat(gate.variants.get()).isEqualTo(15);
    System.out.printf("L10b cost (4 triples, 15 variants): %.1f ms median%n", medianMs(layout, spec, p));
  }

  /** 实测 E19（诗句排版，1 个三元组）整次闸门耗时，目标 JVM 每次保存 < 500 ms（Wave D 计划 L10b 成本）。 */
  @Test
  void e19PoemCost() throws Exception {
    JsonNode c = vectorCase("E19");
    PackageExportService.ExportSpec spec = spec(c);
    Platform p = platform(spec);
    double ms = medianMs(c.path("layoutText").textValue(), spec, p);
    System.out.printf("L10b cost (E19 poem, gate total): %.1f ms median%n", ms);
    assertThat(ms).isLessThan(500);
  }

  private double medianMs(String layout, PackageExportService.ExportSpec spec, Platform p) {
    PackageLayoutGate gate = new PackageLayoutGate(compiler, runtimes);
    for (int i = 0; i < 5; i++) {
      gate.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256());
    }
    long[] ns = new long[21];
    for (int i = 0; i < ns.length; i++) {
      long t = System.nanoTime();
      assertThat(gate.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256()).isIncluded()).isTrue();
      ns[i] = System.nanoTime() - t;
    }
    Arrays.sort(ns);
    return ns[ns.length / 2] / 1e6;
  }

  private static String escaped(String name) {
    return "{\"canonical\":\"`\",\"display\":\"\"},{\"text\":\"" + name + "\"},{\"canonical\":\"`\",\"display\":\"\"}";
  }
}
