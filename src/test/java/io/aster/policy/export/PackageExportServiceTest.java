package io.aster.policy.export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导出服务（ADR 0059 P2 T11）：对 conformance 黄金包的源码与编译输入导出 jre 包，schema 与黄金包逐字节相同、
 * positionFreeSha256 与 source.sha256 等于黄金 manifest、探针期望可重算；同一输入两次导出逐字节相同；
 * IO 与多入口的拒绝路径不产出 zip。
 */
@QuarkusTest
class PackageExportServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  static final List<String> GOLDEN_B = List.of("b-credit-limit", "b-vocab-de", "b-zh", "b-alias");

  @Inject PackageExportService service;

  /** 由黄金包的 fixture.json 构造与其编译输入相同的导出请求（入口取 default:true，target 固定 jre）。 */
  static PackageExportService.ExportSpec spec(String pkg) throws Exception {
    JsonNode fixture = JSON.readTree(EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/fixture.json").toFile());
    Map<String, List<String>> aliasSet = fixture.get("aliasSet").isNull() ? null
        : JSON.convertValue(fixture.get("aliasSet"), new TypeReference<Map<String, List<String>>>() { });
    Map<String, Object> vocabulary = fixture.path("vocabulary").asBoolean(false)
        ? JSON.readValue(EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/vocabulary.json").toFile(),
            new TypeReference<Map<String, Object>>() { })
        : null;
    String entry = null;
    for (JsonNode e : fixture.get("entries")) {
      if (e.path("default").asBoolean()) {
        entry = e.get("function").asText();
      }
    }
    String source = Files.readString(EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/policy.aster"));
    return new PackageExportService.ExportSpec(source, fixture.get("locale").asText(), aliasSet,
        fixture.path("allowStructural").asBoolean(false), vocabulary, entry, "jre", "en",
        new PackageAssembler.ClaimedRef("pol-" + pkg, fixture.get("claimedRefVersion").asInt(), "APPROVED",
            "2026-11-01T00:00:00Z"), "tenant-export-test");
  }

  @Test
  void goldenSourcesExportWithIdenticalSchemasAndHashes() throws Exception {
    for (String pkg : GOLDEN_B) {
      PackageAssembler.AssembledPackage p = service.export(spec(pkg), null);
      Map<String, byte[]> files = PackageAssemblerTest.unzip(p.zip());
      JsonNode manifest = JSON.readTree(files.get("manifest.json"));
      JsonNode golden = JSON.readTree(EntrySchemaGeneratorTest.FIXTURES.resolve("packages/" + pkg + "/manifest.json").toFile());
      assertThat(manifest.at("/coreIr/positionFreeSha256")).as(pkg).isEqualTo(golden.at("/coreIr/positionFreeSha256"));
      assertThat(manifest.at("/source/sha256")).as(pkg).isEqualTo(golden.at("/source/sha256"));
      assertThat(manifest.at("/policy/moduleName")).as(pkg).isEqualTo(golden.at("/policy/moduleName"));
      assertThat(p.kid()).isEqualTo("test-only-export");
      assertThat(files).containsKey("manifest.sig.json");
      if (golden.at("/policy/entries").size() == 1) {
        for (String schema : List.of("schema/input.json", "schema/output.json")) {
          assertThat(new String(files.get(schema), StandardCharsets.UTF_8)).as(pkg + " " + schema)
              .isEqualTo(Files.readString(EntrySchemaGeneratorTest.FIXTURES.resolve("packages/" + pkg + "/" + schema)));
        }
      }
      for (String compileFile : List.of("policy/compile/alias-set.json", "policy/compile/vocabulary.json")) {
        java.nio.file.Path goldenFile = EntrySchemaGeneratorTest.FIXTURES.resolve("packages/" + pkg + "/" + compileFile);
        if (Files.exists(goldenFile)) {
          assertThat(files.get(compileFile)).as(pkg + " " + compileFile).isEqualTo(Files.readAllBytes(goldenFile));
        } else {
          assertThat(files).as(pkg).doesNotContainKey(compileFile);
        }
      }
      String core = new String(Files.readAllBytes(EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/platform-core.json")),
          StandardCharsets.UTF_8);
      JsonNode probes = JSON.readTree(files.get("tests/probes.json"));
      assertThat(probes.get("probes").size()).isBetween(1, 256).isEqualTo(manifest.at("/tests/probes").asInt());
      List<JsonNode> inputs = new ArrayList<>();
      probes.get("probes").forEach(pr -> inputs.add(pr.get("input")));
      byte[] recomputed = ProbeRunner.run(core, manifest.at("/policy/entries/0/function").asText(), inputs).json();
      assertThat(recomputed).as(pkg + " 探针期望在平台 IR 上重算一致").isEqualTo(files.get("tests/probes.json"));
    }
  }

  @Test
  void sameSpecTwiceGivesIdenticalZip() throws Exception {
    PackageExportService.ExportSpec s = spec("b-credit-limit");
    assertThat(service.export(s, null).zip()).isEqualTo(service.export(s, null).zip());
  }

  @Test
  void ioPolicyIsRejectedWithE730AndNoZip() {
    String src = String.join("\n", "Module t.io.", "", "Rule fetch given x as Int, produce Int. It performs io:",
        "  Return x.");
    PackageExportService.ExportSpec s = new PackageExportService.ExportSpec(src, "en-US", null, false, null, null, "jre",
        "en", new PackageAssembler.ClaimedRef("p", 1, "APPROVED", null), "t");
    PackageExportCheck check = service.check(s);
    assertThat(check.targets().get("jre").exportable()).isFalse();
    assertThat(check.targets().get("jre").diagnostics()).extracting(ExportDiagnostic::code).contains("E730");
    assertThat(service.prepare(s).exportable()).isFalse();
    assertThatThrownBy(() -> service.export(s, null)).isInstanceOf(ExportRejectedException.class);
  }

  @Test
  void multipleRulesWithoutEntryRequireAChoice() {
    String src = String.join("\n", "Module t.multi.", "", "Rule a given x as Int, produce Int:", "  Return x.", "",
        "Rule b given x as Int, produce Int:", "  Return x.");
    PackageExportService.ExportSpec s = new PackageExportService.ExportSpec(src, "en-US", null, false, null, null, "jre",
        "en", new PackageAssembler.ClaimedRef("p", 1, "APPROVED", null), "t");
    PackageExportCheck check = service.check(s);
    assertThat(check.defaultEntry()).isNull();
    assertThat(check.entryCandidates()).containsExactly("a", "b");
    assertThatThrownBy(() -> service.export(s, null)).isInstanceOf(EntryRequiredException.class);
  }

  @Test
  void nodeTargetUnavailableUntilBuiltinsShip() throws Exception {
    PackageExportCheck check = service.check(spec("b-credit-limit"));
    assertThat(check.targets().get("jre").exportable()).isTrue();
    assertThat(check.targets().get("node").unavailable()).isEqualTo("export_runtime_unavailable");
    PackageExportService.ExportSpec node = new PackageExportService.ExportSpec(spec("b-credit-limit").source(), "en-US",
        null, false, null, null, "node", "en", new PackageAssembler.ClaimedRef("p", 1, "APPROVED", null), "t");
    assertThatThrownBy(() -> service.prepare(node)).isInstanceOf(PackageExportUnavailableException.class)
        .hasMessage("export_runtime_unavailable");
  }
}
