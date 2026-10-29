package io.aster.security.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.billing.ApiQuotaGuard;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Free 计划界面执行（生产 P0）：cloud 代登录会话经 HMAC 调 {@code /evaluate-source}，不带 API key。
 *
 * <p>月度 API 配额（「Free 不含 Policy Execution API」）只管 API key 调用；界面执行的
 * 「每月 1000 次执行」由 cloud {@code /api/policies/[id]/execute} 按 UsageRecord(type=execution) 计。
 * {@link ApiQuotaGuard} 被 mock 成 Free 快照的裁决（FORBIDDEN），以复现生产：cloud 推来 Free 快照后
 * 每次界面执行都被 403 {@code api_access_denied}。身份解析与 HMAC 验签走真实实现。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CompileStructuralAliasHmacTest.HmacProfile.class)
class EvaluateSourceInternalSessionQuotaTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String EVALUATE_SOURCE = "/api/v1/policies/evaluate-source";
    static final String EVALUATE = "/api/v1/policies/evaluate";
    static final String TENANT = "tenant-free-ui-exec";
    static final String USER = "user-free-ui-exec";
    static final String SOURCE = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x.";

    @InjectMock ApiQuotaGuard apiQuotaGuard;

    @BeforeEach
    void freeSnapshot() {
        when(apiQuotaGuard.check(anyString(), anyString()))
            .thenReturn(new ApiQuotaGuard.GuardResult(ApiQuotaGuard.Verdict.FORBIDDEN, 0L, 0L, 0, null));
        when(apiQuotaGuard.checkRateAsync(any(), any(), any()))
            .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(ApiQuotaGuard.RateCheck.UNCHECKED));
    }

    private static String body() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("context", Map.of("x", 7));
        body.put("locale", "en-US");
        body.put("functionName", "p");
        return MAPPER.writeValueAsString(body);
    }

    private static Map<String, String> signed(String json) {
        return InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY,
            "POST", EVALUATE_SOURCE, "", json, TENANT, "member", USER, List.of());
    }

    @Test
    void Free会话经HMAC执行不受API配额限制且不计入API用量() throws Exception {
        String json = body();
        Response r = given().contentType(ContentType.JSON).headers(signed(json)).body(json).post(EVALUATE_SOURCE);

        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getString("error")).as(r.asString()).isNull();
        assertThat(r.jsonPath().getInt("result")).as(r.asString()).isEqualTo(7);
        verify(apiQuotaGuard, never()).check(anyString(), anyString());
        verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void 签名被篡改的内部调用不能借此绕过() throws Exception {
        String json = body();
        Map<String, String> h = new HashMap<>(signed(json));
        h.put("X-Internal-Signature", "0".repeat(64));
        Response r = given().contentType(ContentType.JSON).headers(h).body(json).post(EVALUATE_SOURCE);

        assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
        assertThat(r.jsonPath().getString("reason")).isEqualTo("invalid_signature");
        verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void 未验签请求自带内部调用头仍按API调用受Free限制() throws Exception {
        // /evaluate 不在 InternalCallerFilter 管辖内，自带的 X-Internal-* 头不会换来 HMAC 章；
        // 测试配置关闭了 API key 过滤器，这里就是「无 key、无验签」的请求，必须仍走 API 配额门禁。
        String json = MAPPER.writeValueAsString(Map.of("policyModule", "free.ui.missing",
            "policyFunction", "p", "context", List.of(Map.of("x", 1))));
        Map<String, String> h = new HashMap<>(InternalCallerSigning.headers(CompileStructuralAliasHmacTest.HMAC_KEY,
            "POST", EVALUATE, "", json, TENANT, "member", USER, List.of()));
        Response r = given().contentType(ContentType.JSON).headers(h).body(json).post(EVALUATE);

        assertThat(r.statusCode()).as(r.asString()).isEqualTo(403);
        assertThat(r.jsonPath().getString("error")).isEqualTo("api_access_denied");
        verify(apiQuotaGuard, never()).recordAsync(any(), any(), any(), any(), any(), any(), anyLong());
    }
}
