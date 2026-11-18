package io.aster.policy.rest;

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

/** on-prem：未配置密钥且 allow-unsigned=true → 无签名包（无 manifest.sig.json，X-Aster-Package-Signed: false），照常审计。 */
@QuarkusTest
@TestProfile(PackageExportTestSupport.UnsignedProfile.class)
class PackageExportUnsignedTest {
  @Test
  void unsignedPackageHasNoSignatureFile() throws Exception {
    String tenant = "tenant-pkg-unsigned";
    Response r = hmacPost(PATH, request("jre", SOURCE), tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.header("X-Aster-Package-Signed")).isEqualTo("false");
    assertThat(PackageExportDownloadTest.unzip(r.asByteArray())).doesNotContainKey("manifest.sig.json")
        .containsKey("manifest.json");
    assertThat(exportEvents(tenant)).isEqualTo(1);
  }
}
