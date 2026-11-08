package io.aster.guard.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.receipt.ReceiptSigningKeys;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §12 D6：{@code GET /api/v1/guard/keys} 发布 JWKS 与配置修订号。
 * HTTP 层用测试配置里的两把无 notAfter 的 key；时间窗与撤销过滤用 {@link ReceiptSigningKeys#parse} 构造的 key 集直接验证渲染逻辑。
 */
@QuarkusTest
class GuardKeysResourceTest {
  private static final String PATH = "/api/v1/guard/keys";
  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  /** 32 字节全 1 / 全 2 / 全 3 种子的 base64url。 */
  private static final String SEED_A = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
  private static final String SEED_B = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";
  private static final String SEED_C = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";

  private static JsonNode render(String keysJson, String activeKid, Instant now) {
    ReceiptSigningKeys keys = ReceiptSigningKeys.parse(keysJson, activeKid, Clock.fixed(NOW, ZoneOffset.UTC));
    return ExecutionReceiptAssert.tree(toJson(GuardKeysResource.render(keys, now)));
  }

  private static String toJson(Object body) {
    try {
      return new ObjectMapper().writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> kids(JsonNode jwks) {
    List<String> out = new ArrayList<>();
    jwks.get("keys").forEach(k -> out.add(k.get("kid").asText()));
    return out;
  }

  @Test
  void listsAllUnexpiredUnrevokedKidsWithRevision() {
    Response r = given().header("X-Tenant-Id", "tenant-guard-keys").get(PATH);
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode body = ExecutionReceiptAssert.tree(r.asString());
    assertThat(body.get("revision").asLong()).isEqualTo(1);
    assertThat(kids(body)).containsExactly("test-a", "test-b");
    JsonNode first = body.get("keys").get(0);
    assertThat(first.fieldNames()).toIterable().containsExactly("alg", "crv", "kid", "kty", "use", "x");
    assertThat(first.get("alg").asText()).isEqualTo("EdDSA");
    assertThat(first.get("crv").asText()).isEqualTo("Ed25519");
    assertThat(first.get("kty").asText()).isEqualTo("OKP");
    assertThat(first.get("use").asText()).isEqualTo("sig");
    assertThat(body.fieldNames()).toIterable().containsExactly("keys", "revision");
  }

  @Test
  void omitsRevokedAndPastNotAfter() {
    String json = "{\"revision\":9,\"keys\":["
        + "{\"kid\":\"live\",\"privateKey\":\"" + SEED_A + "\",\"notAfter\":\"2026-11-09T00:00:00Z\"},"
        + "{\"kid\":\"gone\",\"privateKey\":\"" + SEED_B + "\",\"notAfter\":\"2026-11-07T00:00:00Z\"},"
        + "{\"kid\":\"bad\",\"privateKey\":\"" + SEED_C + "\",\"revoked\":true}]}";
    JsonNode jwks = render(json, "live", NOW);
    assertThat(jwks.get("revision").asLong()).isEqualTo(9);
    assertThat(kids(jwks)).containsExactly("live");
    JsonNode live = jwks.get("keys").get(0);
    assertThat(live.get("exp").asLong()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z").getEpochSecond());
    assertThat(live.fieldNames()).toIterable().containsExactly("alg", "crv", "exp", "kid", "kty", "use", "x");
  }

  @Test
  void notAfterBoundaryIsExclusive() {
    String json = "{\"revision\":1,\"keys\":["
        + "{\"kid\":\"act\",\"privateKey\":\"" + SEED_A + "\"},"
        + "{\"kid\":\"edge\",\"privateKey\":\"" + SEED_B + "\",\"notAfter\":\"2026-11-08T00:00:00Z\"}]}";
    assertThat(kids(render(json, "act", NOW.minusSeconds(1)))).containsExactly("act", "edge");
    assertThat(kids(render(json, "act", NOW))).containsExactly("act");
  }

  @Test
  void retiredKidRemovedFromConfigIsNoLongerListed() {
    String before = "{\"revision\":1,\"keys\":[{\"kid\":\"old\",\"privateKey\":\"" + SEED_A
        + "\"},{\"kid\":\"new\",\"privateKey\":\"" + SEED_B + "\"}]}";
    String after = "{\"revision\":2,\"keys\":[{\"kid\":\"new\",\"privateKey\":\"" + SEED_B + "\"}]}";
    assertThat(kids(render(before, "new", NOW))).containsExactly("old", "new");
    assertThat(kids(render(after, "new", NOW))).containsExactly("new");
  }

  @Test
  void cacheControlMaxAge300() {
    Response r = given().header("X-Tenant-Id", "tenant-guard-keys").get(PATH);
    assertThat(r.header("Cache-Control")).contains("max-age=300");
  }

  @Test
  void resourceDeclaresMemberRole() {
    assertThat(GuardKeysResource.class.getAnnotation(RequireRole.class).value()).isEqualTo(Role.MEMBER);
    // 路径落在 ApiKeyAuthFilter.shouldProtect 的 /api/v1/guard/ 前缀内：见 ApiKeyAuthFilterShouldProtectTest
  }

  @Test
  void jwkXDecodesToPublicKeyThatVerifiesIssuedReceipt() throws Exception {
    JsonNode body = ExecutionReceiptAssert.tree(given().header("X-Tenant-Id", "tenant-guard-keys").get(PATH).asString());
    JsonNode jwk = body.get("keys").get(0);
    byte[] raw = Base64.getUrlDecoder().decode(jwk.get("x").asText());
    assertThat(raw).hasSize(32);
    assertThat(jwk.get("x").asText()).doesNotContain("=");

    // RFC 8032 §5.1.2 解码：最高位为 x 奇偶，其余小端为 y
    boolean xOdd = (raw[31] & 0x80) != 0;
    byte[] be = new byte[32];
    for (int i = 0; i < 32; i++) {
      be[i] = raw[31 - i];
    }
    be[0] &= 0x7f;
    var pub = KeyFactory.getInstance("Ed25519").generatePublic(
        new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, be))));

    byte[] message = "guard-keys-verify".getBytes(StandardCharsets.UTF_8);
    ReceiptSigningKeys keys = Arc.container().instance(ReceiptSigningKeys.class).get();
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.find(jwk.get("kid").asText()).orElseThrow().keyPair().getPrivate());
    signer.update(message);
    byte[] sig = signer.sign();
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(pub);
    verifier.update(message);
    assertThat(verifier.verify(sig)).isTrue();
  }
}
