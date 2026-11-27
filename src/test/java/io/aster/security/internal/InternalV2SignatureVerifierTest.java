package io.aster.security.internal;

import io.aster.policy.security.NonceService;
import io.aster.security.internal.InternalV2SignatureVerifier.Policy;
import io.aster.security.internal.InternalV2SignatureVerifier.Request;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 共用 v2 内部签名校验器：纯单元测试，NonceService 用手写桩。 */
class InternalV2SignatureVerifierTest {

    private static final String KEY = "unit-test-hmac-key";
    private static final String METHOD = "POST";
    private static final String PATH = "/api/internal/x";
    private static final String BODY = "{\"a\":1}";
    private static final Policy STRICT = new Policy("scope-x", false, "unit test");
    private static final Policy LEGACY = new Policy("scope-x", true, "unit test");

    /** 记录每次 ensureFresh 调用；可配置为抛重放异常。 */
    static class StubNonceService extends NonceService {
        final List<String[]> calls = new ArrayList<>();
        boolean replay;

        @Override
        public void ensureFresh(String tenantId, String nonce, String requestHash) {
            calls.add(new String[] {tenantId, nonce, requestHash});
            if (replay) {
                throw new WebApplicationException("Nonce replay detected", 409);
            }
        }
    }

    private StubNonceService nonces;
    private InternalV2SignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        nonces = new StubNonceService();
        verifier = new InternalV2SignatureVerifier();
        verifier.hmacKey = Optional.of(KEY);
        verifier.nonceService = nonces;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String v2Sig(long ts, String nonce, String body) {
        return InternalV2SignatureVerifier.sign(KEY, InternalV2SignatureVerifier.canonicalV2(
            METHOD, PATH, ts, nonce, InternalV2SignatureVerifier.sha256Hex(body)));
    }

    private static Request req(String ts, String sig, String nonce, String body) {
        return new Request(METHOD, PATH, ts, sig, nonce, body);
    }

    private static void assertRejected(Optional<Response> r, int status, String bodyContains) {
        assertTrue(r.isPresent(), "应被拒绝");
        assertEquals(status, r.get().getStatus());
        assertTrue(String.valueOf(r.get().getEntity()).contains(bodyContains),
            "body=" + r.get().getEntity());
    }

    @Test
    void missingKeyIs403() {
        long ts = now();
        for (Optional<String> k : List.of(Optional.<String>empty(), Optional.of("  "))) {
            verifier.hmacKey = k;
            assertRejected(verifier.verify(req(String.valueOf(ts), "s", "n", BODY), STRICT),
                403, "{\"error\":\"hmac_not_configured\"}");
        }
        assertTrue(nonces.calls.isEmpty());
    }

    @Test
    void missingHeadersIs401() {
        assertRejected(verifier.verify(req(null, "s", "n", BODY), STRICT), 401, "missing signature headers");
        assertRejected(verifier.verify(req("1", null, "n", BODY), STRICT), 401, "missing signature headers");
    }

    @Test
    void badTimestampIs401() {
        assertRejected(verifier.verify(req("abc", "s", "n", BODY), STRICT), 401, "invalid timestamp");
    }

    @Test
    void staleTimestampIs401() {
        long ts = now() - 301;
        assertRejected(verifier.verify(req(String.valueOf(ts), v2Sig(ts, "n", BODY), "n", BODY), STRICT),
            401, "stale timestamp");
    }

    @Test
    void validV2Passes() {
        long ts = now();
        Optional<Response> r = verifier.verify(
            req(String.valueOf(ts), v2Sig(ts, "n1", BODY), "n1", BODY), STRICT);
        assertTrue(r.isEmpty());
        assertEquals(1, nonces.calls.size());
        assertArrayEquals(
            new String[] {"scope-x", "n1", InternalV2SignatureVerifier.sha256Hex(BODY)}, nonces.calls.get(0));
    }

    @Test
    void tamperedBodyIs401() {
        long ts = now();
        String sig = v2Sig(ts, "n1", BODY);
        assertRejected(verifier.verify(req(String.valueOf(ts), sig, "n1", "{\"a\":2}"), STRICT),
            401, "{\"error\":\"invalid_signature\"}");
        assertTrue(nonces.calls.isEmpty(), "先验签后消费 nonce");
    }

    @Test
    void replayIs409() {
        long ts = now();
        String sig = v2Sig(ts, "n1", BODY);
        assertTrue(verifier.verify(req(String.valueOf(ts), sig, "n1", BODY), STRICT).isEmpty());
        nonces.replay = true;
        assertRejected(verifier.verify(req(String.valueOf(ts), sig, "n1", BODY), STRICT),
            409, "{\"error\":\"nonce_replay\"}");
    }

    @Test
    void v1RejectedWhenLegacyOff() {
        long ts = now();
        String sig = InternalV2SignatureVerifier.sign(KEY, InternalV2SignatureVerifier.canonicalV1(METHOD, PATH, ts));
        assertRejected(verifier.verify(req(String.valueOf(ts), sig, null, BODY), STRICT),
            401, "{\"error\":\"missing_nonce\",\"detail\":\"v2 signature required\"}");
    }

    @Test
    void v1AcceptedWhenLegacyOn() {
        long ts = now();
        String sig = InternalV2SignatureVerifier.sign(KEY, InternalV2SignatureVerifier.canonicalV1(METHOD, PATH, ts));
        assertTrue(verifier.verify(req(String.valueOf(ts), sig, null, BODY), LEGACY).isEmpty());
        assertTrue(nonces.calls.isEmpty());
    }

    @Test
    void canonicalIsFiveLines() {
        String c = InternalV2SignatureVerifier.canonicalV2("POST", "/p", 123L, "n", "sha");
        assertEquals(5, c.split("\n", -1).length);
        assertEquals("POST\n/p\n123\nn\nsha", c);
    }
}
