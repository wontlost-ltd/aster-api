package io.aster.policy.cedar;

import aster.core.ir.CoreIrFormatException;
import io.aster.policy.api.CoreJsonStrictReadFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR 0055 §5：Cedar 导出读 {@code core_json} 经 {@code CoreIrJson.read}，非 v2 输入拒收。 */
class CedarCoreJsonStrictReadTest {

    @Test
    void readModule_accepts_v2_and_rejects_everything_else() throws Exception {
        assertThat(CedarExportService.readModule(CoreJsonStrictReadFixtures.v2Ir()).name).isEqualTo("strict.read");
        for (String ir : CoreJsonStrictReadFixtures.invalidIrs()) {
            assertThatThrownBy(() -> CedarExportService.readModule(ir))
                .as(ir)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(CoreIrFormatException.class);
        }
    }
}
