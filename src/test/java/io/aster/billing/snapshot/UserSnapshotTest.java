package io.aster.billing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSnapshotTest {

    @Test
    void apiAccessAllowed_freeReturnsFalse() {
        UserSnapshot s = new UserSnapshot("u1", "free", 0, null, null, null);
        assertThat(s.apiAccessAllowed()).isFalse();
    }

    @Test
    void apiAccessAllowed_proReturnsTrue() {
        UserSnapshot s = new UserSnapshot("u1", "pro", 5000, "active", null, null);
        assertThat(s.apiAccessAllowed()).isTrue();
    }

    @Test
    void unlimitedApi_onlyEnterprise() {
        assertThat(new UserSnapshot("u", "enterprise", -1, null, null, null).unlimitedApi()).isTrue();
        assertThat(new UserSnapshot("u", "pro", 5000, null, null, null).unlimitedApi()).isFalse();
    }

    @Test
    void banned_futureReturnsTrue() {
        long inFuture = System.currentTimeMillis() + 10_000;
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, inFuture, null);
        assertThat(s.banned(System.currentTimeMillis())).isTrue();
    }

    @Test
    void banned_pastReturnsFalse() {
        long inPast = System.currentTimeMillis() - 10_000;
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, inPast, null);
        assertThat(s.banned(System.currentTimeMillis())).isFalse();
    }

    @Test
    void banned_nullReturnsFalse() {
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, null, null);
        assertThat(s.banned(System.currentTimeMillis())).isFalse();
    }

    // ---------- owner 池用量基线（#412） ----------

    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    @Test
    void snapshotWithoutBaseline_serializesWithoutBaselineFields() throws Exception {
        // 滚动发布期间旧副本仍用严格 mapper 读 Redis：没有基线时 JSON 不得多出字段
        String json = MAPPER.writeValueAsString(new UserSnapshot("u", "pro", 5000, "active", null, null));
        assertThat(json).doesNotContain("monthlyUsedBaseline").doesNotContain("baselinePeriod");
    }

    @Test
    void legacyJson_parsesWithNullBaseline() throws Exception {
        String legacy = "{\"userId\":\"u\",\"plan\":\"pro\",\"apiCallsLimit\":5000,"
            + "\"subscriptionStatus\":\"active\",\"aiBannedUntilEpochMs\":null,\"gracePeriodEndsEpochMs\":null}";
        UserSnapshot s = MAPPER.readValue(legacy, UserSnapshot.class);
        assertThat(s.monthlyUsedBaseline()).isNull();
        assertThat(s.effectiveUsed(7L, "2026-10")).isEqualTo(7L);
    }

    @Test
    void baselineRoundTripsThroughJson() throws Exception {
        UserSnapshot s = new UserSnapshot("u", "pro", 5000, "active", null, null, 1234L, "2026-10");
        UserSnapshot back = MAPPER.readValue(MAPPER.writeValueAsString(s), UserSnapshot.class);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void effectiveUsed_takesMaxWithinSamePeriodOnly() {
        UserSnapshot s = new UserSnapshot("u", "pro", 5000, null, null, null, 300L, "2026-10");
        assertThat(s.effectiveUsed(100L, "2026-10")).isEqualTo(300L);
        assertThat(s.effectiveUsed(400L, "2026-10")).isEqualTo(400L);
        assertThat(s.effectiveUsed(100L, "2026-11")).isEqualTo(100L);
    }

    @Test
    void fromJson_readsOptionalOwnerPoolUsage() {
        JsonObject json = new JsonObject().put("plan", "team").put("apiCallsLimit", 1000L)
            .put("subscriptionStatus", "active").put("monthlyUsed", 900L).put("usagePeriod", "2026-10");
        UserSnapshot s = UserSnapshot.fromJson("owner-1", json, "2026-11");
        assertThat(s.userId()).isEqualTo("owner-1");
        assertThat(s.plan()).isEqualTo("team");
        assertThat(s.monthlyUsedBaseline()).isEqualTo(900L);
        assertThat(s.baselinePeriod()).isEqualTo("2026-10");
    }

    @Test
    void fromJson_monthlyUsedWithoutPeriod_defaultsToReceiptPeriod() {
        JsonObject json = new JsonObject().put("plan", "pro").put("apiCallsLimit", 1000L).put("monthlyUsed", 5L);
        assertThat(UserSnapshot.fromJson("u", json, "2026-10").baselinePeriod()).isEqualTo("2026-10");
    }

    @Test
    void fromJson_legacyPayloadHasNoBaseline() {
        JsonObject json = new JsonObject().put("plan", "pro").put("apiCallsLimit", 1000L);
        UserSnapshot s = UserSnapshot.fromJson("u", json, "2026-10");
        assertThat(s.monthlyUsedBaseline()).isNull();
        assertThat(s.baselinePeriod()).isNull();
        assertThat(s.plan()).isEqualTo("pro");
    }
}
