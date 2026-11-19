package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.receipt.JcsCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** manifest / SHA256SUMS / zip 组装：schema、规范字节、可选文件、哈希敏感性与 1 MiB 上限。 */
class PackageAssemblerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final PackageAssembler UNSIGNED = new PackageAssembler((m, t) -> new PackageSigner.Unsigned());

  static PackageAssembler.ManifestFields fields(int version, String toolchain, PackageAssembler.NodeCheck nodeCheck,
                                                String cliVersion) {
    return new PackageAssembler.ManifestFields("a".repeat(64), "b".repeat(64), toolchain, List.of(), "tenant-x",
        List.of(), true, List.of("EU_AI_ACT:ART14"), "decide", null, "CP-DECIDE", "aster-lang-truffle",
        "cloud.aster-lang:aster-cli", cliVersion, Map.of("java", ">=25"), "1.0.49", false,
        new PackageAssembler.ClaimedRef("pol-1", version, "APPROVED", "2026-11-01T00:00:00Z"), "c".repeat(64),
        "en-US", nodeCheck);
  }

  static PackageAssembler.PackageInputs inputs(PackageAssembler.ManifestFields f, String target, byte[] source,
                                               String vocabulary) throws Exception {
    String pkg = "b-credit-limit";
    EntrySchemaGenerator.EntrySchemas schemas = EntrySchemaGenerator.generate(EntrySchemaGeneratorTest.platformModule(pkg), "decide");
    byte[] probes = Files.readAllBytes(EntrySchemaGeneratorTest.FIXTURES.resolve("packages/" + pkg + "/tests/probes.json"));
    ProbeRunner.ProbesFile pf = new ProbeRunner.ProbesFile(probes, 2, PackageAssembler.sha256(probes));
    return new PackageAssembler.PackageInputs("aster.pilot.credit", target, "en", source, "null", vocabulary, schemas,
        pf, f, "1.0.49", null);
  }

  private static byte[] source() throws Exception {
    return Files.readAllBytes(EntrySchemaGeneratorTest.FIXTURES.resolve("src/b-credit-limit/policy.aster"));
  }

  static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    Map<String, byte[]> out = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        out.put(e.getName().substring(e.getName().indexOf('/') + 1), in.readAllBytes());
      }
    }
    return out;
  }

  private static String sha(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  @Test
  void manifestIsSchemaValidCanonicalAndConsistent() throws Exception {
    PackageAssembler.AssembledPackage p = UNSIGNED.assemble(inputs(fields(3, "tc", null, "1.0.49"), "jre", source(), null));
    assertThat(p.rootName()).isEqualTo("aster.pilot.credit-v3-jre-b");
    assertThat(p.kid()).isNull();
    Map<String, byte[]> files = unzip(p.zip());
    assertThat(files.keySet()).containsExactly("README.md", "SHA256SUMS", "manifest.json", "policy/compile/alias-set.json",
        "policy/policy.aster", "schema/input.json", "schema/output.json", "tests/probes.json");
    byte[] manifest = files.get("manifest.json");
    JsonNode m = JSON.readTree(manifest);
    assertThat(JsonSchemas.errors(EntrySchemaGeneratorTest.FIXTURES.resolve("schema/manifest-1.0.schema.json"), m)).isEmpty();
    assertThat(JcsCanonicalizer.canonicalize(m)).isEqualTo(manifest);
    assertThat(p.manifestSha256()).isEqualTo(sha(manifest));
    assertThat(p.zipSha256()).isEqualTo(sha(p.zip()));
    assertThat(new String(files.get("policy/compile/alias-set.json"), StandardCharsets.UTF_8)).isEqualTo("null");
    assertThat(m.at("/source/sha256").asText()).isEqualTo(sha(files.get("policy/policy.aster")));
    assertThat(m.at("/tests/probesSha256").asText()).isEqualTo(sha(files.get("tests/probes.json")));
    assertThat(m.at("/tests/probes").asInt()).isEqualTo(2);
    StringBuilder sums = new StringBuilder();
    for (String path : List.of("README.md", "manifest.json", "policy/compile/alias-set.json", "policy/policy.aster",
        "schema/input.json", "schema/output.json", "tests/probes.json")) {
      sums.append(sha(files.get(path))).append("  ").append(path).append('\n');
    }
    assertThat(new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8)).isEqualTo(sums.toString());
    for (JsonNode f : m.get("files")) {
      assertThat(f.get("sha256").asText()).isEqualTo(sha(files.get(f.get("path").asText())));
    }
  }

  @Test
  void vocabularyFileOnlyWhenPresentAndSignatureFileWhenSigned() throws Exception {
    PackageAssembler signed = new PackageAssembler((m, t) -> new PackageSigner.Signed("{\"sig\":1}".getBytes(), "k1"));
    PackageAssembler.AssembledPackage p = signed.assemble(inputs(fields(3, "tc", null, "1.0.49"), "jre", source(), "{\"a\":1}"));
    Map<String, byte[]> files = unzip(p.zip());
    assertThat(files).containsKeys("policy/compile/vocabulary.json", "manifest.sig.json");
    assertThat(new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8)).doesNotContain("manifest.sig.json");
    assertThat(p.kid()).isEqualTo("k1");
  }

  @Test
  void refusedSignatureFailsBeforeZip() {
    PackageAssembler refused = new PackageAssembler((m, t) -> new PackageSigner.Refused("export_signing_kid_unanchored"));
    assertThatThrownBy(() -> refused.assemble(inputs(fields(3, "tc", null, "1.0.49"), "jre", source(), null)))
        .isInstanceOf(PackageExportUnavailableException.class).hasMessage("export_signing_kid_unanchored");
  }

  @Test
  void sameInputSameBytesAndEveryTupleMemberChangesTheManifestHash() throws Exception {
    PackageAssembler.PackageInputs base = inputs(fields(3, "tc", null, "1.0.49"), "jre", source(), null);
    assertThat(UNSIGNED.assemble(base).zip()).isEqualTo(UNSIGNED.assemble(base).zip());
    String h = UNSIGNED.assemble(base).manifestSha256();
    List<UnaryOperator<PackageAssembler.PackageInputs>> changes = List.of(
        in -> withFields(in, fields(4, "tc", null, "1.0.49")),
        in -> withFields(in, fields(3, "tc2", null, "1.0.49")),
        in -> withFields(in, fields(3, "tc", new PackageAssembler.NodeCheck("3.3.0", 2), "1.0.49")),
        in -> withFields(in, fields(3, "tc", null, "1.0.50")),
        in -> new PackageAssembler.PackageInputs(in.moduleName(), "node", in.uiLocale(), in.source(), in.aliasSetCanonicalJson(),
            in.vocabularyCanonicalJson(), in.schemas(), in.probes(), in.fields(), in.jreCliVersion(), in.tsCliVersion()),
        in -> new PackageAssembler.PackageInputs(in.moduleName(), in.target(), in.uiLocale(), in.source(), in.aliasSetCanonicalJson(),
            in.vocabularyCanonicalJson(), in.schemas(),
            new ProbeRunner.ProbesFile("{\"probes\":[]}".getBytes(), 0, "0".repeat(64)), in.fields(), in.jreCliVersion(),
            in.tsCliVersion()),
        in -> new PackageAssembler.PackageInputs(in.moduleName(), in.target(), in.uiLocale(),
            (new String(in.source(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8),
            in.aliasSetCanonicalJson(), in.vocabularyCanonicalJson(), in.schemas(), in.probes(), in.fields(),
            in.jreCliVersion(), in.tsCliVersion()));
    for (UnaryOperator<PackageAssembler.PackageInputs> change : changes) {
      assertThat(UNSIGNED.assemble(change.apply(base)).manifestSha256()).isNotEqualTo(h);
    }
  }

  private static PackageAssembler.PackageInputs withFields(PackageAssembler.PackageInputs in, PackageAssembler.ManifestFields f) {
    return new PackageAssembler.PackageInputs(in.moduleName(), in.target(), in.uiLocale(), in.source(), in.aliasSetCanonicalJson(),
        in.vocabularyCanonicalJson(), in.schemas(), in.probes(), f, in.jreCliVersion(), in.tsCliVersion());
  }

  @Test
  void oversizeIsRejected() throws Exception {
    // 不可压缩的 1 MiB + 1 字节源码：deflate 后仍超过上限
    byte[] random = new byte[PackageAssembler.MAX_ZIP_BYTES + 1];
    new java.util.Random(7).nextBytes(random);
    assertThatThrownBy(() -> UNSIGNED.assemble(inputs(fields(3, "tc", null, "1.0.49"), "jre", random, null)))
        .isInstanceOfSatisfying(PackageTooLargeException.class, e -> {
          assertThat(e.size()).isGreaterThan(PackageAssembler.MAX_ZIP_BYTES);
          assertThat(e.limit()).isEqualTo(1_048_576);
        });
  }

  @Test
  void readmeFallsBackToEnglishAndListsFactSources() {
    PackageReadme.Data d = new PackageReadme.Data("m", 2, "jre", "f", "cloud.aster-lang:aster-cli", "1.0.49", "1.0.49", null,
        List.of("bureau"));
    String hi = new String(PackageReadme.render("hi", d), StandardCharsets.UTF_8);
    assertThat(hi).isEqualTo(new String(PackageReadme.render("en", d), StandardCharsets.UTF_8))
        .contains("aster-cli-1.0.49.jar", "https://github.com/aster-cloud/aster-lang-ts/releases/latest", "node aster.mjs",
            "bureau", "not** a Guard execution point")
        .doesNotContain("npx", "npm i -g")
        .doesNotContain("{{");
    assertThat(new String(PackageReadme.render("zh", d), StandardCharsets.UTF_8)).contains("事实来源：bureau。");
    PackageReadme.Data pinned = new PackageReadme.Data("m", 2, "node", "f", "@aster-cloud/aster-lang-ts", "3.3.0", "1.0.50",
        "3.3.0", List.of());
    for (String locale : List.of("en", "zh", "de")) {
      assertThat(new String(PackageReadme.render(locale, pinned), StandardCharsets.UTF_8)).as(locale)
          .contains("https://github.com/aster-cloud/aster-lang-ts/releases/tag/v3.3.0", "aster.mjs.sha256", "node aster.mjs")
          .doesNotContain("npx", "npm i -g", "{{");
    }
  }
}
