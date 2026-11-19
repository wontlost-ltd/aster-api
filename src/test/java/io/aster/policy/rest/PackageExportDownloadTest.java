package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.export.PackageAssembler;
import io.aster.policy.export.PackageExportService;
import io.aster.policy.export.PackagePrepared;
import io.aster.policy.export.ProbeRunner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.aster.policy.rest.PackageExportTestSupport.JSON;
import static io.aster.policy.rest.PackageExportTestSupport.PATH;
import static io.aster.policy.rest.PackageExportTestSupport.SOURCE;
import static io.aster.policy.rest.PackageExportTestSupport.exportEvent;
import static io.aster.policy.rest.PackageExportTestSupport.exportEvents;
import static io.aster.policy.rest.PackageExportTestSupport.hmacPost;
import static io.aster.policy.rest.PackageExportTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** {@code POST /export/package}（ADR 0059 P2 T13）：zip 响应、审计、nodeProbes 核对与入链失败。 */
@QuarkusTest
@TestProfile(CedarExportResourceTest.AuthProfile.class)
class PackageExportDownloadTest {
  @InjectSpy AuditChainAppender appender;

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
  void jreDownloadHeadersZipAndAudit() throws Exception {
    String tenant = "tenant-pkg-dl-jre";
    Response r = hmacPost(PATH, request("jre", SOURCE), tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.contentType()).startsWith("application/zip");
    byte[] zip = r.asByteArray();
    assertThat(r.header("X-Aster-Package-Sha256")).isEqualTo(sha(zip));
    assertThat(r.header("X-Aster-Package-Signed")).isEqualTo("true");
    assertThat(r.header("Cache-Control")).isEqualTo("private, no-store");
    assertThat(r.header("Content-Disposition")).isEqualTo(
        "attachment; filename=\"aster.pilot.credit-v3-jre-b.zip\"; filename*=UTF-8''aster.pilot.credit-v3-jre-b.zip");
    Map<String, byte[]> files = unzip(zip);
    assertThat(files).containsKeys("manifest.json", "manifest.sig.json", "SHA256SUMS", "tests/probes.json");
    JsonNode manifest = JSON.readTree(files.get("manifest.json"));
    assertThat(manifest.at("/tests/nodeCheck").isNull()).isTrue();
    assertThat(manifest.at("/exportedBy/tenantId").asText()).isEqualTo(tenant);

    AuditLog row = exportEvent(tenant);
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = JSON.readValue(row.metadata, Map.class);
    // 追加器不写值为 null 的键：jre 包的 nodeCheck 因此缺省（node 包见 nodeWithMatchingProbesSucceedsWithNodeCheck）
    assertThat(meta.keySet()).containsExactlyInAnyOrder("target", "manifestSha256", "zipSha256", "coreIrSha256",
        "positionFreeSha256", "cliVersion", "probeCount", "kid", "claimedRef", "sourceHash", "scopesHash");
    assertThat(meta.get("target")).isEqualTo("package:jre-b");
    assertThat(meta.get("zipSha256")).isEqualTo(sha(zip));
    assertThat(meta.get("manifestSha256")).isEqualTo(sha(files.get("manifest.json")));
    assertThat(meta.get("kid")).isEqualTo("test-only-export");
    assertThat(JSON.readTree((String) meta.get("claimedRef")).get("version").asInt()).isEqualTo(3);
    assertThat(row.policyModule).isEqualTo("aster.pilot.credit");
    assertThat(row.policyFunction).isEqualTo("decide");
  }

  private static Map<String, Object> nodeProbes(JsonNode prepared, Consumer<Map<String, Object>> mutate) {
    Map<String, Object> n = new LinkedHashMap<>();
    n.put("probesSha256", prepared.get("probesSha256").asText());
    n.put("count", prepared.get("probeCount").asInt());
    n.put("agreed", prepared.get("probeCount").asInt());
    n.put("tsVersion", prepared.at("/runtime/cliVersion").asText());
    mutate.accept(n);
    return n;
  }

  @Test
  void nodeWithMatchingProbesSucceedsWithNodeCheck() throws Exception {
    String tenant = "tenant-pkg-dl-node";
    JsonNode prepared = JSON.readTree(hmacPost(PATH + "/prepare", request("node", SOURCE), tenant).asString());
    Map<String, Object> req = request("node", SOURCE);
    req.put("nodeProbes", nodeProbes(prepared, n -> { }));
    Response r = hmacPost(PATH, req, tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    JsonNode manifest = JSON.readTree(unzip(r.asByteArray()).get("manifest.json"));
    assertThat(manifest.at("/target").asText()).isEqualTo("node");
    assertThat(manifest.at("/tests/nodeCheck/agreed").asInt()).isEqualTo(prepared.get("probeCount").asInt());
    assertThat(manifest.at("/tests/nodeCheck/checkedBy").asText()).isEqualTo("aster-cloud");
    assertThat(manifest.at("/tests/nodeCheck/tsVersion").asText()).isEqualTo("3.5.0");
    assertThat(manifest.at("/runtime/cli/name").asText()).isEqualTo("@aster-cloud/aster-lang-ts");
    assertThat(JSON.readTree((String) JSON.readValue(exportEvent(tenant).metadata, Map.class).get("nodeCheck"))
        .get("tsVersion").asText()).isEqualTo("3.5.0");
  }

  @Test
  void forgedNodeProbesAreRejectedWithoutAudit() throws Exception {
    String tenant = "tenant-pkg-dl-forged";
    JsonNode prepared = JSON.readTree(hmacPost(PATH + "/prepare", request("node", SOURCE), tenant).asString());
    Map<String, Consumer<Map<String, Object>>> forgeries = new LinkedHashMap<>();
    forgeries.put("node_probes_mismatch", n -> n.put("probesSha256", "0".repeat(64)));
    forgeries.put("node_check_incomplete", n -> n.put("agreed", (Integer) n.get("count") - 1));
    forgeries.put("node_ts_version_mismatch", n -> n.put("tsVersion", "3.2.0"));
    for (Map.Entry<String, Consumer<Map<String, Object>>> f : forgeries.entrySet()) {
      Map<String, Object> req = request("node", SOURCE);
      req.put("nodeProbes", nodeProbes(prepared, f.getValue()));
      Response r = hmacPost(PATH, req, tenant);
      assertThat(r.statusCode()).as(f.getKey() + " " + r.asString()).isEqualTo(400);
      assertThat(r.jsonPath().getString("error")).isEqualTo(f.getKey());
    }
    Map<String, Object> jre = request("jre", SOURCE);
    jre.put("nodeProbes", nodeProbes(prepared, n -> { }));
    Response r = hmacPost(PATH, jre, tenant);
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo("node_probes_unexpected");
    Response missing = hmacPost(PATH, request("node", SOURCE), tenant);
    assertThat(missing.jsonPath().getString("error")).isEqualTo("node_check_incomplete");
    assertThat(exportEvents(tenant)).isZero();
  }

  @Test
  void notExportablePolicyIs200WithDiagnostics() {
    String io = String.join("\n", "Module t.io.", "", "Rule fetch given x as Int, produce Int. It performs io:", "  Return x.");
    String tenant = "tenant-pkg-dl-io";
    Response r = hmacPost(PATH, request("jre", io), tenant);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getBoolean("exportable")).isFalse();
    assertThat(r.jsonPath().getList("diagnostics.code", String.class)).contains("E730");
    assertThat(exportEvents(tenant)).isZero();
  }

  @Test
  void auditFailureFailsTheRequestWithoutZip() {
    doThrow(new AuditChainAppender.AuditAppendException("boom", null)).when(appender).appendSync(any());
    Response r = hmacPost(PATH, request("jre", SOURCE), "tenant-pkg-dl-auditfail");
    assertThat(r.statusCode()).isEqualTo(500);
    assertThat(r.jsonPath().getString("error")).isEqualTo("audit_unavailable");
    assertThat(r.contentType()).startsWith("application/json");
  }

  /** 最坏情况的审计元数据（claimedRef 各段取上限 128 字符）仍在追加器 4096 字符上限内，不会被占位截断。 */
  @Test
  void worstCaseAuditMetadataFits() throws Exception {
    String longId = "p".repeat(128);
    PackageExportService.ExportSpec spec = new PackageExportService.ExportSpec(SOURCE, "en-US", null, false, null, null,
        "node", "en", new PackageAssembler.ClaimedRef(longId, Integer.MAX_VALUE, "s".repeat(128),
        "2026-11-01T00:00:00.123456789+13:45"), "t".repeat(64));
    PackagePrepared p = new PackagePrepared(true, List.of(), "decide", "{}", "a".repeat(64), "b".repeat(64),
        new ProbeRunner.ProbesFile(new byte[0], 256, "c".repeat(64)),
        new PackagePrepared.Runtime("@aster-cloud/aster-lang-ts", "9".repeat(64), "1.0.49"));
    PackageAssembler.AssembledPackage pkg = new PackageAssembler.AssembledPackage(new byte[0], "d".repeat(64),
        "e".repeat(64), "m-v1-node-b", "k".repeat(64));
    Map<String, Object> m = PackageExportResource.auditMetadata(spec, p, pkg,
        new PackageAssembler.NodeCheck("9".repeat(64), 256));
    assertThat(JSON.writeValueAsString(m).length()).isLessThan(AuditChainAppender.MAX_METADATA_SIZE);
  }

  @Test
  void contentDispositionHasAsciiFallbackAndUtf8Name() {
    assertThat(PackageExportResource.contentDisposition("块.遮蔽-v1-node-b")).isEqualTo(
        "attachment; filename=\"_.__-v1-node-b.zip\"; filename*=UTF-8''%E5%9D%97.%E9%81%AE%E8%94%BD-v1-node-b.zip");
  }
}
