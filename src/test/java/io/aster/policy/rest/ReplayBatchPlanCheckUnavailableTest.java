package io.aster.policy.rest;

import io.aster.billing.PlanGateService;
import io.aster.billing.PlanInfo;
import io.aster.billing.PlanLookupUnavailableException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 创建批次时 plan 查询不可用（超时/连接失败/5xx）→ 503 plan_check_unavailable，
 * 而非 403 whatif_not_entitled；两者都在落库前返回（不创建批次）。
 */
class ReplayBatchPlanCheckUnavailableTest {

    private ReplayBatchResource resource;
    private PlanGateService planGate;

    @BeforeEach
    void setUp() {
        resource = new ReplayBatchResource();
        planGate = mock(PlanGateService.class);
        RequestIdentityResolver identity = mock(RequestIdentityResolver.class);
        when(identity.tenantId()).thenReturn("t-pro");
        when(identity.performedBy()).thenReturn("u1");
        resource.planGateService = planGate;
        resource.identityResolver = identity;
    }

    @Test
    void 查询不可用返回503与重试头() {
        when(planGate.lookupPlanForEntitlement("t-pro"))
            .thenThrow(new PlanLookupUnavailableException("plan_lookup_failed"));

        Response r = resource.create("p1", null);

        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(r.getHeaderString("Retry-After")).isEqualTo("5");
        assertThat(r.getEntity()).isEqualTo(Map.of(
            "error", "plan_check_unavailable",
            "message", "权益校验暂不可用，请稍后重试"));
    }

    @Test
    void 确认free档仍返回403() {
        when(planGate.lookupPlanForEntitlement("t-pro"))
            .thenReturn(new PlanInfo("free", null, false, 1, 0L, 0L, 0));

        Response r = resource.create("p1", null);

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(((Map<?, ?>) r.getEntity()).get("error")).isEqualTo("whatif_not_entitled");
    }
}
