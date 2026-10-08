package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.aster.billing.snapshot.UserSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额键归属（ADR 0015 §3-§4）：快照与本地计数器一律按 quotaOwnerId 取键。
 * 团队 key 的成员调用必须读写 team owner 的池，不能落到成员自己的计数器上。
 *
 * <p>纯 JUnit：直接装配包可见的注入字段；cloud 上报段因 cloudInternalUrl 为空在 try 内失败并被吞掉，
 * 不影响本地计数断言。
 */
class ApiQuotaGuardQuotaOwnerKeyTest {

    private ApiQuotaGuard guard;
    private LocalQuotaSnapshotService snapshot;

    @BeforeEach
    void setUp() {
        PlanGateConfig config = mock(PlanGateConfig.class);
        when(config.enabled()).thenReturn(true);
        snapshot = mock(LocalQuotaSnapshotService.class);
        guard = new ApiQuotaGuard();
        guard.config = config;
        guard.snapshot = snapshot;
    }

    @Test
    void check_readsOwnerSnapshotAndCounter_notMember() {
        when(snapshot.getUser("owner-1")).thenReturn(Optional.of(
            new UserSnapshot("owner-1", "team", 1000L, "active", null, null)));
        when(snapshot.getCounter("owner-1")).thenReturn(2000L);

        ApiQuotaGuard.GuardResult r = guard.check("team-1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(r.used()).isEqualTo(2000L);
        verify(snapshot, never()).getUser("member-1");
        verify(snapshot, never()).getCounter("member-1");
    }

    @Test
    void recordAsync_incrementsOwnerCounter_forTeamKey() {
        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/guard/decisions", "success", 5L);

        verify(snapshot).incrementCounter("owner-1");
        verify(snapshot, never()).incrementCounter("member-1");
    }

    @Test
    void recordAsync_fallsBackToUserId_whenQuotaOwnerMissing() {
        guard.recordAsync(null, "u1", "u1", "key-1", "/api/v1/policies/evaluate", "success", 5L);

        verify(snapshot).incrementCounter("u1");
    }

    @Test
    void recordAsync_doesNotCountFailures() {
        guard.recordAsync("owner-1", "member-1", "team-1", "key-1", "/api/v1/policies/evaluate", "api_error", 5L);

        verify(snapshot, never()).incrementCounter(anyString());
    }
}
