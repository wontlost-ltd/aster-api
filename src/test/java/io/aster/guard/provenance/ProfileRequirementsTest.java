package io.aster.guard.provenance;

import aster.core.typecheck.governance.ControlRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前引擎 pin 下没有任何档案要求 verifiedInputs（registry 1.1.0 / 1.2.0 全部为 false）：
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
}
