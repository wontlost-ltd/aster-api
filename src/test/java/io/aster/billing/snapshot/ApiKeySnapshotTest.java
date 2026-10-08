package io.aster.billing.snapshot;

import io.aster.common.JacksonMappers;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        ApiKeySnapshot s = new ApiKeySnapshot(false, "revoked", "k1", "u1", "tenant-a", "pro", "owner", ts, null, List.of());
        assertThat(s.revoked()).isTrue();
    }

    @Test
    void revoked_returnsFalseWhenNull() {
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null, null, List.of());
        assertThat(s.revoked()).isFalse();
    }

    @Test
    void tenantId_isPreserved() {
        // 租户隔离回归：snapshot 必须保留 key 所属租户，不能丢失或被 userId 顶替。
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null, null, List.of());
        assertThat(s.tenantId()).isEqualTo("tenant-a");
        assertThat(s.userId()).isEqualTo("u1");
        assertThat(s.role()).isEqualTo("owner");
    }

    @Test
    void quotaOwnerId_isPreservedForTeamKey() {
        // 团队 key：配额记到 team owner 而非调用成员（ADR 0015 §3）。
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1", List.of());
        assertThat(s.quotaOwnerId()).isEqualTo("owner-1");
        assertThat(s.effectiveQuotaOwnerId()).isEqualTo("owner-1");
    }

    @Test
    void effectiveQuotaOwnerId_fallsBackToUserIdWhenMissing() {
        // 旧 cloud / 旧 snapshot 不带 quotaOwnerId → 回退 userId，个人 key 行为不变。
        assertThat(new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null, List.of())
            .effectiveQuotaOwnerId()).isEqualTo("u1");
        assertThat(new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, " ", List.of())
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
        ApiKeySnapshot in = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1", List.of());
        String json = JacksonMappers.DEFAULT.writeValueAsString(in);
        assertThat(json).doesNotContain("effectiveQuotaOwnerId");
        assertThat(JacksonMappers.DEFAULT.readValue(json, ApiKeySnapshot.class)).isEqualTo(in);
    }

    /** 升级前 pod 的 ApiKeySnapshot 形状（无 quotaOwnerId），用于验证新旧 pod 共享 Redis 时旧 pod 仍可读。 */
    record LegacyApiKeySnapshot(boolean valid, String reason, String apiKeyId, String userId, String tenantId,
                                String plan, String role, Long revokedAtEpochMs) {}

    @Test
    void redisJson_nullQuotaOwnerId_isOmitted_byteIdenticalToLegacyPod() throws Exception {
        // 旧 cloud 的 key（quotaOwnerId 缺省）由新 pod 写入 Redis：JSON 必须与升级前 pod 写出的逐字节相同，
        // 否则旧 pod（vanilla mapper，未知字段即失败）读到 "quotaOwnerId":null 会把每次命中都当成 miss。
        ApiKeySnapshot in = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null, List.of());
        String json = JacksonMappers.DEFAULT.writeValueAsString(in);
        String legacyPodJson = JacksonMappers.DEFAULT.writeValueAsString(
            new LegacyApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null));

        assertThat(json).doesNotContain("quotaOwnerId").isEqualTo(legacyPodJson);
        assertThat(JacksonMappers.DEFAULT.readValue(json, LegacyApiKeySnapshot.class).userId()).isEqualTo("u1");
        assertThat(JacksonMappers.DEFAULT.readValue(json, ApiKeySnapshot.class)).isEqualTo(in);
    }

    @Test
    void redisJson_presentQuotaOwnerId_isWritten() throws Exception {
        ApiKeySnapshot in = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1", List.of());
        String json = JacksonMappers.DEFAULT.writeValueAsString(in);

        assertThat(json).contains("\"quotaOwnerId\":\"owner-1\"");
        assertThat(JacksonMappers.DEFAULT.readValue(json, ApiKeySnapshot.class)).isEqualTo(in);
    }

    @Test
    void personalKey_quotaOwnerEqualToUserId_isNormalized_andRedisJsonMatchesLegacyPod() throws Exception {
        // 新 cloud 对个人 key 也下发 quotaOwnerId=userId：归一为 null 后写入 Redis 的 JSON 与升级前逐字节相同，
        // 旧 pod / 回滚 pod 仍能读取个人 key 快照。
        ApiKeySnapshot personal = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, "u1", List.of());
        String json = JacksonMappers.DEFAULT.writeValueAsString(personal);
        String legacyPodJson = JacksonMappers.DEFAULT.writeValueAsString(
            new LegacyApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null));

        assertThat(personal.quotaOwnerId()).isNull();
        assertThat(personal.effectiveQuotaOwnerId()).isEqualTo("u1");
        assertThat(json).doesNotContain("\"quotaOwnerId\"").isEqualTo(legacyPodJson);

        ApiKeySnapshot team = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner", List.of());
        assertThat(team.effectiveQuotaOwnerId()).isEqualTo("owner");
        assertThat(JacksonMappers.DEFAULT.writeValueAsString(team)).contains("\"quotaOwnerId\":\"owner\"");
    }

    @Test
    void blankOrPaddedQuotaOwnerId_isNormalized_effectiveOwnerUnchanged() {
        ApiKeySnapshot blank = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, "  ", List.of());
        assertThat(blank.quotaOwnerId()).isNull();
        assertThat(blank.effectiveQuotaOwnerId()).isEqualTo("u1");

        ApiKeySnapshot paddedSelf = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, " u1 ", List.of());
        assertThat(paddedSelf.quotaOwnerId()).isNull();
        assertThat(paddedSelf.effectiveQuotaOwnerId()).isEqualTo("u1");

        ApiKeySnapshot paddedOwner = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, " owner-1 ", List.of());
        assertThat(paddedOwner.quotaOwnerId()).isEqualTo("owner-1");
        assertThat(paddedOwner.effectiveQuotaOwnerId()).isEqualTo("owner-1");
    }

    @Test
    void fromJson_validTeamKey_mapsAllFields() {
        JsonObject json = new JsonObject()
            .put("valid", true).put("apiKeyId", "k1").put("userId", "member-1").put("tenantId", "team-1")
            .put("plan", "team").put("role", "member").put("revokedAtEpochMs", 42L).put("quotaOwnerId", "owner-1");

        assertThat(ApiKeySnapshot.fromJson(json)).isEqualTo(
            new ApiKeySnapshot(true, null, "k1", "member-1", "team-1", "team", "member", 42L, "owner-1", List.of()));
    }

    @Test
    void fromJson_validWithoutQuotaOwnerId_fallsBackToUserId() {
        // 旧 cloud 推送 / 全量同步不带 quotaOwnerId
        JsonObject json = new JsonObject()
            .put("valid", true).put("apiKeyId", "k1").put("userId", "u1").put("tenantId", "u1")
            .put("plan", "pro").put("role", "owner");

        ApiKeySnapshot s = ApiKeySnapshot.fromJson(json);

        assertThat(s).isEqualTo(new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null, List.of()));
        assertThat(s.effectiveQuotaOwnerId()).isEqualTo("u1");
    }

    @Test
    void fromJson_validPersonalKeyWithQuotaOwnerEqualToUser_isNormalized() {
        JsonObject json = new JsonObject()
            .put("valid", true).put("apiKeyId", "k1").put("userId", "u1").put("tenantId", "u1")
            .put("plan", "pro").put("role", "owner").put("quotaOwnerId", "u1");

        assertThat(ApiKeySnapshot.fromJson(json).quotaOwnerId()).isNull();
    }

    @Test
    void fromJson_invalid_keepsReason_orDefaultsToInvalid_andIgnoresIdentityFields() {
        JsonObject revoked = new JsonObject().put("valid", false).put("reason", "revoked")
            .put("userId", "u1").put("quotaOwnerId", "owner-1");
        assertThat(ApiKeySnapshot.fromJson(revoked)).isEqualTo(ApiKeySnapshot.invalid("revoked"));

        assertThat(ApiKeySnapshot.fromJson(new JsonObject().put("valid", false)))
            .isEqualTo(ApiKeySnapshot.invalid("invalid"));
        assertThat(ApiKeySnapshot.fromJson(new JsonObject()))
            .isEqualTo(ApiKeySnapshot.invalid("invalid"));
    }

    @Test
    void fromJson_businessRoles_readAsStringList_orEmptyWhenAbsent() {
        // ADR 0042 §2.2：cloud 下发团队 key 的业务角色集合；非字符串元素跳过，缺省为空。
        JsonObject withRoles = new JsonObject()
            .put("valid", true).put("apiKeyId", "k1").put("userId", "member-1").put("tenantId", "team-1")
            .put("plan", "team").put("role", "member")
            .put("businessRoles", new JsonArray().add("DPO").add(7).add(" CISO "));
        assertThat(ApiKeySnapshot.fromJson(withRoles).businessRoles()).containsExactly("DPO", "CISO");

        JsonObject without = new JsonObject()
            .put("valid", true).put("apiKeyId", "k1").put("userId", "u1").put("tenantId", "u1")
            .put("plan", "pro").put("role", "owner");
        assertThat(ApiKeySnapshot.fromJson(without).businessRoles()).isEmpty();
    }

    @Test
    void businessRoles_nullNormalizedToImmutableEmpty_blanksDropped() {
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null, null);
        assertThat(s.businessRoles()).isEmpty();
        ApiKeySnapshot t = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1",
            Arrays.asList(" DPO ", null, " "));
        assertThat(t.businessRoles()).containsExactly("DPO");
        assertThatThrownBy(() -> t.businessRoles().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void redisJson_emptyBusinessRoles_isOmitted_byteIdenticalToLegacyPod_presentRolesRoundTrip() throws Exception {
        // 个人 key（无业务角色）写入 Redis 的 JSON 不含 businessRoles 键，与旧 pod 逐字节相同。
        ApiKeySnapshot personal = new ApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null, null, List.of());
        String json = JacksonMappers.DEFAULT.writeValueAsString(personal);
        String legacyPodJson = JacksonMappers.DEFAULT.writeValueAsString(
            new LegacyApiKeySnapshot(true, null, "k1", "u1", "u1", "pro", "owner", null));
        assertThat(json).doesNotContain("businessRoles").isEqualTo(legacyPodJson);

        ApiKeySnapshot team = new ApiKeySnapshot(true, null, "k1", "u1", "team-1", "team", "member", null, "owner-1",
            List.of("Data Protection Officer"));
        String teamJson = JacksonMappers.DEFAULT.writeValueAsString(team);
        assertThat(teamJson).contains("\"businessRoles\":[\"Data Protection Officer\"]");
        assertThat(JacksonMappers.DEFAULT.readValue(teamJson, ApiKeySnapshot.class)).isEqualTo(team);
    }
}
