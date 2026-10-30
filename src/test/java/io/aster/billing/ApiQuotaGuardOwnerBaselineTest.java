package io.aster.billing;

import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.aster.billing.snapshot.LocalQuotaSnapshotService.LocalQuota;
import io.aster.billing.snapshot.UserSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快速路径按 owner 池合并用量裁决（#412）：cloud REST 调用写入 owner 池的用量不经过本地 Redis 计数器，
 * 快照携带 cloud 统计的当月合并用量作为基线，裁决取 max(本地计数, 同账期基线)。
 */
class ApiQuotaGuardOwnerBaselineTest {

    private static final String PERIOD = LocalQuotaSnapshotService.currentPeriod();
    private static final String PREVIOUS_PERIOD = YearMonth.parse(PERIOD).minusMonths(1).toString();

    private ApiQuotaGuard guard;
    private LocalQuotaSnapshotService snapshot;

    @BeforeEach
    void setUp() {
        PlanGateConfig config = mock(PlanGateConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.cloudInternalUrl()).thenReturn("http://cloud.internal:3000");
        snapshot = mock(LocalQuotaSnapshotService.class);
        guard = new ApiQuotaGuard();
        guard.config = config;
        guard.snapshot = snapshot;
    }

    private void owner(Long baseline, String period, long localUsed) {
        when(snapshot.peekOwner("owner-1")).thenReturn(Optional.of(new LocalQuota(
            new UserSnapshot("owner-1", "pro", 1000L, "active", null, null, baseline, period), localUsed)));
    }

    @Test
    void cloudBaselineAboveHardCap_rejectsEvenWhenLocalCounterIsLow() {
        owner(2500L, PERIOD, 100L);

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(r.used()).isEqualTo(2500L);
    }

    @Test
    void cloudBaselineInSoftZone_warns() {
        owner(1200L, PERIOD, 10L);

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(r.used()).isEqualTo(1200L);
        assertThat(r.warning()).isEqualTo("soft-overage");
    }

    @Test
    void localCounterAheadOfStaleBaseline_usesLocalCounter() {
        owner(500L, PERIOD, 2100L);

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(r.used()).isEqualTo(2100L);
    }

    @Test
    void baselineFromPreviousPeriod_isIgnoredAfterMonthRollover() {
        owner(5000L, PREVIOUS_PERIOD, 3L);

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(r.used()).isEqualTo(3L);
    }

    @Test
    void snapshotWithoutBaseline_behavesAsBefore() {
        owner(null, null, 42L);

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(r.used()).isEqualTo(42L);
    }

    @Test
    void precheckWarmup_snapshotCarriesOwnerPoolUsageForCurrentPeriod() {
        UserSnapshot s = ApiQuotaGuard.snapshotFromPrecheck("owner-1", "pro", 1000L, 1800L, null);

        assertThat(s.userId()).isEqualTo("owner-1");
        assertThat(s.apiCallsLimit()).isEqualTo(1000L);
        assertThat(s.monthlyUsedBaseline()).isEqualTo(1800L);
        assertThat(s.baselinePeriod()).isEqualTo(PERIOD);
        assertThat(s.effectiveUsed(0L, PERIOD)).isEqualTo(1800L);
    }

    @Test
    void precheckWarmup_usesPeriodReportedByCloud() {
        // 月末发出、月初回包：用量属于 cloud 统计时的账期，不能标成本服务的新月
        UserSnapshot s = ApiQuotaGuard.snapshotFromPrecheck("owner-1", "pro", 1000L, 1800L, PREVIOUS_PERIOD);

        assertThat(s.baselinePeriod()).isEqualTo(PREVIOUS_PERIOD);
        assertThat(s.effectiveUsed(3L, PERIOD)).isEqualTo(3L);
    }

    @Test
    void periodHelperMatchesUtcYearMonth() {
        assertThat(PERIOD).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
    }
}
