package io.aster.security.apikey;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/v1/audit/receipts 的双通道鉴权端到端：HMAC 内部调用者（无 Bearer）与 Bearer API key 均可访问，
 * 无凭证仍由 ApiKeyAuthFilter 返回 401，伪造签名不得放行。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(AuditReceiptHmacAuthTest.AuthProfile.class)
class AuditReceiptHmacAuthTest {

    /** apikey 鉴权与 HMAC 密钥同时打开；signature 关闭以贴近生产。 */
    public static class AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.apikey.enabled", "true",
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false");
        }
    }

    static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
    static final String PATH = "/api/v1/audit/receipts";
    static final String TENANT = "tenant-receipt-hmac";
    static final String OTHER = "tenant-receipt-hmac-other";
    static final String BEARER_KEY = "ak_test_receipt_member";

    @Inject AuditChainAppender appender;
    @Inject BlockingDbTestHelper db;
    @Inject ApiKeyVerifierService verifier;

    @BeforeEach void setUp() {
        db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id IN ('" + TENANT + "','" + OTHER + "')");
        appender.appendSync(event(TENANT, "c-hmac"));
        appender.appendSync(event(OTHER, "c-hmac"));
        verifier.seedCacheForTest(BEARER_KEY,
            ApiKeyVerifyResult.valid("ak-r-1", "user-r", TENANT, "pro", "active", "MEMBER"));
    }

    static AuditEvent event(String tenant, String cid) {
        return new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), tenant, "m", "f", null,
            null, null, "u", true, 5L, null, Map.of("correlationId", cid), null, null, null, null);
    }

    /** 与 cloud signInternalCallerHeaders 一致：GET 无 body，canonical 绑定 tenant 与 role。 */
    static Response signedGet(String tenant, String signKey) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "receipt-" + System.nanoTime();
        String canonical = "GET\n" + PATH + "\n" + ts + "\n" + nonce + "\n" + sha256Hex("") + "\n" + tenant + "\nmember";
        return given()
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sign(signKey, canonical))
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", "evidence-export")
            .header("X-User-Role", "member")
            .queryParam("correlationIds", "c-hmac")
            .get(PATH);
    }

    @Test void HMAC内部调用者无Bearer返回本租户收据() throws Exception {
        Response r = signedGet(TENANT, HMAC_KEY);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("receipts")).hasSize(1);
        assertThat(r.jsonPath().getString("receipts[0].correlationId")).isEqualTo("c-hmac");
        assertThat(r.jsonPath().getList("missing")).isNull();
    }

    @Test void HMAC调用者只能看到签名绑定租户的行() throws Exception {
        Response r = signedGet("tenant-receipt-hmac-stranger", HMAC_KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getList("receipts")).isNull();
        assertThat(r.jsonPath().getList("missing")).containsExactly("c-hmac");
    }

    @Test void 内部调用头存在但签名错误不放行() throws Exception {
        Response r = signedGet(TENANT, "wrong-key-wrong-key-wrong-key-32bytes");
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
        // 错误码沿用，文案按收据路径给出，不得误导成 evaluate-source
        assertThat(r.jsonPath().getString("error")).isEqualTo("evaluate_source_forbidden");
        assertThat(r.jsonPath().getString("reason")).isEqualTo("invalid_signature");
        assertThat(r.jsonPath().getString("message")).contains(PATH).doesNotContain("evaluate-source");
    }

    @Test void 无Bearer无HMAC头返回401() {
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u").queryParam("correlationIds", "c-hmac")
            .get(PATH).then().statusCode(401);
    }

    @Test void Bearer通道仍可用() {
        Response r = given().header("Authorization", "Bearer " + BEARER_KEY)
            .queryParam("correlationIds", "c-hmac").get(PATH);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("receipts")).hasSize(1);
    }

    static String sha256Hex(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String sign(String key, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}
