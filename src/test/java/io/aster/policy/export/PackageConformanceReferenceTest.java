package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.signing.Jwks;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 平台导出的包必须被 conformance package-v1 的参照实现接受（ADR 0059 P2 T11，Review Focus 2）。兄弟检出
 * {@code ASTER_LANG_TEST_DIR}（缺省 {@code ../aster-lang-test}）存在且有 node 时实际执行；CI 设
 * {@code ASTER_EXPORT_REFERENCE_REQUIRE=1}，缺失即失败而不是跳过。
 */
@QuarkusTest
class PackageConformanceReferenceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PackageExportService service;
  @Inject ExportSigningKeys keys;

  @TempDir Path tmp;

  @Test
  void exportedPackagesPassTheReferenceCheck() throws Exception {
    Path testDir = Path.of(System.getenv().getOrDefault("ASTER_LANG_TEST_DIR", "../aster-lang-test"));
    boolean required = "1".equals(System.getenv("ASTER_EXPORT_REFERENCE_REQUIRE"));
    boolean present = Files.isRegularFile(testDir.resolve("scripts/lib/package-v1/reference-check.mjs")) && nodeAvailable();
    if (required) {
      assertThat(present).as("CI 要求参照检查实际执行：" + testDir.toAbsolutePath() + " 与 node 必须可用").isTrue();
    }
    assumeTrue(present, "兄弟检出 aster-lang-test 或 node 不可用，跳过参照检查");

    var ring = keys.ring().orElseThrow();
    Path jwks = tmp.resolve("jwks.json");
    Files.writeString(jwks, JSON.writeValueAsString(Jwks.render(ring.all(), ring.revision(), Instant.now())));
    Path driver = Path.of("src/test/resources/export/reference-verify.mjs").toAbsolutePath();
    int executed = 0;
    for (String pkg : PackageExportServiceTest.GOLDEN_B) {
      PackageAssembler.AssembledPackage p = service.export(PackageExportServiceTest.spec(pkg), null);
      Path zip = tmp.resolve(p.rootName() + ".zip");
      Files.write(zip, p.zip());
      Process proc = new ProcessBuilder("node", driver.toString(), testDir.toAbsolutePath().toString(),
          zip.toString(), jwks.toString()).redirectErrorStream(true).start();
      assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
      String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode result = JSON.readTree(out);
      assertThat(result.get("ok").asBoolean()).as(pkg + " → " + out).isTrue();
      executed++;
    }
    assertThat(executed).isEqualTo(PackageExportServiceTest.GOLDEN_B.size());

    // ADR 0059 修订 4：带排版的 1.1 包另过参照实现的显示排版 L1–L7（与 core 闸门、两个 CLI 同一份规则的第三个实现）
    for (String target : PackageExportService.TARGETS) {
      PackageExportService.ExportSpec spec = PackageLayoutGateTest.jingyesi(target);
      PackageAssembler.NodeCheck nodeCheck = null;
      if ("node".equals(target)) {
        PackagePrepared prepared = service.prepare(spec);
        nodeCheck = new PackageAssembler.NodeCheck(prepared.runtime().cliVersion(), prepared.probes().count());
      }
      PackageAssembler.AssembledPackage p = service.export(spec, nodeCheck, io.aster.test.JingyesiPolicy.LAYOUT_JSON);
      assertThat(p.layout().status()).isEqualTo("included");
      Path zip = tmp.resolve(p.rootName() + ".zip");
      Files.write(zip, p.zip());
      Process proc = new ProcessBuilder("node", driver.toString(), testDir.toAbsolutePath().toString(),
          zip.toString(), jwks.toString()).redirectErrorStream(true).start();
      assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
      String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode result = JSON.readTree(out);
      assertThat(result.get("ok").asBoolean()).as("jingyesi " + target + " → " + out).isTrue();
      assertThat(result.get("display").asBoolean()).as("参照实现实际检查了显示排版").isTrue();
    }
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
