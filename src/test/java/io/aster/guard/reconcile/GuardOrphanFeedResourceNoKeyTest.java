package io.aster.guard.reconcile;

import io.aster.security.internal.InternalV2SignatureVerifier;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** 未配置 {@code aster.plan-gate.hmac-key} 时两端点 fail-closed（403）。 */
@QuarkusTest
@TestProfile(GuardOrphanFeedResourceNoKeyTest.NoKeyProfile.class)
class GuardOrphanFeedResourceNoKeyTest {
  public static class NoKeyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("aster.plan-gate.hmac-key", "");
    }
  }

  @jakarta.inject.Inject io.micrometer.core.instrument.MeterRegistry registry;

  private double unauthorizedCount() {
    io.micrometer.core.instrument.Counter c = registry.find("guard.orphan.feed.requests").tag("outcome", "unauthorized").counter();
    return c == null ? 0.0 : c.count();
  }

  @Test
  void missingKeyIs403() {
    double before = unauthorizedCount();
    for (String path : new String[] {"/api/internal/guard/orphan-feed/tenants", "/api/internal/guard/orphan-feed/summary"}) {
      String body = "{}";
      long ts = System.currentTimeMillis() / 1000;
      String nonce = UUID.randomUUID().toString();
      String sig = InternalV2SignatureVerifier.sign("any-key", InternalV2SignatureVerifier.canonicalV2(
          "POST", path, ts, nonce, InternalV2SignatureVerifier.sha256Hex(body)));
      given().contentType(ContentType.JSON).header("X-Aster-Timestamp", Long.toString(ts))
          .header("X-Aster-Nonce", nonce).header("X-Aster-Signature", sig).body(body).post(path)
          .then().statusCode(403);
    }
    org.assertj.core.api.Assertions.assertThat(unauthorizedCount() - before).isEqualTo(2.0);
  }
}
