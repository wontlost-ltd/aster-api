package io.aster.billing;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.billing.snapshot.LocalQuotaSnapshotService;
import io.aster.billing.snapshot.LocalQuotaSnapshotService.LocalQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * precheck 的 Caffeine 缓存按账期校验：缓存里的 monthlyUsed 属于哪个账期就只在该账期内有效，
 * 跨月后视为未命中（走 fail-open 预热），不再沿用上月计数。
 */
class ApiQuotaGuardPrecheckPeriodTest {

    private static final String PERIOD = LocalQuotaSnapshotService.currentPeriod();
    private static final String PREVIOUS_PERIOD = YearMonth.parse(PERIOD).minusMonths(1).toString();

    private ApiQuotaGuard guard;

    @BeforeEach
    void setUp() {
        PlanGateConfig config = mock(PlanGateConfig.class);
        when(config.enabled()).thenReturn(true);
        LocalQuotaSnapshotService snapshot = mock(LocalQuotaSnapshotService.class);
        when(snapshot.peekOwner("owner-1")).thenReturn(Optional.<LocalQuota>empty());
        guard = new ApiQuotaGuard();
        guard.config = config;
        guard.snapshot = snapshot;
        guard.precheckCache = Caffeine.newBuilder().build();
    }

    @Test
    void cachedUsageFromCurrentPeriod_isEnforced() {
        guard.precheckCache.put("owner-1", new ApiQuotaGuard.PrecheckResult(1000L, 2500L, "pro", PERIOD));

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.RATE_LIMITED);
        assertThat(r.used()).isEqualTo(2500L);
    }

    @Test
    void cachedUsageFromPreviousPeriod_isTreatedAsMiss() {
        guard.precheckCache.put("owner-1", new ApiQuotaGuard.PrecheckResult(1000L, 2500L, "pro", PREVIOUS_PERIOD));

        ApiQuotaGuard.GuardResult r = guard.check("t1", "owner-1");

        assertThat(r.verdict()).isEqualTo(ApiQuotaGuard.Verdict.ALLOW);
        assertThat(r.used()).isZero();
    }

    @Test
    void cachedUsageWithoutPeriod_isTreatedAsMiss() {
        guard.precheckCache.put("owner-1", new ApiQuotaGuard.PrecheckResult(1000L, 2500L, "pro", null));

        assertThat(guard.check("t1", "owner-1").used()).isZero();
    }
}
