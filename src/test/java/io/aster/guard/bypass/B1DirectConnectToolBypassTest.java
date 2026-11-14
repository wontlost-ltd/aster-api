package io.aster.guard.bypass;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #466 第 1 条的部署层含义：模型（Agent）所在命名空间触达不到受保护工具的原始端点。ADR 0054 §7 第 1 条只以部署层为准，
 * JUnit 无法证明；{@link B1ApiDirectCallBypassTest} 只覆盖「绕过 Guard 直接打执行面 API」。阶段 C 启用前本例恒为 skipped，
 * #466 的场景矩阵把第 1 条标为「部署层未验证」。
 */
@Tag("guard-bypass")
@Disabled("阶段 C 部署层：从模型命名空间直连原始工具端点，由 k3s scripts/guard-bypass/direct-connect-probe.sh 判定（ADR 0054 §7 第 1 条只以部署层为准）")
class B1DirectConnectToolBypassTest {
  @Test
  void directConnectFromModelNamespaceFails() {
  }
}
