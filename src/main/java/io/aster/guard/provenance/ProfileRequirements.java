package io.aster.guard.provenance;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 档案在运行期的要求项（ADR 0046 §11 D1）：用于读不到规则元数据时，按评估事件的 {@code profile} 判定
 * 是否要求 verifiedInputs（from-evidence 关闭 fail-open，ADR 0040 §11 D5 实施澄清）。
 *
 * <p>当前引擎 pin（1.0.43，registry 1.1.0）的 {@code ControlRegistry.Profile} 还没有 {@code verifiedInputs} 字段，
 * 任何档案都不可能要求它，所以恒为 {@code false}；引擎升到 1.0.44（registry 1.2.0）后改为委托 replay 中唯一的
 * 判定 helper，与 {@code RuleMetadata} 同一口径。未知档案按不要求处理（编译期已报 E705，ADR 0046 §11.6 第 6 条）。
 */
@ApplicationScoped
public class ProfileRequirements {

  /** @param profileId 模块声明的档案 id；null / 空表示未声明 */
  public boolean verifiedInputsRequired(String profileId) {
    return false;
  }
}
