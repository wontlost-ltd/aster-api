package io.aster.policy;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.ControlRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16b 依赖引擎 1.0.44：IR 的 {@code Func.inputRefs}、注册表档案的 {@code verifiedInputs} 与 registry ≥ 1.2.0。
 * 引擎 pin 回退到更早版本时本测试先红，而不是让 from-evidence 与 guard 静默退回「读不到引用」。
 */
class EnginePinGateTest {

  @Test
  void funcHasInputRefs() throws Exception {
    Field f = CoreModel.Func.class.getField("inputRefs");
    assertThat(f.getType()).isEqualTo(List.class);
  }

  @Test
  void profileHasVerifiedInputs() {
    assertThat(Arrays.stream(ControlRegistry.Profile.class.getRecordComponents()).map(RecordComponent::getName))
        .contains("verifiedInputs");
  }

  @Test
  void defaultRegistryIsAtLeast120() {
    String version = ControlRegistry.loadDefault().version();
    int[] actual = Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    assertThat(actual).as(version).hasSize(3);
    assertThat(compare(actual, new int[] {1, 2, 0})).as(version).isGreaterThanOrEqualTo(0);
  }

  private static int compare(int[] a, int[] b) {
    for (int i = 0; i < 3; i++) {
      if (a[i] != b[i]) {
        return Integer.compare(a[i], b[i]);
      }
    }
    return 0;
  }
}
