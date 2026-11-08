package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import java.util.List;

/**
 * 被执行规则的元数据（ADR 0039 §3）：来自 Core IR 注解，不在运行时反查源码。
 * profile 是模块声明的治理档案 id（ADR 0046 §6），属模块级，未声明时为 null。
 *
 * <p>{@code inputRefs} 是编译器静态算出的规则输入引用集（ADR 0046 §11 D2，点路径、字典序，{@code "*"} 表示无法静态定位）；
 * null 表示 IR 未提供（旧引擎编译或模块未声明档案）。{@code verifiedInputsRequired} 为模块档案是否要求
 * {@code requires.verifiedInputs}（ADR 0046 §11 D1）。引擎产出这两项之前 {@link #of} 恒给 null / false。
 */
public record RuleMetadata(String ruleId, List<String> controls, String profile, List<String> inputRefs,
                           boolean verifiedInputsRequired) {
  public static final RuleMetadata NONE = new RuleMetadata(null, List.of(), null);

  public RuleMetadata {
    inputRefs = inputRefs == null ? null : List.copyOf(inputRefs);
  }

  /** 无输入引用信息、不要求 verifiedInputs 的构造。 */
  public RuleMetadata(String ruleId, List<String> controls, String profile) {
    this(ruleId, controls, profile, null, false);
  }

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
