package io.aster.policy.export;

/** 测试辅助：让其它包的端点测试在 T22 之前启用 Node 白名单的测试副本，用完恢复。 */
public final class ExportBuiltinsTestAccess {
  private ExportBuiltinsTestAccess() {
  }

  public static void enableNodeTestCopy() {
    ExportBuiltins.setForTest("node", ExportBuiltins.load("export/export-builtins-node.test.json"));
  }

  public static void reset() {
    ExportBuiltins.setForTest("node", null);
  }
}
