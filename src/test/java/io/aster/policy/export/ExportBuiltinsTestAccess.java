package io.aster.policy.export;

import java.util.Optional;

/** 测试辅助：让其它包的端点测试模拟 Node 没有白名单（target 不可用），用完恢复。 */
public final class ExportBuiltinsTestAccess {
  private ExportBuiltinsTestAccess() {
  }

  public static void disableNode() {
    ExportBuiltins.setForTest("node", Optional.empty());
  }

  public static void reset() {
    ExportBuiltins.setForTest("node", null);
  }
}
