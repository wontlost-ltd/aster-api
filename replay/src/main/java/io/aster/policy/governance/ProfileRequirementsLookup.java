package io.aster.policy.governance;

import aster.core.typecheck.governance.ControlRegistry;

/**
 * 档案在运行期是否要求 verifiedInputs（ADR 0046 §11 D1）的唯一判定：{@link RuleMetadata#of} 与 aster-api 的
 * {@code ProfileRequirements} 都调用这里，不各写一份。
 *
 * <p>未声明档案、注册表缺失或档案不在注册表中（编译期已报 E705，无法保存）一律按不要求处理
 * （ADR 0046 §11.6 第 6 条）。
 */
public final class ProfileRequirementsLookup {
  private ProfileRequirementsLookup() {
  }

  /**
   * @param registry  控制注册表；null 按不要求
   * @param profileId 模块声明的档案 id；null / 空表示未声明
   */
  public static boolean verifiedInputsRequired(ControlRegistry registry, String profileId) {
    if (registry == null || profileId == null || profileId.isBlank()) {
      return false;
    }
    return registry.profile(profileId).map(ControlRegistry.Profile::verifiedInputs).orElse(false);
  }
}
