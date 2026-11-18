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

/** 未配置导出密钥：503 {@code export_signing_unavailable}，组装前拒绝、不写审计。 */
@QuarkusTest
@TestProfile(PackageExportTestSupport.NoKeysProfile.class)
class PackageExportSigningProfilesTest {
  @Test
  void missingKeysRefuseBeforeAssembly() {
    String tenant = "tenant-pkg-nokeys";
    Response r = hmacPost(PATH, request("jre", SOURCE), tenant);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(503);
    assertThat(r.jsonPath().getString("error")).isEqualTo("export_signing_unavailable");
    assertThat(exportEvents(tenant)).isZero();
  }
}
