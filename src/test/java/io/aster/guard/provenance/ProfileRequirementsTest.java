package io.aster.guard.provenance;

import aster.core.typecheck.governance.ControlRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前引擎 pin（1.0.44，registry 1.2.0）下没有任何档案要求 verifiedInputs：
 * from-evidence 关闭 fail-open 之后也不会因此拒绝任何请求。
 */
class ProfileRequirementsTest {

  @Test
  void noProfileInBundledRegistryRequiresVerifiedInputs() {
    ControlRegistry registry = ControlRegistry.loadDefault();
    ProfileRequirements requirements = new ProfileRequirements();

    assertThat(registry.profiles()).containsKeys("governed", "eu-ai-act-high-risk");
    assertThat(registry.profiles().keySet()).allSatisfy(id ->
        assertThat(requirements.verifiedInputsRequired(id)).as(id).isFalse());
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
