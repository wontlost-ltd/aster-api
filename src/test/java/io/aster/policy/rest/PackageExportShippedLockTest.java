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

/**
 * 首次上线形态：仓内锁文件（jre 1.0.49、trustAnchorKids []、node null）+ 已配置密钥。jre 导出必须在组装 zip 之前
 * 503 {@code export_signing_kid_unanchored} 且不写审计；node 在 check 中为 {@code export_runtime_unavailable}、prepare 503。
 */
@QuarkusTest
@TestProfile(PackageExportTestSupport.ShippedLockProfile.class)
class PackageExportShippedLockTest {
  private static final String TENANT = "tenant-pkg-shipped-lock";

  @Test
  void jreIsUnanchoredAndNodeIsUnavailable() {
    Response check = hmacPost(PATH + "/check", request("jre", SOURCE), TENANT);
    assertThat(check.jsonPath().getBoolean("targets.jre.exportable")).isTrue();
    assertThat(check.jsonPath().getString("targets.node.unavailable")).isEqualTo("export_runtime_unavailable");

    Response jre = hmacPost(PATH, request("jre", SOURCE), TENANT);
    assertThat(jre.statusCode()).as(jre.asString()).isEqualTo(503);
    assertThat(jre.jsonPath().getString("error")).isEqualTo("export_signing_kid_unanchored");
    assertThat(jre.contentType()).startsWith("application/json");

    Response node = hmacPost(PATH + "/prepare", request("node", SOURCE), TENANT);
    assertThat(node.statusCode()).isEqualTo(503);
    assertThat(node.jsonPath().getString("error")).isEqualTo("export_runtime_unavailable");
    assertThat(exportEvents(TENANT)).isZero();
  }
}
