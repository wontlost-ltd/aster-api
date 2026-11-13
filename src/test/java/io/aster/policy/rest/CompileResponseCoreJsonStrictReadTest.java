package io.aster.policy.rest;

import io.aster.policy.api.CoreJsonStrictReadFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0055 §5：编译响应从 {@code core_json} 抽取模块信息、档案与 {@code inputRefs} 时经
 * {@code CoreIrJson.readTree}；非 v2 输入不被采信（得到 MissingNode，下游提取一律为空）。
 */
class CompileResponseCoreJsonStrictReadTest {

    @Test
    void parseCoreJson_accepts_v2_and_rejects_everything_else() throws Exception {
        assertThat(PolicyEvaluationResource.parseCoreJson(CoreJsonStrictReadFixtures.v2Ir()).path("schemaVersion").asInt())
            .isEqualTo(2);
        for (String ir : CoreJsonStrictReadFixtures.invalidIrs()) {
            assertThat(PolicyEvaluationResource.parseCoreJson(ir).isMissingNode()).as(ir).isTrue();
        }
    }
}
