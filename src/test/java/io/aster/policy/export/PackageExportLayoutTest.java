package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.test.JingyesiPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带显示排版的导出（ADR 0059 修订 4 D1、D2、D12、D14）：《静夜思》带排版导出 jre 与 node 两种包——manifest 为 1.1、
 * {@code display:{"layoutVersion":1}}、{@code files[]} 含两个 {@code policy/display/*}；同一输入不带排版仍为 1.0，
 * 编译身份（{@code source.envelopeSha256}、{@code coreIr.*}）与其余载荷不变；带排版导出可复现。
 */
@QuarkusTest
class PackageExportLayoutTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> DISPLAY_FILES = List.of("policy/display/display.aster", "policy/display/layout.json");

  @Inject PackageExportService service;

  private PackageAssembler.AssembledPackage export(String target, String layout) {
    PackageExportService.ExportSpec spec = PackageLayoutGateTest.jingyesi(target);
    PackageAssembler.NodeCheck nodeCheck = null;
    if ("node".equals(target)) {
      PackagePrepared p = service.prepare(spec);
      nodeCheck = new PackageAssembler.NodeCheck(p.runtime().cliVersion(), p.probes().count());
    }
    return service.export(spec, nodeCheck, layout);
  }

  @Test
  void poemWithLayoutExportsFormat11ForBothTargets() throws Exception {
    for (String target : PackageExportService.TARGETS) {
      PackageAssembler.AssembledPackage pkg = export(target, JingyesiPolicy.LAYOUT_JSON);
      assertThat(pkg.layout().status()).as(target).isEqualTo("included");
      Map<String, byte[]> files = PackageAssemblerTest.unzip(pkg.zip());
      JsonNode manifest = JSON.readTree(files.get("manifest.json"));
      assertThat(manifest.get("formatVersion").asText()).as(target).isEqualTo("1.1");
      assertThat(manifest.get("display").toString()).isEqualTo("{\"layoutVersion\":1}");
      assertThat(paths(manifest)).containsAll(DISPLAY_FILES);
      assertThat(JsonSchemas.errors(EntrySchemaGeneratorTest.FIXTURES.resolve("schema/manifest-1.1.schema.json"), manifest))
          .as(target).isEmpty();
      assertThat(files.get("policy/display/layout.json")).isEqualTo(JingyesiPolicy.LAYOUT_JSON.getBytes(StandardCharsets.UTF_8));
      assertThat(new String(files.get("policy/display/display.aster"), StandardCharsets.UTF_8)).isEqualTo(JingyesiPolicy.DISPLAY);
      assertThat(manifest.at("/coreIr/positionFreeSha256").asText()).isEqualTo(JingyesiPolicy.POSITION_FREE_SHA256);
      // SHA256SUMS 覆盖两个排版文件（与其余载荷同一配方）
      String sums = new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8);
      for (String path : DISPLAY_FILES) {
        assertThat(sums).contains(PackageAssembler.sha256(files.get(path)) + "  " + path + "\n");
      }
      assertThat(new String(files.get("README.md"), StandardCharsets.UTF_8))
          .contains("## 显示排版", "aster show <package.zip>", "--display poem.txt", "PKG_UNSUPPORTED_FORMAT")
          .doesNotContain("{{");
    }
  }

  @Test
  void theSamePolicyWithoutLayoutStaysFormat10AndKeepsItsCompileIdentity() throws Exception {
    for (String target : PackageExportService.TARGETS) {
      PackageAssembler.AssembledPackage with = export(target, JingyesiPolicy.LAYOUT_JSON);
      PackageAssembler.AssembledPackage without = export(target, null);
      assertThat(without.layout().status()).isEqualTo("absent");
      Map<String, byte[]> a = PackageAssemblerTest.unzip(with.zip());
      Map<String, byte[]> b = PackageAssemblerTest.unzip(without.zip());
      JsonNode ma = JSON.readTree(a.get("manifest.json"));
      JsonNode mb = JSON.readTree(b.get("manifest.json"));
      assertThat(mb.get("formatVersion").asText()).isEqualTo("1.0");
      assertThat(mb.has("display")).isFalse();
      assertThat(b).doesNotContainKeys(DISPLAY_FILES.toArray(String[]::new));
      assertThat(JsonSchemas.errors(EntrySchemaGeneratorTest.FIXTURES.resolve("schema/manifest-1.0.schema.json"), mb)).isEmpty();
      assertThat(mb.at("/source/envelopeSha256")).isEqualTo(ma.at("/source/envelopeSha256"));
      assertThat(mb.get("coreIr")).isEqualTo(ma.get("coreIr"));
      assertThat(without.manifestSha256()).isNotEqualTo(with.manifestSha256());
      // 除排版文件与 README 的显示一节之外，两包的载荷逐字节相同；README 只多出末尾一节
      for (String path : b.keySet()) {
        if (!List.of("README.md", "manifest.json", "manifest.sig.json", "SHA256SUMS").contains(path)) {
          assertThat(a.get(path)).as(target + " " + path).isEqualTo(b.get(path));
        }
      }
      String readmeWith = new String(a.get("README.md"), StandardCharsets.UTF_8);
      String readmeWithout = new String(b.get("README.md"), StandardCharsets.UTF_8);
      assertThat(readmeWith).startsWith(readmeWithout + "\n## 显示排版");
      assertThat(readmeWithout).doesNotContain("display.aster");
      // manifest 去掉 display、两个排版文件与 README 的条目、改回 1.0 之后，与不带排版的 manifest 相同
      assertThat(withoutDisplay(ma)).isEqualTo(withoutDisplay(mb));
    }
  }

  @Test
  void layoutExportsAreReproducible() {
    for (String target : PackageExportService.TARGETS) {
      PackageAssembler.AssembledPackage first = export(target, JingyesiPolicy.LAYOUT_JSON);
      PackageAssembler.AssembledPackage second = export(target, JingyesiPolicy.LAYOUT_JSON);
      assertThat(first.zip()).as(target).isEqualTo(second.zip());
    }
  }

  @Test
  void anOmittedLayoutExportsTheSameBytesAsNoLayout() {
    String broken = JingyesiPolicy.LAYOUT_JSON.replace("{\"text\":\"明月光\"}", "{\"text\":\"明月光光\"}");
    PackageAssembler.AssembledPackage omitted = export("jre", broken);
    assertThat(omitted.layout().status()).isEqualTo("omitted");
    assertThat(omitted.layout().reason()).isEqualTo("display_rules_failed");
    assertThat(omitted.zip()).isEqualTo(export("jre", null).zip());
  }

  private static List<String> paths(JsonNode manifest) {
    return manifest.get("files").findValuesAsText("path");
  }

  /** manifest 去掉排版相关成员（display、两个排版文件、README 条目，formatVersion 改回 1.0）后的键序无关形态。 */
  private static Map<String, JsonNode> withoutDisplay(JsonNode manifest) {
    ObjectNode m = manifest.deepCopy();
    m.remove("display");
    m.put("formatVersion", "1.0");
    ArrayNode files = JSON.createArrayNode();
    for (JsonNode f : manifest.get("files")) {
      String path = f.get("path").asText();
      if (!DISPLAY_FILES.contains(path) && !"README.md".equals(path)) {
        files.add(f);
      }
    }
    m.set("files", files);
    Map<String, JsonNode> out = new TreeMap<>();
    m.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }
}
