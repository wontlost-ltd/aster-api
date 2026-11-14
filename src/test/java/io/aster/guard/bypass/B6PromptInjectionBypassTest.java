package io.aster.guard.bypass;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #466 第 6 条：注入指令驱动 Agent。阶段 A 只登记、不执行：判定需要 aster-guard 0.2.0 代理按固定顺序
 * （验签 → consume → 上游 → 回写）转发，由阶段 B 以已发布的 npm 包、真实 aster-api 与脚本化的被注入 Agent 证明
 * 「代理不转发 DENY 的调用、上游未被调用、链上没有有效 consume」（计划 Task B2）。
 */
@Tag("guard-bypass")
@Disabled("阶段 B 待 #464（aster-guard 0.2.0 代理固定顺序）发布：脚本化被注入 Agent 经 aster-guard-mcp 调用受保护工具")
class B6PromptInjectionBypassTest {
  @Test
  void injectedInstructionIsDeniedAndToolNotExecuted() {
  }
}
