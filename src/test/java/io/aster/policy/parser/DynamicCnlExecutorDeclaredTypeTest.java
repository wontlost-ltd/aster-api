package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** #558 在源码执行路径（/evaluate-source、What-If）上：按声明类型转换，非法字符串同样归为入参不合法（400）。 */
class DynamicCnlExecutorDeclaredTypeTest {
  private static final String A_DECIMAL = String.join("\n",
      "Module fixture.decimal.",
      "",
      "Rule withTax given amount as Decimal, produce Decimal:",
      "  Return Decimal.round(amount times 1.0825m, 2, \"HALF_EVEN\").",
      "");

  @Test
  void sourcePathConvertsAndClassifiesInvalidInput() {
    DynamicCnlExecutor executor = new DynamicCnlExecutor();
    Object ok = executor.executeWithTenantContext("tenant-558", A_DECIMAL, Map.of("amount", "12.34"), "withTax",
        "en-US", null, false).result();
    assertThat(String.valueOf(ok)).isEqualTo("13.36");
    DynamicCnlExecutor.DynamicExecutionException e = catchThrowableOfType(
        DynamicCnlExecutor.DynamicExecutionException.class,
        () -> executor.executeWithTenantContext("tenant-558", A_DECIMAL, Map.of("amount", "12,34"), "withTax",
            "en-US", null, false));
    assertThat(e.kind()).isEqualTo(DynamicCnlExecutor.DynamicExecutionException.Kind.INVALID_INPUT);
    assertThat(e.getMessage()).isEqualTo("amount: expected Decimal");
  }
}
