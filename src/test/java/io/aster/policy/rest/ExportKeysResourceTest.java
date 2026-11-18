package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.signing.Ed25519KeyRing;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0059 修订 3 P2-R2：{@code GET /api/v1/exports/keys} 匿名可读，形状同 Guard JWKS。 */
@QuarkusTest
class ExportKeysResourceTest {
  /** 测试配置的固定种子 0x0e×32。 */
  private static final String SEED = "Dg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4";

  @Test
  void anonymousRequestGetsJwksWithTestKey() throws Exception {
    Response r = given().get(ExportKeysResource.PATH);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.header("Cache-Control")).contains("max-age=300");
    JsonNode body = new ObjectMapper().readTree(r.asString());
    assertThat(body.fieldNames()).toIterable().containsExactly("keys", "revision");
    assertThat(body.get("revision").asLong()).isEqualTo(1);
    assertThat(body.get("keys")).hasSize(1);
    JsonNode key = body.get("keys").get(0);
    assertThat(key.fieldNames()).toIterable().containsExactly("alg", "crv", "kid", "kty", "use", "x");
    assertThat(key.get("kid").asText()).isEqualTo("test-only-export");
    String expectedX = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Ed25519KeyRing.parse("{\"revision\":1,\"keys\":[{\"kid\":\"k\",\"privateKey\":\"" + SEED + "\"}]}", "k",
            Clock.systemUTC(), "t").active().rawPublicKey());
    assertThat(key.get("x").asText()).isEqualTo(expectedX);
  }

  @Test
  void sameAnswerWithTenantHeader() {
    assertThat(given().header("X-Tenant-Id", "tenant-export-keys").get(ExportKeysResource.PATH).statusCode())
        .isEqualTo(200);
  }

  @Test
  void emptyRingRendersEmptyKeysAndRevisionZero() {
    assertThat(io.aster.common.signing.Jwks.render(java.util.List.of(), 0, java.time.Instant.EPOCH).toString())
        .isEqualTo("{keys=[], revision=0}");
  }
}
