package io.aster.guard.reconcile;

import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.security.internal.InternalV2SignatureVerifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.aster.guard.reconcile.GuardOrphanActionEntity.OPEN;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 孤儿通知源内部端点（ADR 0065 §3.4.2）：仅 v2 内部签名可达，响应键集固定且不含明细。 */
@QuarkusTest
@TestProfile(GuardOrphanFeedResourceTest.FeedProfile.class)
class GuardOrphanFeedResourceTest {
  static final String HMAC_KEY = "orphan-feed-test-key-32bytes-min!";
  private static final String TENANTS = "/api/internal/guard/orphan-feed/tenants";
  private static final String SUMMARY = "/api/internal/guard/orphan-feed/summary";
  private static final String BEARER_KEY = "ak_test_orphan_feed_member";
  private static final String MARKER = "SECRET-MARKER-" + UUID.randomUUID();

  public static class FeedProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "aster.plan-gate.hmac-key", HMAC_KEY,
          "aster.security.apikey.enabled", "true",
          "aster.security.signature.enabled", "false");
    }
  }

  @Inject EntityManager em;
  @Inject ApiKeyVerifierService apiKeys;
  @ConfigProperty(name = "aster.guard.reconciliation.lookback") Duration lookback;

  private final List<String> tenants = new ArrayList<>();

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> tenants.forEach(t -> GuardOrphanActionEntity.delete("tenantId = ?1", t)));
  }

  private String tenant(String scenario) {
    String t = ReconcileFixture.tenant("feedres-" + scenario);
    tenants.add(t);
    return t;
  }

  private static Map<String, String> v2Headers(String path, String body) {
    long ts = System.currentTimeMillis() / 1000;
    String nonce = UUID.randomUUID().toString();
    String sig = InternalV2SignatureVerifier.sign(HMAC_KEY, InternalV2SignatureVerifier.canonicalV2(
        "POST", path, ts, nonce, InternalV2SignatureVerifier.sha256Hex(body)));
    return Map.of("X-Aster-Timestamp", Long.toString(ts), "X-Aster-Nonce", nonce, "X-Aster-Signature", sig);
  }

  private static Response post(String path, Map<String, String> headers, String body) {
    return given().contentType(ContentType.JSON).headers(headers).body(body).post(path);
  }

  private static Response signedPost(String path, String body) {
    return post(path, v2Headers(path, body), body);
  }

  private static String since() {
    return Instant.now().minus(Duration.ofHours(1)).toString();
  }

  private static String tenantsBody(String since) {
    return "{\"detectedSince\":\"" + since + "\",\"limit\":500}";
  }

  private static String summaryBody(String tenant, long after) {
    return "{\"tenantId\":\"" + tenant + "\",\"afterId\":" + after + ",\"detectedSince\":\"" + since() + "\"}";
  }

  private static JsonObject json(Response r) {
    return Json.createReader(new StringReader(r.asString())).readObject();
  }

  private long seedWithMarker(String tenant, OrphanKind kind, String subject, long run) {
    long id = ReconcileFixture.seedOrphan(tenant, kind, subject, OPEN, Instant.now(), run);
    QuarkusTransaction.requiringNew().run(() -> em
        .createNativeQuery("UPDATE guard_orphan_actions SET detail = CAST(:d AS jsonb), executor_id = :e WHERE id = :id")
        .setParameter("d", "{\"reason\":\"" + MARKER + "\"}").setParameter("e", "exec-" + MARKER)
        .setParameter("id", id).executeUpdate());
    return id;
  }

  private long auditRows() {
    return QuarkusTransaction.requiringNew().call(() -> ((Number) em
        .createNativeQuery("SELECT COUNT(*) FROM audit_logs").getSingleResult()).longValue());
  }

  private static double feedCount(String outcome) {
    String body = given().get("/q/metrics").then().statusCode(200).extract().asString();
    String prefix = "guard_orphan_feed_requests_total{outcome=\"" + outcome + "\"";
    return body.lines().filter(l -> l.startsWith(prefix)).findFirst()
        .map(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).orElse(0.0);
  }

  @Test
  void metricsCountEachOutcome() {
    List<String> outcomes = List.of("ok", "invalid", "unauthorized", "replayed");
    Map<String, Double> before = new java.util.HashMap<>();
    outcomes.forEach(o -> before.put(o, feedCount(o)));

    signedPost(TENANTS, tenantsBody(since())).then().statusCode(200);
    signedPost(TENANTS, "not json").then().statusCode(400);
    Map<String, String> bad = new java.util.HashMap<>(v2Headers(TENANTS, "{}"));
    bad.put("X-Aster-Signature", "0".repeat(64));
    post(TENANTS, bad, "{}").then().statusCode(401);
    String body = tenantsBody(since());
    Map<String, String> h = v2Headers(TENANTS, body);
    post(TENANTS, h, body).then().statusCode(200);
    post(TENANTS, h, body).then().statusCode(409);

    assertThat(feedCount("ok") - before.get("ok")).isEqualTo(2.0);
    assertThat(feedCount("invalid") - before.get("invalid")).isEqualTo(1.0);
    assertThat(feedCount("unauthorized") - before.get("unauthorized")).isEqualTo(1.0);
    assertThat(feedCount("replayed") - before.get("replayed")).isEqualTo(1.0);
    String metrics = given().get("/q/metrics").then().extract().asString();
    assertThat(metrics.lines().filter(l -> l.startsWith("guard_orphan_feed_requests_total{")))
        .allMatch(l -> l.matches("guard_orphan_feed_requests_total\\{outcome=\"[a-z]+\"\\} .*"));
  }

  @Test
  void badSignatureIs401() {
    for (String path : List.of(TENANTS, SUMMARY)) {
      Map<String, String> h = new java.util.HashMap<>(v2Headers(path, "{}"));
      h.put("X-Aster-Signature", "0".repeat(64));
      post(path, h, "{}").then().statusCode(401);
    }
  }

  @Test
  void missingHeadersIs401() {
    for (String path : List.of(TENANTS, SUMMARY)) {
      post(path, Map.of(), tenantsBody(since())).then().statusCode(401);
    }
  }

  @Test
  void v1Is401() {
    for (String path : List.of(TENANTS, SUMMARY)) {
      long ts = System.currentTimeMillis() / 1000;
      String sig = InternalV2SignatureVerifier.sign(HMAC_KEY, InternalV2SignatureVerifier.canonicalV1("POST", path, ts));
      post(path, Map.of("X-Aster-Timestamp", Long.toString(ts), "X-Aster-Signature", sig), tenantsBody(since()))
          .then().statusCode(401);
    }
  }

  @Test
  void nonceReplayIs409() {
    String body = tenantsBody(since());
    Map<String, String> h = v2Headers(TENANTS, body);
    post(TENANTS, h, body).then().statusCode(200);
    post(TENANTS, h, body).then().statusCode(409);
  }

  @Test
  void bearerApiKeyNotAccepted() {
    apiKeys.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-of-1", "user-of", tenant("bearer"), "pro", "active", "MEMBER"));
    for (String path : List.of(TENANTS, SUMMARY)) {
      given().contentType(ContentType.JSON).header("Authorization", "Bearer " + BEARER_KEY)
          .body(tenantsBody(since())).post(path).then().statusCode(401)
          .body(org.hamcrest.Matchers.equalTo("missing signature headers"));
    }
  }

  @Test
  void internalCallerV3HeadersNotAccepted() {
    for (String path : List.of(TENANTS, SUMMARY)) {
      String body = tenantsBody(since());
      Map<String, String> h = InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", body,
          "tenant-x", "ADMIN", "", List.of());
      post(path, h, body).then().statusCode(401).body(org.hamcrest.Matchers.equalTo("missing signature headers"));
    }
  }

  @Test
  void signatureBindsBody() {
    String signed = summaryBody("a", 0);
    Map<String, String> h = v2Headers(SUMMARY, signed);
    post(SUMMARY, h, summaryBody("b", 0)).then().statusCode(401);
  }

  @Test
  void invalidQueryIs400() {
    String tooOld = Instant.now().minus(lookback).minus(Duration.ofMinutes(45)).toString();
    signedPost(TENANTS, tenantsBody(tooOld)).then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    signedPost(TENANTS, "{\"detectedSince\":\"" + since() + "\",\"limit\":0}").then().statusCode(400)
        .body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    for (String scalar : List.of("\"x\"", "5", "null", "true")) {
      signedPost(TENANTS, scalar).then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    }
    String deep = "[".repeat(1500) + "]".repeat(1500);
    signedPost(TENANTS, deep).then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    signedPost(TENANTS, "[]").then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    signedPost(TENANTS, "not json").then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    signedPost(SUMMARY, "{\"tenantId\":\"t\",\"afterId\":-1,\"detectedSince\":\"" + since() + "\"}").then().statusCode(400)
        .body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
    signedPost(SUMMARY, "[]").then().statusCode(400).body("error", org.hamcrest.Matchers.equalTo("invalid_query"));
  }

  @Test
  void verifyBeforeParse() {
    Map<String, String> h = new java.util.HashMap<>(v2Headers(TENANTS, "not json"));
    h.put("X-Aster-Signature", "0".repeat(64));
    post(TENANTS, h, "not json").then().statusCode(401);
  }

  @Test
  void tenantsHappyPath() {
    String t = tenant("tenants");
    long id = seedWithMarker(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "s1", 1);
    JsonArray items = json(signedPost(TENANTS, tenantsBody(since())).then().statusCode(200).extract().response())
        .getJsonArray("items");
    JsonObject mine = items.getValuesAs(JsonObject.class).stream()
        .filter(o -> t.equals(o.getString("tenantId"))).findFirst().orElseThrow();
    assertThat(mine.getJsonNumber("maxId").longValue()).isEqualTo(id);
  }

  @Test
  void summaryHappyPath() {
    String t = tenant("summary");
    long a = seedWithMarker(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "s1", 3);
    long b = seedWithMarker(t, OrphanKind.RESULT_WITHOUT_CONSUME, "s2", 4);
    JsonObject o = json(signedPost(SUMMARY, summaryBody(t, a - 1)).then().statusCode(200).extract().response());
    assertThat(o.getString("tenantId")).isEqualTo(t);
    assertThat(o.getJsonNumber("maxId").longValue()).isEqualTo(b);
    assertThat(o.getJsonNumber("total").longValue()).isEqualTo(2);
    assertThat(o.getJsonObject("byKind").getJsonNumber("side_effect_unconsumed").longValue()).isEqualTo(1);
    assertThat(o.getJsonObject("byKind").getJsonNumber("result_without_consume").longValue()).isEqualTo(1);
    assertThat(Instant.parse(o.getString("firstDetectedAt"))).isBeforeOrEqualTo(Instant.parse(o.getString("lastDetectedAt")));
    assertThat(o.getJsonArray("runIds").getValuesAs(jakarta.json.JsonNumber.class).stream().map(n -> n.longValue()).toList())
        .containsExactly(4L, 3L);
  }

  @Test
  void responseKeysPinned() {
    String t = tenant("keys");
    long id = seedWithMarker(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "s1", 1);
    Response index = signedPost(TENANTS, tenantsBody(since()));
    JsonObject root = json(index);
    assertThat(root.keySet()).isSubsetOf("items", "next");
    for (JsonObject item : root.getJsonArray("items").getValuesAs(JsonObject.class)) {
      assertThat(item.keySet()).isEqualTo(Set.of("tenantId", "maxId"));
    }
    Response full = signedPost(SUMMARY, summaryBody(t, id - 1));
    assertThat(json(full).keySet()).isEqualTo(
        Set.of("tenantId", "maxId", "total", "byKind", "firstDetectedAt", "lastDetectedAt", "runIds"));
    Response empty = signedPost(SUMMARY, summaryBody(t, id));
    assertThat(json(empty).keySet()).isEqualTo(Set.of("tenantId", "maxId", "total", "byKind", "runIds"));
    for (String text : List.of(index.asString(), full.asString(), empty.asString())) {
      assertThat(text).doesNotContain(MARKER).doesNotContain("executor");
    }
  }

  @Test
  void noChainWriteNoQuota() {
    String t = tenant("audit");
    seedWithMarker(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "s1", 1);
    long before = auditRows();
    signedPost(TENANTS, tenantsBody(since())).then().statusCode(200);
    signedPost(SUMMARY, summaryBody(t, 0)).then().statusCode(200);
    assertThat(auditRows()).isEqualTo(before);
  }
}
