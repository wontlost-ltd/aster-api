package io.aster.policy.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentIdentityTest {
  @Test void 合法四字段原样保留() {
    AgentIdentity a = AgentIdentity.validated(new AgentIdentity("anthropic", "claude-fable-5-1", "2026-10", "s-1"));
    assertThat(a).isEqualTo(new AgentIdentity("anthropic", "claude-fable-5-1", "2026-10", "s-1"));
  }

  @Test void null入参返回null() { assertThat(AgentIdentity.validated(null)).isNull(); }

  @Test void provider或model缺失报错() {
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity(null, "m", null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent.provider");
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", " ", null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent.model");
  }

  @Test void 超长与控制字符报错() {
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", "m".repeat(256), null, null)))
        .hasMessageContaining("agent.model");
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", "m", "v\u0007", null)))
        .hasMessageContaining("agent.version");
  }

  @Test void 恰好255字符合法() {
    String max = "x".repeat(AgentIdentity.MAX_TEXT);
    assertThat(AgentIdentity.validated(new AgentIdentity(max, max, max, max)))
        .isEqualTo(new AgentIdentity(max, max, max, max));
  }

  @Test void ofModel缺省provider为unknown且空model为null() {
    assertThat(AgentIdentity.ofModel("claude", "s")).isEqualTo(new AgentIdentity("unknown", "claude", null, "s"));
    assertThat(AgentIdentity.ofModel(" ", "s")).isNull();
  }
}
