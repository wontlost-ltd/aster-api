package io.aster.policy.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 未指定函数名时 E720 的原因文案：区分「没有可导出的规则」与「可导出的规则不止一条」。 */
class CedarExportReasonTest {

    @Test
    void noCandidateExplainsTheGuardShape() {
        assertThat(CedarExportService.functionRequiredReason(List.of()))
            .isEqualTo("no rule has the guard request shape");
    }

    @Test
    void severalCandidatesAreListedSoTheCallerCanChoose() {
        assertThat(CedarExportService.functionRequiredReason(List.of("first", "second")))
            .isEqualTo("several rules can be exported (first, second); choose one");
    }
}
