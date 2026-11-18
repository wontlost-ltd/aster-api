package io.aster.policy.rest;

import io.aster.policy.export.PackageSigner;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.aster.policy.rest.PackageExportTestSupport.PATH;
import static io.aster.policy.rest.PackageExportTestSupport.SOURCE;
import static io.aster.policy.rest.PackageExportTestSupport.exportEvents;
import static io.aster.policy.rest.PackageExportTestSupport.hmacPost;
import static io.aster.policy.rest.PackageExportTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 签名结论到 HTTP 的映射（签名器本身的判定见 A2 {@code PackageSignerTest}，其中钉住了生产 kid {@code export-2026-11a} 对仓内锁文件
 * → {@code export_signing_kid_unanchored}）：拒绝在组装前 503、不写审计；on-prem 无签名包无 {@code manifest.sig.json}。
 */
@QuarkusTest
@TestProfile(CedarExportResourceTest.AuthProfile.class)
class PackageExportSigningTest {
  @InjectMock PackageSigner signer;

  private void assertRefused(String code, String tenant) {
    when(signer.sign(any(), any())).thenReturn(new PackageSigner.Refused(code));
    Response r = hmacPost(PATH, request("jre", SOURCE), tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(503);
    assertThat(r.jsonPath().getString("error")).isEqualTo(code);
    assertThat(r.contentType()).startsWith("application/json");
    assertThat(exportEvents(tenant)).isZero();
  }

  /** 首次上线形态：已配置的 active kid 不在锁文件该 target 的 trustAnchorKids 中。 */
  @Test
  void unanchoredKidIs503WithoutAudit() {
    assertRefused("export_signing_kid_unanchored", "tenant-pkg-unanchored");
  }

  @Test
  void missingKeysIs503WithoutAudit() {
    assertRefused("export_signing_unavailable", "tenant-pkg-nokeys");
  }

  @Test
  void unsignedPackageHasNoSignatureFile() throws Exception {
    when(signer.sign(any(), any())).thenReturn(new PackageSigner.Unsigned());
    String tenant = "tenant-pkg-unsigned";
    Response r = hmacPost(PATH, request("jre", SOURCE), tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.header("X-Aster-Package-Signed")).isEqualTo("false");
    assertThat(PackageExportDownloadTest.unzip(r.asByteArray())).doesNotContainKey("manifest.sig.json")
        .containsKey("manifest.json");
    assertThat(exportEvents(tenant)).isEqualTo(1);
  }
}
