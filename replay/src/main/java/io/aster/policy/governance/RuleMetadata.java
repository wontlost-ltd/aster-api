package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import java.util.List;

/** 被执行规则的元数据（ADR 0039 §3）：来自 Core IR 注解，不在运行时反查源码。 */
public record RuleMetadata(String ruleId, List<String> controls) {
  public static final RuleMetadata NONE = new RuleMetadata(null, List.of());

  public static RuleMetadata of(CoreModel.Module module, String functionName) {
    if (module == null || module.decls == null || functionName == null) return NONE;
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && functionName.equals(f.name)) {
        return new RuleMetadata(GovernanceChecker.ruleId(f).orElse(null), List.copyOf(GovernanceChecker.controls(f)));
      }
    }
    return NONE;
  }
}
