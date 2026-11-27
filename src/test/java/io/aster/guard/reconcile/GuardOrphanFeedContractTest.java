package io.aster.guard.reconcile;

import io.aster.security.internal.InternalV2SignatureVerifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.aster.guard.reconcile.GuardOrphanActionEntity.OPEN;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 孤儿通知源共享契约夹具（ADR 0065 §3.4.2）。
 *
 * <p>夹具由 aster-cloud 的通知源客户端测试逐字节复用（解析器输入与签名向量），
 * 两仓以 {@link #FIXTURE_SHA256} 钉住同一份内容；修改夹具必须同步更新两侧常量。
 */
@QuarkusTest
@TestProfile(GuardOrphanFeedResourceTest.FeedProfile.class)
class GuardOrphanFeedContractTest {
  static final String FIXTURE_SHA256 = "182ecd4a383d8d03ce0039aaa6adbd7b38fafb52bfe7ddbbadf4647e34024845";
  private static final Path FIXTURE = Path.of("src/test/resources/guard/orphan-feed-contract.json");
  private static final String TENANTS = "/api/internal/guard/orphan-feed/tenants";
  private static final String SUMMARY = "/api/internal/guard/orphan-feed/summary";

  private final List<String> tenants = new ArrayList<>();

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> tenants.forEach(t -> GuardOrphanActionEntity.delete("tenantId = ?1", t)));
  }

  private static byte[] fixtureBytes() throws IOException {
    return Files.readAllBytes(FIXTURE);
  }

  private static JsonObject fixture() throws IOException {
    return Json.createReader(new StringReader(new String(fixtureBytes(), StandardCharsets.UTF_8))).readObject();
  }

  /** 键序保留的结构签名：对象展开为 {@code {键:类型,…}}，整数为 integer，数组取首元素类型。 */
  private static String shape(JsonValue v) {
    return switch (v.getValueType()) {
      case OBJECT -> {
        StringBuilder sb = new StringBuilder("{");
        v.asJsonObject().forEach((k, child) -> sb.append(k).append(':').append(shape(child)).append(','));
        yield sb.append('}').toString();
      }
      case ARRAY -> {
        JsonArray a = v.asJsonArray();
        yield "array<" + (a.isEmpty() ? "" : shape(a.get(0))) + ">";
      }
      case STRING -> "string";
      case NUMBER -> ((jakarta.json.JsonNumber) v).isIntegral() ? "integer" : "decimal";
      default -> v.getValueType().name().toLowerCase();
    };
  }

  private static Response signedPost(String path, String body) {
    long ts = System.currentTimeMillis() / 1000;
    String nonce = UUID.randomUUID().toString();
    String sig = InternalV2SignatureVerifier.sign(GuardOrphanFeedResourceTest.HMAC_KEY, InternalV2SignatureVerifier.canonicalV2(
        "POST", path, ts, nonce, InternalV2SignatureVerifier.sha256Hex(body)));
    return given().contentType(ContentType.JSON)
        .header("X-Aster-Timestamp", Long.toString(ts)).header("X-Aster-Nonce", nonce).header("X-Aster-Signature", sig)
        .body(body).post(path);
  }

  private static String since() {
    return Instant.now().minus(Duration.ofHours(1)).toString();
  }

  @Test
  void fixtureSha256Pinned() throws Exception {
    byte[] bytes = fixtureBytes();
    assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
        .isEqualTo(FIXTURE_SHA256);
    assertThat(bytes).isNotEmpty();
    assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '\n');
    assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\r").doesNotStartWith("\uFEFF");
  }

  @Test
  void signingVectorMatchesProduction() throws Exception {
    JsonObject s = fixture().getJsonObject("signing");
    String canonical = InternalV2SignatureVerifier.canonicalV2(s.getString("method"), s.getString("path"),
        s.getJsonNumber("ts").longValue(), s.getString("nonce"), InternalV2SignatureVerifier.sha256Hex(s.getString("body")));
    assertThat(canonical).isEqualTo(s.getString("canonical"));
    assertThat(InternalV2SignatureVerifier.sign(s.getString("key"), canonical)).isEqualTo(s.getString("signature"));
  }

  @Test
  void signingBodyIsCompactSummaryRequest() throws Exception {
    JsonObject f = fixture();
    assertThat(f.getJsonObject("signing").getString("body")).isEqualTo(f.getJsonObject("summaryRequest").toString());
    assertThat(f.getJsonObject("signing").getString("path")).isEqualTo(SUMMARY);
    assertThat(f.getJsonObject("summaryRequest").getString("tenantId"))
        .isEqualTo(f.getJsonObject("summaryResponse").getString("tenantId"))
        .isEqualTo(f.getJsonObject("summaryEmptyResponse").getString("tenantId"));
  }

  @Test
  void responsesMatchResourceOutput() throws Exception {
    JsonObject f = fixture();
    String t = ReconcileFixture.tenant("contract");
    tenants.add(t);
    long a = ReconcileFixture.seedOrphan(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "s1", OPEN, Instant.now(), 3);
    long b = ReconcileFixture.seedOrphan(t, OrphanKind.RESULT_WITHOUT_CONSUME, "s2", OPEN, Instant.now(), 4);

    String index = signedPost(TENANTS, "{\"detectedSince\":\"" + since() + "\",\"limit\":500}")
        .then().statusCode(200).extract().asString();
    assertThat(shape(Json.createReader(new StringReader(index)).readObject()))
        .isEqualTo(shape(f.getJsonObject("tenantsResponse")));

    String full = signedPost(SUMMARY, "{\"tenantId\":\"" + t + "\",\"afterId\":" + (a - 1) + ",\"detectedSince\":\"" + since() + "\"}")
        .then().statusCode(200).extract().asString();
    assertThat(shape(Json.createReader(new StringReader(full)).readObject()))
        .isEqualTo(shape(f.getJsonObject("summaryResponse")));

    String empty = signedPost(SUMMARY, "{\"tenantId\":\"" + t + "\",\"afterId\":" + Math.max(a, b) + ",\"detectedSince\":\"" + since() + "\"}")
        .then().statusCode(200).extract().asString();
    JsonObject emptyObj = Json.createReader(new StringReader(empty)).readObject();
    assertThat(emptyObj.getJsonNumber("total").longValue()).isZero();
    assertThat(shape(emptyObj)).isEqualTo(shape(f.getJsonObject("summaryEmptyResponse")));
  }
}
