package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.policy.export.ValueJson;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.aster.policy.rest.PackageExportTestSupport.JSON;
import static io.aster.policy.rest.PackageExportTestSupport.PATH;
import static io.aster.policy.rest.PackageExportTestSupport.SOURCE;
import static io.aster.policy.rest.PackageExportTestSupport.hmacPost;
import static io.aster.policy.rest.PackageExportTestSupport.request;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 策略包导出端点前置拒绝与 check / prepare（ADR 0059 P2 T12）。 */
@QuarkusTest
@TestProfile(CedarExportResourceTest.AuthProfile.class)
class PackageExportResourceTest {
  private static final String TENANT = "tenant-pkg-export";

  @Inject PackageExportGate gate;

  @Test
  void disabledSwitchIs404BeforeAuthentication() {
    PackageExportResource.enabledForTest = false;
    try {
      for (String sub : List.of("/check", "/prepare", "")) {
        assertThat(hmacPost(PATH + sub, request("jre", SOURCE), TENANT).statusCode()).as(sub).isEqualTo(404);
        Response anonymous = given().header("X-Tenant-Id", TENANT).contentType(ContentType.JSON).body("{}").post(PATH + sub);
        assertThat(anonymous.statusCode()).as(sub + " 非 HMAC").isEqualTo(404);
      }
      assertThat(given().get(ExportKeysResource.PATH).statusCode()).as("JWKS 不受开关影响").isEqualTo(200);
    } finally {
      PackageExportResource.enabledForTest = null;
    }
  }

  /** prepare 与导出共用同一个闸：许可占满时二者都 503 + Retry-After: 1，check 不受影响；释放后二者成功。 */
  @Test
  void prepareAndExportShareOneGate() {
    int held = 0;
    try {
      while (gate.tryAcquire()) {
        held++;
      }
      assertThat(held).isEqualTo(gate.limit());
      for (String sub : List.of("/prepare", "")) {
        Response busy = hmacPost(PATH + sub, request("jre", SOURCE), TENANT);
        assertThat(busy.statusCode()).as(sub + " " + busy.asString()).isEqualTo(503);
        assertThat(busy.jsonPath().getString("error")).isEqualTo("package_export_busy");
        assertThat(busy.header("Retry-After")).isEqualTo("1");
      }
      assertThat(hmacPost(PATH + "/check", request("jre", SOURCE), TENANT).statusCode()).as("check 不占许可").isEqualTo(200);
    } finally {
      for (int i = 0; i < held; i++) {
        gate.release();
      }
    }
    assertThat(hmacPost(PATH + "/prepare", request("jre", SOURCE), TENANT).statusCode()).isEqualTo(200);
    Response exported = hmacPost(PATH, request("jre", SOURCE), TENANT);
    assertThat(exported.statusCode()).as(exported.asString()).isEqualTo(200);
    assertThat(exported.contentType()).startsWith("application/zip");
  }

  @Test
  void nonHmacCallerIsForbidden() throws Exception {
    String body = JSON.writeValueAsString(request("jre", SOURCE));
    for (String sub : List.of("/check", "/prepare", "")) {
      Response r = given().header("X-Tenant-Id", TENANT).header("Authorization", "Bearer ak_not_a_cloud_call")
          .contentType(ContentType.JSON).body(body).post(PATH + sub);
      assertThat(r.statusCode()).as(sub + " " + r.asString()).isEqualTo(403);
      assertThat(r.jsonPath().getString("error")).isEqualTo("export_requires_cloud");
    }
  }

  private static void assertInvalid(String code, Consumer<Map<String, Object>> mutate) {
    Map<String, Object> req = request("jre", SOURCE);
    mutate.accept(req);
    Response r = hmacPost(PATH + "/check", req, TENANT);
    assertThat(r.statusCode()).as(code + " " + r.asString()).isEqualTo(400);
    assertThat(r.jsonPath().getString("error")).isEqualTo(code);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> claimed(Map<String, Object> req) {
    Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) req.get("claimedRef"));
    req.put("claimedRef", c);
    return c;
  }

  @Test
  void eachInputErrorHasItsCode() {
    assertInvalid("source_required", r -> r.remove("source"));
    assertInvalid("source_too_large", r -> r.put("source", "x".repeat(16_385)));
    assertInvalid("target_invalid", r -> r.put("target", "wasm"));
    assertInvalid("target_invalid", r -> r.remove("target"));
    assertInvalid("mode_unsupported", r -> r.put("mode", "a"));
    assertInvalid("ui_locale_invalid", r -> r.put("uiLocale", "fr"));
    assertInvalid("claimed_ref_invalid", r -> r.remove("claimedRef"));
    assertInvalid("claimed_ref_invalid", r -> claimed(r).put("version", 0));
    assertInvalid("claimed_ref_invalid", r -> claimed(r).put("policyId", "has space"));
    assertInvalid("claimed_ref_invalid", r -> claimed(r).put("status", "x".repeat(129)));
    assertInvalid("claimed_ref_invalid", r -> claimed(r).put("approvedAt", "yesterday"));
    assertInvalid("claimed_ref_invalid", r -> claimed(r).remove("policyId"));
  }

  @Test
  void truffleFuncEnvironmentIsMisconfiguration() {
    var original = PackageExportResource.truffleFuncSet;
    PackageExportResource.truffleFuncSet = () -> true;
    try {
      Response r = hmacPost(PATH + "/check", request("jre", SOURCE), TENANT);
      assertThat(r.statusCode()).isEqualTo(503);
      assertThat(r.jsonPath().getString("error")).isEqualTo("export_runtime_misconfigured");
    } finally {
      PackageExportResource.truffleFuncSet = original;
    }
  }

  @Test
  void checkReturnsBothTargets() {
    Response r = hmacPost(PATH + "/check", request("jre", SOURCE), TENANT);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getBoolean("targets.jre.exportable")).isTrue();
    // node 用 ts 白名单（export-builtins-node.json）与锁文件 node 项
    assertThat(r.jsonPath().getBoolean("targets.node.exportable")).isTrue();
    assertThat(r.jsonPath().getString("defaultEntry")).isEqualTo("decide");
    assertThat(r.jsonPath().getList("entryCandidates", String.class)).containsExactly("decide");
  }

  @Test
  void checkReportsDiagnosticsAndEntryChoice() {
    String io = String.join("\n", "Module t.io.", "", "Rule fetch given x as Int, produce Int. It performs io:", "  Return x.");
    Response r = hmacPost(PATH + "/check", request("jre", io), TENANT);
    assertThat(r.jsonPath().getBoolean("targets.jre.exportable")).isFalse();
    assertThat(r.jsonPath().getList("targets.jre.diagnostics.code", String.class)).contains("E730");
    String multi = String.join("\n", "Module t.m.", "", "Rule a given x as Int, produce Int:", "  Return x.", "",
        "Rule b given x as Int, produce Int:", "  Return x.");
    Response m = hmacPost(PATH + "/check", request("jre", multi), TENANT);
    assertThat(m.jsonPath().getString("defaultEntry")).isNull();
    assertThat(m.jsonPath().getList("entryCandidates", String.class)).containsExactly("a", "b");
    Response p = hmacPost(PATH + "/prepare", request("jre", multi), TENANT);
    assertThat(p.statusCode()).isEqualTo(400);
    assertThat(p.jsonPath().getString("error")).isEqualTo("entry_required");
  }

  @Test
  void prepareReturnsProbesWithTheirCanonicalHash() throws Exception {
    Response r = hmacPost(PATH + "/prepare", request("jre", SOURCE), TENANT);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    JsonNode body = JSON.readTree(r.asString());
    assertThat(body.get("exportable").asBoolean()).isTrue();
    assertThat(body.get("entry").asText()).isEqualTo("decide");
    byte[] canonical = ValueJson.write(body.get("probes")).getBytes(StandardCharsets.UTF_8);
    assertThat(body.get("probesSha256").asText())
        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
    assertThat(body.get("probeCount").asInt()).isEqualTo(body.at("/probes/probes").size()).isPositive();
    assertThat(body.get("coreIrSha256").asText()).isEqualTo(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(body.get("coreIr").asText().getBytes(StandardCharsets.UTF_8))));
    assertThat(body.get("positionFreeSha256").asText()).hasSize(64);
    assertThat(body.at("/runtime/cliName").asText()).isEqualTo("cloud.aster-lang:aster-cli");
    assertThat(body.at("/runtime/cliVersion").asText()).isEqualTo("1.0.53");
  }
}
