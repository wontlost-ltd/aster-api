package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayOutcomeTest {

    @Test
    void 目标侧五值一一映射() {
        assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.APPROVED)).isEqualTo(ReplayOutcome.ALLOW);
        assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.DENIED)).isEqualTo(ReplayOutcome.DENY);
        assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.REQUIRE_APPROVAL)).isEqualTo(ReplayOutcome.REQUIRE_APPROVAL);
        assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.ESCALATE)).isEqualTo(ReplayOutcome.ESCALATE);
        assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.INDETERMINATE)).isEqualTo(ReplayOutcome.INDETERMINATE);
    }

    @Test
    void 基线侧outcome优先且不标legacy() {
        assertThat(ReplayOutcome.fromWindow("REQUIRE_APPROVAL", "approved", true))
                .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.REQUIRE_APPROVAL, false));
    }

    @Test
    void 基线侧未知outcome为ERROR() {
        assertThat(ReplayOutcome.fromWindow("MAYBE", "approved", true).outcome()).isEqualTo(ReplayOutcome.ERROR);
    }

    @Test
    void 基线侧无outcome按decision折算且大小写不敏感() {
        assertThat(ReplayOutcome.fromWindow(null, "escalate", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ESCALATE, true));
        assertThat(ReplayOutcome.fromWindow("", "APPROVED", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ALLOW, true));
        assertThat(ReplayOutcome.fromWindow(null, "error", true).outcome()).isEqualTo(ReplayOutcome.ERROR);
    }

    @Test
    void 基线侧无decision按success折算() {
        assertThat(ReplayOutcome.fromWindow(null, null, true)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ALLOW, true));
        assertThat(ReplayOutcome.fromWindow(null, " ", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.DENY, true));
    }

    @Test
    void 可比性与转移键() {
        assertThat(ReplayOutcome.ERROR.comparable()).isFalse();
        assertThat(ReplayOutcome.INDETERMINATE.comparable()).isFalse();
        assertThat(ReplayOutcome.REQUIRE_APPROVAL.comparable()).isTrue();
        assertThat(ReplayOutcome.transitionKey(ReplayOutcome.ALLOW, ReplayOutcome.ESCALATE)).isEqualTo("ALLOW->ESCALATE");
    }

    @Test
    void parse严格匹配大写码() {
        assertThat(ReplayOutcome.parse("ERROR")).contains(ReplayOutcome.ERROR);
        assertThat(ReplayOutcome.parse("ALLOW")).contains(ReplayOutcome.ALLOW);
        assertThat(ReplayOutcome.parse("allow")).isEmpty();
        assertThat(ReplayOutcome.parse(" ALLOW ")).isEmpty();
        assertThat(ReplayOutcome.parse(null)).isEmpty();
    }

    @Test
    void 基线侧outcome去空白_小写视为未知_仅空白回落decision() {
        assertThat(ReplayOutcome.fromWindow(" ALLOW ", null, false))
                .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ALLOW, false));
        assertThat(ReplayOutcome.fromWindow("allow", "approved", true))
                .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ERROR, false));
        assertThat(ReplayOutcome.fromWindow("ERROR", "approved", true))
                .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ERROR, false));
        assertThat(ReplayOutcome.fromWindow("   ", "denied", true))
                .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.DENY, true));
    }

    @Test
    void 布尔遗留值唯一折算口径() {
        assertThat(ReplayOutcome.fromLegacyBoolean(true)).isEqualTo(ReplayOutcome.ALLOW);
        assertThat(ReplayOutcome.fromLegacyBoolean(false)).isEqualTo(ReplayOutcome.DENY);
    }
}
