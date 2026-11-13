package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.ControlRegistry;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.governance.InputRefsAnalyzer;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * 被执行规则的元数据（ADR 0039 §3）：来自 Core IR 注解，不在运行时反查源码。
 * profile 是模块声明的治理档案 id（ADR 0046 §6），属模块级，未声明时为 null。
 *
 * <p>{@code inputRefs} 是规则对第一个参数的静态输入引用集（ADR 0046 §11 D2，引擎源码字段路径、字典序，{@code "*"}
 * 表示无法静态定位）；只对声明了档案的模块中返回 Verdict 的规则给出（与引擎写 IR 的条件同口径），其余为 null。
 * {@code verifiedInputsRequired} 为模块档案在给定注册表下是否要求 {@code requires.verifiedInputs}（ADR 0046 §11 D1），
 * 属模块级，函数不存在时同样给出。
 *
 * <p><b>inputRefs 一律按规则体重算，从不读 IR 自带的 {@code Func.inputRefs}</b>（ADR 0046 §11.6 第 19 条）：
 * 本类服务的三条路径里，{@code POST /evaluate-json} 的 IR 由调用方提交，可以伪造 {@code inputRefs: []}；
 * {@code /evaluate} 读的已保存产物与 {@code /evaluate-source} 的即时编译虽来自本通道，但统一重算后不必在每条路径上
 * 判定 IR 是否可信，也让旧引擎编译、不带该字段的产物得到引用集。分析器带记忆化与节点预算，结果由调用方按编译产物 memo。
 * 分析器对畸形 IR 抛错时按 {@code ["*"]} 处理（fail-closed），不影响评估。
 */
public record RuleMetadata(String ruleId, List<String> controls, String profile, List<String> inputRefs,
                           boolean verifiedInputsRequired) {
  public static final RuleMetadata NONE = new RuleMetadata(null, List.of(), null);
  private static final Logger LOG = Logger.getLogger(RuleMetadata.class);

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

  /** 按 classpath 默认注册表（core jar 内副本，与运行期 guard 同一份）判定要求项。 */
  public static RuleMetadata of(CoreModel.Module module, String functionName) {
    return of(module, functionName, ControlRegistry.loadDefault());
  }

  public static RuleMetadata of(CoreModel.Module module, String functionName, ControlRegistry registry) {
    if (module == null) return NONE;
    boolean required = ProfileRequirementsLookup.verifiedInputsRequired(registry, module.profile);
    RuleMetadata moduleOnly = new RuleMetadata(null, List.of(), module.profile, null, required);
    if (module.decls == null || functionName == null) return moduleOnly;
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && functionName.equals(f.name)) {
        return new RuleMetadata(GovernanceChecker.ruleId(f).orElse(null), List.copyOf(GovernanceChecker.controls(f)),
            module.profile, inputRefsOf(module, f), required);
      }
    }
    return moduleOnly;
  }

  /** 重算规则的引擎路径引用集；不在写入范围内为 null。 */
  private static List<String> inputRefsOf(CoreModel.Module module, CoreModel.Func f) {
    if (!InputRefsAnalyzer.applies(module, f)) return null;
    try {
      return InputRefsAnalyzer.analyze(module, f).inputRefs();
    } catch (RuntimeException e) {
      LOG.warnf("规则输入引用分析失败，按 * 处理: %s.%s: %s", module.name, f.name, e.toString());
      return List.of(InputRefsAnalyzer.STAR);
    }
  }
}
