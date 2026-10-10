package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import java.util.List;

/**
 * 被执行规则的元数据（ADR 0039 §3）：来自 Core IR 注解，不在运行时反查源码。
 * profile 是模块声明的治理档案 id（ADR 0046 §6），属模块级，未声明时为 null。
 */
public record RuleMetadata(String ruleId, List<String> controls, String profile) {
  public static final RuleMetadata NONE = new RuleMetadata(null, List.of(), null);

  /** 无档案的便捷构造。 */
  public RuleMetadata(String ruleId, List<String> controls) {
    this(ruleId, controls, null);
  }

  public static RuleMetadata of(CoreModel.Module module, String functionName) {
    if (module == null) return NONE;
    RuleMetadata moduleOnly = new RuleMetadata(null, List.of(), module.profile);
    if (module.decls == null || functionName == null) return moduleOnly;
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && functionName.equals(f.name)) {
        return new RuleMetadata(GovernanceChecker.ruleId(f).orElse(null), List.copyOf(GovernanceChecker.controls(f)),
            module.profile);
      }
    }
    return moduleOnly;
  }
}
