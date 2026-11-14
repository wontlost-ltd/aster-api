package io.aster.guard.reconcile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** executor 标签只取白名单内的组件名，防止 executorId / source 让指标基数失控（计划 D6）。 */
class ExecutorLabelsTest {
  private final ExecutorLabels labels =
      new ExecutorLabels(List.of("aster-guard-mcp", "aster-guard-sdk", "aster-cloud-exec"));

  @Test
  void normalizes() {
    assertThat(labels.of("aster-guard-mcp@agent-host-1")).isEqualTo("aster-guard-mcp");
    assertThat(labels.of("ASTER-CLOUD-EXEC@cloud-7f9c")).isEqualTo("aster-cloud-exec");
    assertThat(labels.of("my-bot@pod-7f9c:3")).isEqualTo("other");
    assertThat(labels.of("billing-db")).isEqualTo("other");
    assertThat(labels.of(null)).isEqualTo("unknown");
    assertThat(labels.of("")).isEqualTo("unknown");
    assertThat(labels.of("  ")).isEqualTo("unknown");
    assertThat(labels.of("aster-guard-sdk")).isEqualTo("aster-guard-sdk");
  }

  @Test
  void whitelistIsTrimmedAndLowercased() {
    ExecutorLabels custom = new ExecutorLabels(List.of(" Tool-A ", "", "tool-b"));
    assertThat(custom.of("tool-a@x")).isEqualTo("tool-a");
    assertThat(custom.of("TOOL-B")).isEqualTo("tool-b");
    assertThat(custom.whitelist()).containsExactly("tool-a", "tool-b");
  }

  @Test
  void emptyComponentIsOther() {
    // 与 SQL 侧归一同语义：只有整串为空白才是 unknown
    assertThat(labels.of("@host")).isEqualTo("other");
  }
}
