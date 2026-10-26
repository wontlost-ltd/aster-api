package io.aster.security.apikey;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译端点在真实 filter 链上的 HMAC 行为（aster-cloud#604 复审）：
 * <ul>
 *   <li>RateLimitFilter 打开时，它先读 body 验签再重置实体流；InternalCallerFilter 需能再次读到
 *       同一 body 验签成功，结构词授权才可能生效。</li>
 *   <li>同一签名请求（同 nonce）原样重放被拒（409），nonce 只在 InternalCallerFilter 消费一次。</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileHmacRateLimitReplayTest.RateLimitProfile.class)
class CompileHmacRateLimitReplayTest {

    /** 打开限流：IP 桶额度 1，内部桶额度宽松——签名请求能连续通过即证明落入内部桶。 */
    public static class RateLimitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", CompileStructuralAliasHmacTest.HMAC_KEY,
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false",
                "aster.ratelimit.enabled", "true",
                "aster.ratelimit.rest.max-requests", "1",
                "aster.ratelimit.internal.max-requests", "1000");
        }
    }

    static final String PATH = CompileStructuralAliasHmacTest.PATH;

    static Map<String, String> sign(String body) {
        return InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY, "POST", PATH, "", body,
            "tenant-compile-rl", "member", "user-rl", List.of());
    }

    static Response post(String body, Map<String, String> headers) {
        return given().contentType(ContentType.JSON).headers(headers).body(body).post(PATH);
    }

    @Test
    void 经过限流filter后签名请求仍验签成功且结构词授权生效() {
        String body = CompileStructuralAliasHmacTest.body(true);
        // 连续多次：IP 桶额度只有 1，能全部通过说明限流 filter 验签成功并按内部桶计数
        for (int i = 0; i < 3; i++) {
            Response r = post(body, sign(body));
            assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
            // success=true 只有在 InternalCallerFilter 重读 body 验签通过后才可能出现
            assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isTrue();
        }
    }

    @Test
    void 同一签名请求原样重放被拒() {
        String body = CompileStructuralAliasHmacTest.body(true);
        Map<String, String> headers = sign(body);
        Response first = post(body, headers);
        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
        assertThat(first.jsonPath().getBoolean("success")).isTrue();
        Response replay = post(body, headers);
        assertThat(replay.statusCode()).as(replay.asString()).isEqualTo(409);
    }
}
