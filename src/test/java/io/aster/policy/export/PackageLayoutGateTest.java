package io.aster.policy.export;

import io.aster.policy.compiler.PolicyCompiler;
import io.aster.test.JingyesiPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名前的显示排版闸门（ADR 0059 修订 4 D11–D12）：无排版 → absent；《静夜思》排版 → included 且 display.aster 为原诗；
 * L1–L9 任一不过 → omitted / display_rules_failed；锁文件该 target 的 CLI 不认得 1.1 → omitted / cli_unsupported。
 */
@QuarkusTest
class PackageLayoutGateTest {
  @Inject PackageLayoutGate gate;
  @Inject PackageExportService service;
  @Inject PolicyCompiler compiler;
  @Inject ExportRuntimes runtimes;

  /** 与 cloud 同形的《静夜思》导出输入（源码、冻结 aliasSet、合并词汇、allowStructural）。 */
  static PackageExportService.ExportSpec jingyesi(String target) {
    return new PackageExportService.ExportSpec(JingyesiPolicy.SOURCE, JingyesiPolicy.LOCALE, JingyesiPolicy.ALIAS_SET, true,
        JingyesiPolicy.vocabulary(), JingyesiPolicy.ENTRY, target, "zh",
        new PackageAssembler.ClaimedRef("pol-jingyesi", 1, "APPROVED", "2026-11-20T00:00:00Z"), "tenant-layout-test");
  }

  private PackageLayoutGate.LayoutOutcome evaluate(PackageLayoutGate g, String layout, String target) {
    PackageExportService.ExportSpec spec = jingyesi(target);
    PackagePrepared p = service.prepare(spec);
    assertThat(p.exportable()).isTrue();
    assertThat(p.positionFreeSha256()).isEqualTo(JingyesiPolicy.POSITION_FREE_SHA256);
    return g.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256());
  }

  @Test
  void sharedLayoutConstantMatchesTheResourceAndTheConformanceGolden() throws Exception {
    byte[] constant = JingyesiPolicy.LAYOUT_JSON.getBytes(StandardCharsets.UTF_8);
    assertThat(Files.readAllBytes(Path.of("src/test/resources/export/jingyesi-layout.json"))).isEqualTo(constant);
    assertThat(Files.readAllBytes(EntrySchemaGeneratorTest.FIXTURES
        .resolve("display/packages/b-jingyesi/policy/display/layout.json"))).isEqualTo(constant);
    assertThat(Files.readAllBytes(EntrySchemaGeneratorTest.FIXTURES
        .resolve("display/packages/b-jingyesi/policy/display/display.aster")))
        .isEqualTo(JingyesiPolicy.DISPLAY.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void noLayoutIsAbsent() {
    PackageLayoutGate.LayoutOutcome o = evaluate(gate, null, "jre");
    assertThat(o.status()).isEqualTo("absent");
    assertThat(o.reason()).isNull();
    assertThat(o.layoutJson()).isNull();
    assertThat(o.auditValue()).isEqualTo("absent");
  }

  @Test
  void thePoemLayoutIsIncludedWithTheOriginalPoemAsDisplayText() {
    for (String target : PackageExportService.TARGETS) {
      PackageLayoutGate.LayoutOutcome o = evaluate(gate, JingyesiPolicy.LAYOUT_JSON, target);
      assertThat(o.status()).as(target).isEqualTo("included");
      assertThat(o.reason()).isNull();
      assertThat(o.layoutJson()).isEqualTo(JingyesiPolicy.LAYOUT_JSON.getBytes(StandardCharsets.UTF_8));
      assertThat(new String(o.displayAster(), StandardCharsets.UTF_8))
          .isEqualTo("床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。");
      assertThat(o.auditValue()).isEqualTo("included");
    }
  }

  @Test
  void anyRuleFailureOmitsTheLayoutWithoutThrowing() {
    List<String> bad = List.of(
        // L1：不是严格 JSON / 键序不规范 / 相邻内容 span 未合并
        "not json",
        JingyesiPolicy.LAYOUT_JSON.replace("{\"v\":1,\"spans\":", "{\"spans\":").replace("]}", "],\"v\":1}"),
        JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"床前\"}", "{\"text\":\"床\"},{\"text\":\"前\"}"),
        // L3：往返不符（规范视图多一个字）
        JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"明月光\"}", "{\"text\":\"明月光光\"}"),
        // L7：结构 span 的 display 多出一个运算符
        JingyesiPolicy.LAYOUT_JSON.replace("{\"canonical\":\"，\",\"display\":\"。\\n\"}", "{\"canonical\":\"，\",\"display\":\"。+\\n\"}"),
        // L6：结构 span 藏了词汇触发词（字面量宏）
        JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"思故乡\"}", "{\"canonical\":\"思故乡\",\"display\":\"思故乡\"}"),
        // L8：入口函数名 地上霜 整个落在结构 span 里（IR 的 name）
        JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"地上霜\"}", "{\"canonical\":\"地上霜\",\"display\":\"地上霜\"}"),
        // L9：名字 明月光 被切进结构 span（补空格后模块名变了）
        JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"明月光\"}", "{\"text\":\"明月\"},{\"canonical\":\"光\",\"display\":\"光\"}"));
    for (String layout : bad) {
      PackageLayoutGate.LayoutOutcome o = evaluate(gate, layout, "jre");
      assertThat(o.status()).as(layout).isEqualTo("omitted");
      assertThat(o.reason()).as(layout).isEqualTo("display_rules_failed");
      assertThat(o.layoutJson()).isNull();
      assertThat(o.auditValue()).isEqualTo("omitted:display_rules_failed");
    }
  }

  @Test
  void aCliThatDoesNotKnowFormat11OmitsTheLayout() {
    ExportRuntimes legacy = new ExportRuntimes() {
      @Override
      public Optional<Target> target(String target) {
        return runtimes.target(target).map(t -> new Target(t.cliName(), t.cliVersion(), t.trustAnchorKids()));
      }
    };
    PackageLayoutGate.LayoutOutcome o = evaluate(new PackageLayoutGate(compiler, legacy), JingyesiPolicy.LAYOUT_JSON, "node");
    assertThat(o.status()).isEqualTo("omitted");
    assertThat(o.reason()).isEqualTo("cli_unsupported");
    // 规则先于锁文件：排版本身不合规时报规则失败
    PackageLayoutGate.LayoutOutcome both = evaluate(new PackageLayoutGate(compiler, legacy), "not json", "node");
    assertThat(both.reason()).isEqualTo("display_rules_failed");
  }
}
