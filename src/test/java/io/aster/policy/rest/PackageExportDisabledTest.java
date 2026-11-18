package io.aster.policy.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.aster.policy.rest.PackageExportTestSupport.PATH;
import static io.aster.policy.rest.PackageExportTestSupport.SOURCE;
import static io.aster.policy.rest.PackageExportTestSupport.hmacPost;
import static io.aster.policy.rest.PackageExportTestSupport.request;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 开关关闭：三个包端点 404（先于鉴权判定）；JWKS 照常可读。 */
@QuarkusTest
@TestProfile(PackageExportTestSupport.DisabledProfile.class)
class PackageExportDisabledTest {
  @Test
  void endpointsAre404AndJwksStillServes() {
    for (String sub : new String[] {"/check", "/prepare", ""}) {
      Response r = hmacPost(PATH + sub, request("jre", SOURCE), "tenant-pkg-off");
      assertThat(r.statusCode()).as(sub).isEqualTo(404);
      Response anonymous = given().header("X-Tenant-Id", "tenant-pkg-off").contentType("application/json")
          .body("{}").post(PATH + sub);
      assertThat(anonymous.statusCode()).as(sub + " 非 HMAC").isEqualTo(404);
    }
    assertThat(given().get(ExportKeysResource.PATH).statusCode()).isEqualTo(200);
  }
}
