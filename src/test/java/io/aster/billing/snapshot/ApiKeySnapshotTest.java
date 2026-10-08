package io.aster.billing.snapshot;

import io.aster.common.JacksonMappers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySnapshotTest {

    @Test
    void invalid_factory_setsValidFalse() {
        ApiKeySnapshot s = ApiKeySnapshot.invalid("revoked");
        assertThat(s.valid()).isFalse();
        assertThat(s.reason()).isEqualTo("revoked");
        assertThat(s.userId()).isNull();
    }

    @Test
    void revoked_returnsTrueWhenSet() {
        long ts = System.currentTimeMillis();
        ApiKeySnapshot s = new ApiKeySnapshot(false, "revoked", "k1", "u1", "tenant-a", "pro", "owner", ts, null);
        assertThat(s.revoked()).isTrue();
    }

    @Test
    void revoked_returnsFalseWhenNull() {
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null, null);
        assertThat(s.revoked()).isFalse();
    }

    @Test
    void tenantId_isPreserved() {
        // 租户隔离回归：snapshot 必须保留 key 所属租户，不能丢失或被 userId 顶替。
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null, null);
        assertThat(s.tenantId()).isEqualTo("tenant-a");
        assertThat(s.userId()).isEqualTo("u1");
        assertThat(s.role()).isEqualTo("owner");
    }

    @Test
    void quotaOwnerId_isPreservedForTeamKey() {
        // 团队 key：配额记到 team owner 而非调用成员（ADR 0015 §3）。
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1");
        assertThat(s.quotaOwnerId()).isEqualTo("owner-1");
        assertThat(s.effectiveQuotaOwnerId()).isEqualTo("owner-1");
    }

    @Test
    void effectiveQuotaOwnerId_fallsBackToUserIdWhenMissing() {
        // 旧 cloud / 旧 snapshot 不带 quotaOwnerId → 回退 userId，个人 key 行为不变。
        assertThat(new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null)
            .effectiveQuotaOwnerId()).isEqualTo("u1");
        assertThat(new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, " ")
            .effectiveQuotaOwnerId()).isEqualTo("u1");
    }

    @Test
    void legacyRedisJson_withoutQuotaOwnerId_deserializesAndFallsBack() throws Exception {
        // Redis 里升级前写入的 snapshot 没有 quotaOwnerId 字段：必须仍能反序列化并回退 userId。
        String legacy = "{\"valid\":true,\"reason\":null,\"apiKeyId\":\"k1\",\"userId\":\"u1\","
            + "\"tenantId\":\"u1\",\"plan\":\"pro\",\"role\":\"owner\",\"revokedAtEpochMs\":null}";
        ApiKeySnapshot s = JacksonMappers.DEFAULT.readValue(legacy, ApiKeySnapshot.class);
        assertThat(s.quotaOwnerId()).isNull();
        assertThat(s.effectiveQuotaOwnerId()).isEqualTo("u1");
    }

    @Test
    void redisJson_roundTrip_keepsQuotaOwnerId() throws Exception {
        // 与 LocalQuotaSnapshotService 同一 mapper：写入再读回不丢 quotaOwnerId，
        // 且派生方法 effectiveQuotaOwnerId() 不会被当作属性写进 JSON。
        ApiKeySnapshot in = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1");
        String json = JacksonMappers.DEFAULT.writeValueAsString(in);
        assertThat(json).doesNotContain("effectiveQuotaOwnerId");
        assertThat(JacksonMappers.DEFAULT.readValue(json, ApiKeySnapshot.class)).isEqualTo(in);
    }
}
