package io.aster.guard.provenance;

import aster.core.typecheck.governance.ControlRegistry;
import io.aster.policy.governance.ProfileRequirementsLookup;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 档案在运行期的要求项（ADR 0046 §11 D1）：from-evidence 按评估事件的 {@code profile} 在消费时重算是否要求
 * verifiedInputs（ADR 0040 §11 D5 实施澄清；事件里的要求项快照不参与判定）。
 *
 * <p>委托 replay 中唯一的判定 {@link ProfileRequirementsLookup}，与 {@code RuleMetadata} 同一口径；注册表是 core jar 内的
 * 副本（{@link ControlRegistry#loadDefault()}，引擎 pin 1.0.44 起为 registry 1.2.0，全部档案 {@code verifiedInputs=false}）。
 * 未知档案按不要求处理（编译期已报 E705，ADR 0046 §11.6 第 6 条）。
 */
@ApplicationScoped
public class ProfileRequirements {
  private final ControlRegistry registry;

  public ProfileRequirements() {
    this(ControlRegistry.loadDefault());
  }

  /** 注入注册表（测试模拟 registry 翻转）。 */
  public ProfileRequirements(ControlRegistry registry) {
    this.registry = registry;
  }

  /** @param profileId 模块声明的档案 id；null / 空表示未声明 */
  public boolean verifiedInputsRequired(String profileId) {
    return ProfileRequirementsLookup.verifiedInputsRequired(registry, profileId);
  }
}
