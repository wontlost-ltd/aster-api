package io.aster.security.apikey;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v1/policies/compile 的结构词别名信任边界（aster-cloud#604）端到端：
 * 只有「HMAC 已验签 + allowStructural=true」才按已授权编译结构词别名；
 * 未签名调用方传 allowStructural=true 被忽略，带内部头但签名无效直接 403。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
class CompileStructuralAliasHmacTest {

    /** 打开 HMAC 密钥，关闭全局签名与 evaluate-source 旁路，贴近生产。 */
    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false");
        }
    }

    static final String HMAC_KEY = "compile-structural-604-key-32bytes!";
    static final String PATH = "/api/v1/policies/compile";
    static final String TENANT = "tenant-compile-604";

    /** 与 cloud 保存门禁同形：IF 结构词别名 + 依赖它的源码。 */
    static final String SOURCE =
        "Module M.\\n\\nRule p given age as Int, produce Bool:\\n"
        + "  provided that age at least 18:\\n    Return true.\\n  Return false.";

    static String body(Boolean allowStructural) {
        String flag = allowStructural == null ? "" : ",\"allowStructural\":" + allowStructural;
        return "{\"source\":\"" + SOURCE + "\",\"locale\":\"en-US\","
            + "\"aliasSet\":{\"IF\":[\"provided that\"]}" + flag + "}";
    }

    static Map<String, String> signedHeaders(String body, String key) {
        return InternalCallerSigning.headers(key, "POST", PATH, "", body,
            TENANT, "member", "user-604", List.of());
    }

    static Response post(String body, Map<String, String> headers) {
        return given().contentType(ContentType.JSON).headers(headers).body(body).post(PATH);
    }

    @Test
    void 已验签且请求授权_结构词别名编译通过() {
        String b = body(true);
        Response r = post(b, signedHeaders(b, HMAC_KEY));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).as(r.asString()).isTrue();
    }

    @Test
    void 已验签但未请求授权_结构词别名仍被拒() {
        String b = body(null);
        Response r = post(b, signedHeaders(b, HMAC_KEY));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).isFalse();
        assertThat(r.asString()).contains("IF");
    }

    @Test
    void 未签名调用方传allowStructural被忽略_结构词别名被拒() {
        Response r = post(body(true), Map.of("X-Tenant-Id", TENANT));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getBoolean("success")).isFalse();
        assertThat(r.asString()).contains("IF");
    }

    @Test
    void 带内部头但签名无效_直接403不编译() {
        String b = body(true);
        Response r = post(b, signedHeaders(b, "wrong-key-wrong-key-wrong-key-32b!"));
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
    }

    @Test
    void 签名覆盖body_篡改allowStructural即403() {
        // 先对不授权的 body 签名，再把 allowStructural 改成 true 发送：签名不再匹配
        Map<String, String> h = new HashMap<>(signedHeaders(body(false), HMAC_KEY));
        Response r = post(body(true), h);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
    }

    @Test
    void 无别名的普通编译行为不变() {
        String b = "{\"source\":\"Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times 3.\","
            + "\"locale\":\"en-US\"}";
        Response anon = post(b, Map.of("X-Tenant-Id", TENANT));
        assertThat(anon.statusCode()).as(anon.asString()).isEqualTo(200);
        assertThat(anon.jsonPath().getBoolean("success")).isTrue();
        Response signed = post(b, signedHeaders(b, HMAC_KEY));
        assertThat(signed.statusCode()).as(signed.asString()).isEqualTo(200);
        assertThat(signed.jsonPath().getBoolean("success")).isTrue();
    }
}
