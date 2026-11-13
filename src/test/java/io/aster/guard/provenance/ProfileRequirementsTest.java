package io.aster.guard.provenance;

import aster.core.typecheck.governance.ControlRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引擎 pin 1.0.45（registry 1.3.0，ADR 0046 §11.4 ⑦）起只有 {@code eu-ai-act-high-risk} 要求 verifiedInputs：
 * 声明该档案的模块，from-evidence 与 guard 决策对未经签名事实证实的被引用输入一律 DENY；{@code governed} 不受影响。
 */
class ProfileRequirementsTest {

  @Test
  void bundledRegistryRequiresVerifiedInputsOnlyForHighRisk() {
    ControlRegistry registry = ControlRegistry.loadDefault();
    ProfileRequirements requirements = new ProfileRequirements();

    assertThat(registry.version()).isEqualTo("1.3.0");
    assertThat(registry.profiles().keySet()).containsExactlyInAnyOrder("governed", "eu-ai-act-high-risk");
    assertThat(requirements.verifiedInputsRequired("governed")).isFalse();
    assertThat(requirements.verifiedInputsRequired("eu-ai-act-high-risk")).isTrue();
  }

  @Test
  void missingOrUnknownProfileIsNotRequired() {
    ProfileRequirements requirements = new ProfileRequirements();

    assertThat(requirements.verifiedInputsRequired(null)).isFalse();
    assertThat(requirements.verifiedInputsRequired("")).isFalse();
    assertThat(requirements.verifiedInputsRequired("nope")).isFalse();
  }

  /** 委托 replay 中唯一的 helper：注入要求 verifiedInputs 的注册表时按档案判定（模拟 1.3.0 翻转）。 */
  @Test
  void delegatesToRegistryProfileVerifiedInputs() {
    ControlRegistry flipped = new ControlRegistry("1.3.0-test", Set.of(), Map.of(), Map.of(
        "eu-ai-act-high-risk", new ControlRegistry.Profile("eu-ai-act-high-risk", true, true, List.of(), true),
        "governed", new ControlRegistry.Profile("governed", true, true, List.of(), false)));
    ProfileRequirements requirements = new ProfileRequirements(flipped);

    assertThat(requirements.verifiedInputsRequired("eu-ai-act-high-risk")).isTrue();
    assertThat(requirements.verifiedInputsRequired("governed")).isFalse();
    assertThat(requirements.verifiedInputsRequired("nope")).isFalse();
    assertThat(requirements.verifiedInputsRequired(null)).isFalse();
  }
}
