package io.aster.guard.bypass;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #466 第 1 条的部署层含义：模型（Agent）所在命名空间触达不到受保护工具的原始端点。ADR 0054 §7 第 1 条只以部署层为准，
 * JUnit 无法证明；{@link B1ApiDirectCallBypassTest} 只覆盖「绕过 Guard 直接打执行面 API」。
 * <p>
 * 部署层已于 2026-11-16 验证，判定点是 k3s 仓库的 {@code guard-bypass-probe} 工作流
 * （{@code .github/workflows/guard-bypass-probe.yml}）与 {@code redteam-window.sh} 反向记录。记录见 #466 评论：
 * <a href="https://github.com/wontlost-ltd/aster-api/issues/466#issuecomment-6899245972">首个生产探针记录</a>、
 * <a href="https://github.com/wontlost-ltd/aster-api/issues/466#issuecomment-6899602342">红队窗口反向记录</a>。
 * 必需检查 {@code Guard bypass suite (#466)} 跑在 public 仓 CI 上，不持有集群凭证，所以本类保持 {@code @Disabled}，不在此重复判定。
 */
@Tag("guard-bypass")
@Disabled("部署层由 k3s guard-bypass-probe 工作流判定（ADR 0054 §12 D7/D9）：记录见 aster-api#466 评论 6899245972（生产探针）与 6899602342（红队反向记录）；JUnit 无集群凭证，不在此重复")
class B1DirectConnectToolBypassTest {
  @Test
  void directConnectFromModelNamespaceFails() {
  }
}
