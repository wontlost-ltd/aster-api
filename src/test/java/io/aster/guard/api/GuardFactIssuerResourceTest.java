package io.aster.guard.api;

import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.security.apikey.InternalCallerSigning;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §11 D3：签发方管理端点 {@code /api/v1/guard/fact-issuers} 只接受内部 HMAC 通道。
 * 复用 {@link GuardFromEvidenceResourceTest.AuthProfile}（apikey 鉴权与 HMAC 同时打开），避免多一次应用重启。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardFromEvidenceResourceTest.AuthProfile.class)
class GuardFactIssuerResourceTest {
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String TENANT = "tenant-guard-issuer-" + RUN;
  private static final String OTHER = "tenant-guard-issuer-other-" + RUN;
  private static final String BASE = "/api/v1/guard/fact-issuers";
  private static final String BEARER_KEY = "ak_test_guard_issuer_member";
  private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  /** 32 字节原始公钥（端点只校验长度；真实 Ed25519 点由 Task 12 验签时使用）。 */
  private static final String PUBLIC_KEY = B64.encodeToString(bytes(32, (byte) 7));

  @Inject ApiKeyVerifierService verifier;
  @Inject FactIssuerStore store;

  @BeforeEach
  void setUp() {
    verifier.seedCacheForTest(BEARER_KEY,
        ApiKeyVerifyResult.valid("ak-gi-1", "user-gi", TENANT, "pro", "active", "MEMBER"));
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() ->
        GuardFactIssuerEntity.delete("tenantId in (?1, ?2)", TENANT, OTHER));
  }

  private static byte[] bytes(int n, byte fill) {
    byte[] b = new byte[n];
    Arrays.fill(b, fill);
    return b;
  }

  private static String path(String issuer, String kid) {
    return BASE + "/" + issuer + "/" + kid;
  }

  private static Response signed(String method, String path, String body, String tenant, String userId) {
    var req = given().headers(InternalCallerSigning.headers(HMAC_KEY, method, path, "", body, tenant, "member",
        userId, List.of()));
    if (!body.isEmpty()) {
      req = req.contentType("application/json").body(body);
    }
    return req.request(method, path);
  }

  private static Response put(String issuer, String kid, String body, String tenant) {
    return signed("PUT", path(issuer, kid), body, tenant, "u-admin");
  }

  private static String putBody(String publicKey) {
    return "{\"publicKey\":\"" + publicKey + "\",\"enabled\":true}";
  }

  @Test
  void bearerIs403InternalOnly() {
    List<Response> responses = List.of(
        given().header("Authorization", "Bearer " + BEARER_KEY).get(BASE),
        given().header("Authorization", "Bearer " + BEARER_KEY).contentType("application/json")
            .body(putBody(PUBLIC_KEY)).put(path("idp.example.com", "2026-11")),
        given().header("Authorization", "Bearer " + BEARER_KEY).delete(path("idp.example.com", "2026-11")),
        given().get(BASE));
    for (Response r : responses) {
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
      assertThat(r.jsonPath().getString("reason")).isEqualTo("internal_only");
    }
    assertThat(store.find(TENANT, "idp.example.com", "2026-11")).isEmpty();
  }

  @Test
  void hmacPutThenGetRoundTrips() {
    Response created = put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT);
    assertThat(created.statusCode()).as(created.asString()).isEqualTo(200);
    assertThat(created.jsonPath().getString("alg")).isEqualTo("Ed25519");
    assertThat(created.jsonPath().getString("issuer")).isEqualTo("idp.example.com");
    assertThat(created.jsonPath().getString("kid")).isEqualTo("2026-11");
    assertThat(created.jsonPath().getString("publicKey")).isEqualTo(PUBLIC_KEY);
    assertThat(created.jsonPath().getBoolean("enabled")).isTrue();
    assertThat(created.jsonPath().getString("createdBy")).isEqualTo("u-admin");
    assertThat(created.jsonPath().getString("createdAt")).isNotBlank();
    // notAfter 缺省时不写键；键按字母序输出
    assertThat(created.asString()).doesNotContain("notAfter");
    assertThat(created.asString()).startsWith("{\"alg\":\"Ed25519\",\"createdAt\":");

    String body2 = "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":true,\"notAfter\":\"2027-01-01T00:00:00Z\"}";
    Response second = put("idp.example.com", "2027-01", body2, TENANT);
    assertThat(second.statusCode()).as(second.asString()).isEqualTo(200);
    assertThat(second.jsonPath().getString("notAfter")).isEqualTo("2027-01-01T00:00:00Z");

    Response list = signed("GET", BASE, "", TENANT, "u-admin");
    assertThat(list.statusCode()).as(list.asString()).isEqualTo(200);
    assertThat(list.jsonPath().getList("issuers.kid")).containsExactly("2026-11", "2027-01");
    assertThat(list.jsonPath().getString("issuers[0].createdAt")).isEqualTo(created.jsonPath().getString("createdAt"));
    assertThat(list.jsonPath().getString("issuers[1].notAfter")).isEqualTo("2027-01-01T00:00:00Z");

    // 立即生效：存储层主键直查可见
    GuardFactIssuerEntity found = store.find(TENANT, "idp.example.com", "2026-11").orElseThrow();
    assertThat(found.publicKey).isEqualTo(PUBLIC_KEY);
    assertThat(found.enabled).isTrue();
    assertThat(found.alg).isEqualTo("Ed25519");

    // 同键同公钥再 PUT 为幂等更新（notAfter 可改）：创建时间与创建人保留
    Response replaced = signed("PUT", path("idp.example.com", "2026-11"),
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":true,\"notAfter\":\"2027-06-01T00:00:00Z\"}", TENANT, "u-other");
    assertThat(replaced.statusCode()).as(replaced.asString()).isEqualTo(200);
    assertThat(replaced.jsonPath().getString("notAfter")).isEqualTo("2027-06-01T00:00:00Z");
    assertThat(replaced.jsonPath().getString("createdBy")).isEqualTo("u-admin");
    assertThat(replaced.jsonPath().getString("createdAt")).isEqualTo(created.jsonPath().getString("createdAt"));
  }

  @Test
  void samePublicKeyIsIdempotentDifferentKeyIs409() {
    assertThat(put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);

    Response same = put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT);
    assertThat(same.statusCode()).as(same.asString()).isEqualTo(200);
    assertThat(same.jsonPath().getString("publicKey")).isEqualTo(PUBLIC_KEY);
    // 带填充的同一公钥规范化后相同，同为幂等
    assertThat(put("idp.example.com", "2026-11", putBody(PUBLIC_KEY + "="), TENANT).statusCode()).isEqualTo(200);

    String otherKey = B64.encodeToString(bytes(32, (byte) 8));
    Response conflict = put("idp.example.com", "2026-11", putBody(otherKey), TENANT);
    assertThat(conflict.statusCode()).as(conflict.asString()).isEqualTo(409);
    assertThat(conflict.jsonPath().getString("error")).isEqualTo("issuer_key_conflict");
    assertThat(store.find(TENANT, "idp.example.com", "2026-11").orElseThrow().publicKey).isEqualTo(PUBLIC_KEY);
  }

  /** 裁定：吊销是终态，DELETE 或 PUT enabled=false 之后不可再启用，须轮换到新 kid。 */
  @Test
  void revokedIssuerCannotBeReEnabled() {
    assertThat(put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);
    assertThat(signed("DELETE", path("idp.example.com", "2026-11"), "", TENANT, "u-admin").statusCode())
        .isEqualTo(204);
    Response reEnable = put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT);
    assertThat(reEnable.statusCode()).as(reEnable.asString()).isEqualTo(409);
    assertThat(reEnable.jsonPath().getString("error")).isEqualTo("issuer_revoked");
    // 缺省 enabled 视为 true，同样拒绝
    Response defaulted = put("idp.example.com", "2026-11", "{\"publicKey\":\"" + PUBLIC_KEY + "\"}", TENANT);
    assertThat(defaulted.statusCode()).isEqualTo(409);
    assertThat(defaulted.jsonPath().getString("error")).isEqualTo("issuer_revoked");
    assertThat(store.find(TENANT, "idp.example.com", "2026-11").orElseThrow().enabled).isFalse();

    // PUT enabled=false 同为吊销
    assertThat(put("idp.example.com", "2026-12", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);
    Response disabled = put("idp.example.com", "2026-12",
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":false}", TENANT);
    assertThat(disabled.statusCode()).as(disabled.asString()).isEqualTo(200);
    assertThat(disabled.jsonPath().getBoolean("enabled")).isFalse();
    // 已吊销行再次以 enabled=false 提交为幂等 200
    assertThat(put("idp.example.com", "2026-12",
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":false}", TENANT).statusCode()).isEqualTo(200);
    Response again = put("idp.example.com", "2026-12", putBody(PUBLIC_KEY), TENANT);
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.jsonPath().getString("error")).isEqualTo("issuer_revoked");

    // 轮换到新 kid 正常登记
    assertThat(put("idp.example.com", "2027-01", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);
  }

  /** 过 notAfter 即终态（ADR 0040 §11 D4）：已过期的行不能再以同钥 PUT 延期或启用（409 issuer_revoked，须轮换 kid）；未过期时可延期。 */
  @Test
  void lapsedIssuerCannotBeExtended() {
    String past = Instant.now().minusSeconds(5).toString();
    String future = Instant.now().plusSeconds(3600).toString();
    String soon = "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"notAfter\":\"" + future + "\"}";
    assertThat(put("idp.example.com", "live", soon, TENANT).statusCode()).isEqualTo(200);
    String later = "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"notAfter\":\"2099-01-01T00:00:00Z\"}";
    Response extended = put("idp.example.com", "live", later, TENANT);
    assertThat(extended.statusCode()).as("未过期可延期").isEqualTo(200);
    assertThat(extended.jsonPath().getString("notAfter")).isEqualTo("2099-01-01T00:00:00Z");

    String lapsedBody = "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"notAfter\":\"" + past + "\"}";
    assertThat(put("idp.example.com", "lapsed", lapsedBody, TENANT).statusCode()).isEqualTo(200);
    Response extend = put("idp.example.com", "lapsed", later, TENANT);
    assertThat(extend.statusCode()).as(extend.asString()).isEqualTo(409);
    assertThat(extend.jsonPath().getString("error")).isEqualTo("issuer_revoked");
    // 以 enabled=false 提交仍为 200（即吊销）
    Response disable = put("idp.example.com", "lapsed",
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":false}", TENANT);
    assertThat(disable.statusCode()).as(disable.asString()).isEqualTo(200);
    assertThat(disable.jsonPath().getBoolean("enabled")).isFalse();
  }

  /** 并发首次写入同一主键：不出现 500；同钥两边 200，异钥恰一边 409 issuer_key_conflict。 */
  @Test
  void concurrentFirstPutsResolveWithoutServerError() throws Exception {
    String otherKey = B64.encodeToString(bytes(32, (byte) 3));
    for (int round = 0; round < 10; round++) {
      String sameKid = "same-" + round;
      List<Response> same = race(prepared(sameKid, PUBLIC_KEY), prepared(sameKid, PUBLIC_KEY));
      assertThat(same).extracting(Response::statusCode).containsExactly(200, 200);

      String diffKid = "diff-" + round;
      List<Response> diff = race(prepared(diffKid, PUBLIC_KEY), prepared(diffKid, otherKey));
      assertThat(diff).extracting(Response::statusCode).containsExactlyInAnyOrder(200, 409);
      assertThat(diff).filteredOn(r -> r.statusCode() == 409)
          .extracting(r -> r.jsonPath().getString("error")).containsExactly("issuer_key_conflict");
    }
  }

  /**
   * 在主线程预先签好请求头：{@link InternalCallerSigning} 的 nonce 取 {@code System.nanoTime()}，
   * 两个线程同时签名可能拿到同一 nonce 而被防重放拒绝（409 Nonce replay），与被测逻辑无关。
   */
  private static Callable<Response> prepared(String kid, String publicKey) {
    String p = path("idp.example.com", kid);
    String body = putBody(publicKey);
    Map<String, String> headers = InternalCallerSigning.headers(HMAC_KEY, "PUT", p, "", body, TENANT, "member",
        "u-admin", List.of());
    return () -> given().headers(headers).contentType("application/json").body(body).put(p);
  }

  private static List<Response> race(Callable<Response> a, Callable<Response> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Response> fa = pool.submit(() -> { start.await(); return a.call(); });
      Future<Response> fb = pool.submit(() -> { start.await(); return b.call(); });
      start.countDown();
      return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  /** HMAC 调用缺签名租户时不回退到默认租户。 */
  @Test
  void missingSignedTenantIs400() {
    for (Response r : List.of(signed("GET", BASE, "", null, "u-admin"),
        signed("PUT", path("idp.example.com", "2026-11"), putBody(PUBLIC_KEY), null, "u-admin"),
        signed("DELETE", path("idp.example.com", "2026-11"), "", null, "u-admin"))) {
      // TenantFilter 先于资源以纯文本 400 拒绝缺租户的请求；资源内的同类检查是兜底，二者都不回退到默认租户。
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(400);
    }
    assertThat(store.find("default", "idp.example.com", "2026-11")).isEmpty();
  }

  @Test
  void badPublicKeyIs400() {
    for (String key : List.of(B64.encodeToString(bytes(31, (byte) 1)), B64.encodeToString(bytes(33, (byte) 1)),
        "not base64!!", "")) {
      Response r = put("idp.example.com", "bad", putBody(key), TENANT);
      assertThat(r.statusCode()).as(key + " -> " + r.asString()).isEqualTo(400);
      assertThat(r.jsonPath().getString("error")).isEqualTo("invalid_issuer");
    }
    Response missing = put("idp.example.com", "bad", "{\"enabled\":true}", TENANT);
    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.jsonPath().getString("error")).isEqualTo("invalid_issuer");

    Response wrongAlg = put("idp.example.com", "bad",
        "{\"alg\":\"RS256\",\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":true}", TENANT);
    assertThat(wrongAlg.statusCode()).isEqualTo(400);
    assertThat(wrongAlg.jsonPath().getString("error")).isEqualTo("invalid_issuer");

    Response badNotAfter = put("idp.example.com", "bad",
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":true,\"notAfter\":\"tomorrow\"}", TENANT);
    assertThat(badNotAfter.statusCode()).isEqualTo(400);
    assertThat(badNotAfter.jsonPath().getString("error")).isEqualTo("invalid_issuer");

    Response notJson = put("idp.example.com", "bad", "{not json", TENANT);
    assertThat(notJson.statusCode()).isEqualTo(400);
    assertThat(notJson.jsonPath().getString("error")).isEqualTo("invalid_issuer");

    // 全局约束：guard 请求体（含 /fact-issuers/*）任一层重复键 → 400 invalid_action
    Response dup = put("idp.example.com", "bad",
        "{\"publicKey\":\"" + PUBLIC_KEY + "\",\"publicKey\":\"" + PUBLIC_KEY + "\",\"enabled\":true}", TENANT);
    assertThat(dup.statusCode()).as(dup.asString()).isEqualTo(400);
    assertThat(dup.jsonPath().getString("error")).isEqualTo("invalid_action");

    Response badKid = put("idp.example.com", "a#b", putBody(PUBLIC_KEY), TENANT);
    assertThat(badKid.statusCode()).isEqualTo(400);
    assertThat(badKid.jsonPath().getString("error")).isEqualTo("invalid_issuer");

    assertThat(store.find(TENANT, "idp.example.com", "bad")).isEmpty();
  }

  @Test
  void deleteIsSoftDisable() {
    assertThat(put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);

    Response deleted = signed("DELETE", path("idp.example.com", "2026-11"), "", TENANT, "u-admin");
    assertThat(deleted.statusCode()).as(deleted.asString()).isEqualTo(204);
    assertThat(deleted.asString()).isEmpty();

    GuardFactIssuerEntity row = store.find(TENANT, "idp.example.com", "2026-11").orElseThrow();
    assertThat(row.enabled).isFalse();
    assertThat(row.publicKey).isEqualTo(PUBLIC_KEY);

    Response list = signed("GET", BASE, "", TENANT, "u-admin");
    assertThat(list.jsonPath().getList("issuers")).hasSize(1);
    assertThat(list.jsonPath().getBoolean("issuers[0].enabled")).isFalse();

    // 重复删除幂等；不存在的签发方 404 空体
    assertThat(signed("DELETE", path("idp.example.com", "2026-11"), "", TENANT, "u-admin").statusCode())
        .isEqualTo(204);
    Response missing = signed("DELETE", path("idp.example.com", "nope"), "", TENANT, "u-admin");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.asString()).isEmpty();
  }

  @Test
  void crossTenantInvisible() {
    assertThat(put("idp.example.com", "2026-11", putBody(PUBLIC_KEY), TENANT).statusCode()).isEqualTo(200);

    Response otherList = signed("GET", BASE, "", OTHER, "u-admin");
    assertThat(otherList.statusCode()).isEqualTo(200);
    assertThat(otherList.jsonPath().getList("issuers")).isEmpty();
    assertThat(otherList.asString()).isEqualTo("{\"issuers\":[]}");

    Response otherDelete = signed("DELETE", path("idp.example.com", "2026-11"), "", OTHER, "u-admin");
    assertThat(otherDelete.statusCode()).isEqualTo(404);
    assertThat(store.find(OTHER, "idp.example.com", "2026-11")).isEmpty();
    assertThat(store.find(TENANT, "idp.example.com", "2026-11").orElseThrow().enabled).isTrue();

    // 另一租户可登记同名 issuer/kid，互不覆盖
    String otherKey = B64.encodeToString(bytes(32, (byte) 9));
    assertThat(put("idp.example.com", "2026-11", putBody(otherKey), OTHER).statusCode()).isEqualTo(200);
    assertThat(store.find(TENANT, "idp.example.com", "2026-11").orElseThrow().publicKey).isEqualTo(PUBLIC_KEY);
    assertThat(store.find(OTHER, "idp.example.com", "2026-11").orElseThrow().publicKey).isEqualTo(otherKey);
  }

  @Test
  void hmacListOfEmptyTenantIsEmptyArray() {
    Response r = signed("GET", BASE, "", TENANT, "u-admin");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.jsonPath().getMap("$")).isEqualTo(Map.of("issuers", List.of()));
  }
}
