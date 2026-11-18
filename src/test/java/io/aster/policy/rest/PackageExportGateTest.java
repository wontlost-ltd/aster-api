package io.aster.policy.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.aster.policy.rest.PackageExportTestSupport.PATH;
import static io.aster.policy.rest.PackageExportTestSupport.SOURCE;
import static io.aster.policy.rest.PackageExportTestSupport.hmacPost;
import static io.aster.policy.rest.PackageExportTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/** max-concurrent=1：一个 prepare 持有许可期间，另一个 prepare 与一个导出都 503；释放后二者都成功；check 不占许可。 */
@QuarkusTest
@TestProfile(PackageExportTestSupport.BusyProfile.class)
class PackageExportGateTest {
  private static final String TENANT = "tenant-pkg-gate";

  @Test
  void prepareAndExportShareOnePermit() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    PackageExportResource.afterPermit = () -> {
      held.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    CompletableFuture<Response> holder;
    try {
      holder = CompletableFuture.supplyAsync(() -> hmacPost(PATH + "/prepare", request("jre", SOURCE), TENANT));
      assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
      PackageExportResource.afterPermit = () -> { };
      for (String sub : new String[] {"/prepare", ""}) {
        Response busy = hmacPost(PATH + sub, request("jre", SOURCE), TENANT);
        assertThat(busy.statusCode()).as(sub + " " + busy.asString()).isEqualTo(503);
        assertThat(busy.jsonPath().getString("error")).isEqualTo("package_export_busy");
        assertThat(busy.header("Retry-After")).isEqualTo("1");
      }
      assertThat(hmacPost(PATH + "/check", request("jre", SOURCE), TENANT).statusCode()).as("check 不占许可").isEqualTo(200);
    } finally {
      release.countDown();
    }
    assertThat(holder.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    PackageExportResource.afterPermit = () -> { };
    assertThat(hmacPost(PATH + "/prepare", request("jre", SOURCE), TENANT).statusCode()).isEqualTo(200);
    Response exported = hmacPost(PATH, request("jre", SOURCE), TENANT);
    assertThat(exported.statusCode()).as(exported.asString()).isEqualTo(200);
    assertThat(exported.contentType()).startsWith("application/zip");
  }
}
