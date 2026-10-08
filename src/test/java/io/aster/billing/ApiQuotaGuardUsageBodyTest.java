package io.aster.billing;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * usage 上报体（ADR 0015 §4）：cloud 按 {@code quotaOwnerId} 把调用计入 owner 共享池，
 * {@code userId} 仍是实际调用成员，两者必须分别携带、不得互相顶替。
 */
class ApiQuotaGuardUsageBodyTest {

    @Test
    void usageBody_carriesQuotaOwnerAndUser() {
        JsonObject b = ApiQuotaGuard.usageBody("owner-1", "member-1", "team-1", "key-1", "/api/v1/guard/decisions", "success", 12L);
        assertThat(b.getString("quotaOwnerId")).isEqualTo("owner-1");
        assertThat(b.getString("userId")).isEqualTo("member-1");
        assertThat(b.getString("tenantId")).isEqualTo("team-1");
        assertThat(b.getString("apiKeyId")).isEqualTo("key-1");
        assertThat(b.getString("endpointPath")).isEqualTo("/api/v1/guard/decisions");
        assertThat(b.getString("status")).isEqualTo("success");
        assertThat(b.getLong("latencyMs")).isEqualTo(12L);
    }

    @Test
    void usageBody_personalKey_quotaOwnerEqualsUser_andNoExtraFields() {
        // 个人 key：quotaOwnerId == userId；字段集合只比旧上报体多出 quotaOwnerId 一项
        JsonObject b = ApiQuotaGuard.usageBody("u1", "u1", "u1", "key-1", "/api/v1/policies/evaluate", "api_error", 3L);
        assertThat(b.getString("quotaOwnerId")).isEqualTo(b.getString("userId"));
        assertThat(b.fieldNames()).containsExactlyInAnyOrder(
            "quotaOwnerId", "userId", "tenantId", "apiKeyId", "endpointPath", "status", "latencyMs");
    }
}
