package io.aster.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权益判定映射：区分「确认无权益」与「无法确认」。
 * 无法确认必须抛 {@link PlanLookupUnavailableException}（→ 503），不得落成 free（→ 403）。
 */
class PlanGateEntitlementMappingTest {

    @Test
    void pro档200可解析为有权益() {
        PlanInfo p = PlanGateService.interpretEntitlementResponse(200,
            "{\"plan\":\"pro\",\"concurrentReplayBatches\":1}");
        assertThat(p.allowsReplayBatch()).isTrue();
    }

    @Test
    void free档200为确认无权益() {
        PlanInfo p = PlanGateService.interpretEntitlementResponse(200, "{\"plan\":\"free\"}");
        assertThat(p.allowsReplayBatch()).isFalse();
    }

    @Test
    void 租户404为确认无权益() {
        PlanInfo p = PlanGateService.interpretEntitlementResponse(404, "");
        assertThat(p.isFreePlan()).isTrue();
        assertThat(p.allowsReplayBatch()).isFalse();
    }

    @Test
    void 服务端5xx为不可用() {
        assertThatThrownBy(() -> PlanGateService.interpretEntitlementResponse(503, "x"))
            .isInstanceOf(PlanLookupUnavailableException.class);
        assertThatThrownBy(() -> PlanGateService.interpretEntitlementResponse(500, ""))
            .isInstanceOf(PlanLookupUnavailableException.class);
    }

    @Test
    void 签名失败401等非2xx为不可用() {
        assertThatThrownBy(() -> PlanGateService.interpretEntitlementResponse(401, ""))
            .isInstanceOf(PlanLookupUnavailableException.class);
    }

    @Test
    void 响应体不可解析为不可用() {
        assertThatThrownBy(() -> PlanGateService.interpretEntitlementResponse(200, "<html>"))
            .isInstanceOf(PlanLookupUnavailableException.class);
    }
}
