package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.aster.billing.snapshot.LocalQuotaSnapshotService.LocalQuota;
import io.aster.billing.snapshot.UserSnapshot;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额键归属（ADR 0015 §3-§4）：快照与本地计数器一律按 quotaOwnerId 取键。
 * 团队 key 的成员调用必须读写 team owner 的池，不能落到成员自己的计数器上；
 * cloud 上报体必须把 owner 放进 quotaOwnerId、把成员放进 userId，两者不得互换。
 *
 * <p>纯 JUnit：直接装配包可见的注入字段；上报出口 {@code postUsage} 打桩为空操作并捕获上报体，
 * 不发真实 HTTP，也不依赖异常路径。
 */
class ApiQuotaGuardQuotaOwnerKeyTest {

    private ApiQuotaGuard guard;
    private LocalQuotaSnapshotService snapshot;

    @BeforeEach
    void setUp() {
        PlanGateConfig config = mock(PlanGateConfig.class);
        when(config.enabled()).thenReturn(true);
        // 合法的 cloud 地址：任何出站路径都不会落进 URI.create(null) 的异常分支
        when(config.cloudInternalUrl()).thenReturn("http://cloud.internal:3000");
        snapshot = mock(LocalQuotaSnapshotService.class);
        when(snapshot.incrementCounter(anyString())).thenReturn(Uni.createFrom().item(1L));
        guard = spy(new ApiQuotaGuard());
        doNothing().when(guard).postUsage(any());
        guard.config = config;
        guard.snapshot = snapshot;
    }

    private JsonObject capturedUsageBody() {
        ArgumentCaptor<JsonObject> body = ArgumentCaptor.forClass(JsonObject.class);
        verify(guard).postUsage(body.capture());
        return body.getValue();
    }

    @Test
    void check_readsOwnerSnapshotAndCounter_notMember() {
        when(snapshot.peekOwner("owner-1")).thenReturn(Optional.of(new LocalQuota(
            new UserSnapshot("owner-1", "team", 1000L, "active", null, null), 2000L)));

        ApiQuotaGuard.GuardResult r = guard.check("team-1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(r.used()).isEqualTo(2000L);
        verify(snapshot, never()).peekOwner("member-1");
    }

    @Test
    void recordAsync_incrementsOwnerCounter_forTeamKey() {
        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/guard/decisions", "success", 5L);

        verify(snapshot).incrementCounter("owner-1");
        verify(snapshot, never()).incrementCounter("member-1");
    }

    @Test
    void recordAsync_teamKey_postsOwnerAsQuotaOwner_andMemberAsUser() {
        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/guard/decisions", "success", 5L);

        JsonObject body = capturedUsageBody();
        assertThat(body.getString("quotaOwnerId")).isEqualTo("owner-1");
        assertThat(body.getString("userId")).isEqualTo("member-1");
        assertThat(body.getString("tenantId")).isEqualTo("team-1");
        assertThat(body.getString("apiKeyId")).isEqualTo("key-1");
        assertThat(body.getString("endpointPath")).isEqualTo("/api/v1/guard/decisions");
        assertThat(body.getString("status")).isEqualTo("success");
        assertThat(body.getLong("latencyMs")).isEqualTo(5L);
    }

    @Test
    void recordAsync_personalKey_postsQuotaOwnerEqualToUser() {
        guard.recordAsync("u1", "u1", "u1", "key-1", "/api/v1/policies/evaluate", "success", 3L);

        JsonObject body = capturedUsageBody();
        assertThat(body.getString("quotaOwnerId")).isEqualTo("u1");
        assertThat(body.getString("userId")).isEqualTo("u1");
        verify(snapshot).incrementCounter("u1");
    }

    @Test
    void recordAsync_fallsBackToUserId_whenQuotaOwnerMissing() {
        guard.recordAsync(null, "u1", "u1", "key-1", "/api/v1/policies/evaluate", "success", 5L);

        verify(snapshot).incrementCounter("u1");
        assertThat(capturedUsageBody().getString("userId")).isEqualTo("u1");
    }

    @Test
    void recordAsync_doesNotCountFailures_butStillReportsThem() {
        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/policies/evaluate", "api_error", 5L);

        verify(snapshot, never()).incrementCounter(anyString());
        JsonObject body = capturedUsageBody();
        assertThat(body.getString("status")).isEqualTo("api_error");
        assertThat(body.getString("quotaOwnerId")).isEqualTo("owner-1");
        assertThat(body.getString("userId")).isEqualTo("member-1");
    }

    @Test
    void recordAsync_planGateDisabled_neitherCountsNorReports() {
        when(guard.config.enabled()).thenReturn(false);

        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/policies/evaluate", "success", 5L);

        verify(snapshot, never()).incrementCounter(anyString());
        verify(guard, never()).postUsage(any());
    }
}
