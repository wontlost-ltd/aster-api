package io.aster.policy.api;

import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试种子里的预编译 Core IR 必须是 Core IR v2（ADR 0055）：执行（Truffle Loader）与规则元数据（ruleId / controls /
 * profile / inputRefs）都按 v2 schema 严格读入，种子残留 v1 形状会让依赖它的用例以执行失败间接报错，这里直接定位到产物。
 */
class SeedCoreIrParsesTest {
  private static final String SEED = "/db/seed/V99.0.0__seed_test_policies.sql";
  /** 种子里每份 Core IR 都写成 {@code E'{…}'::bytea}。 */
  private static final Pattern CORE_IR = Pattern.compile("E'(\\{.*?\\})'::bytea", Pattern.DOTALL);

  @Test
  void everySeededCoreIrIsStrictV2() throws Exception {
    String sql;
    try (InputStream in = SeedCoreIrParsesTest.class.getResourceAsStream(SEED)) {
      assertThat(in).as(SEED).isNotNull();
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher m = CORE_IR.matcher(sql);
    List<String> failures = new ArrayList<>();
    int count = 0;
    while (m.find()) {
      count++;
      String json = m.group(1).replace("''", "'");
      try {
        CoreModel.Module module = CoreIrJson.read(json);
        assertThat(module.decls).isNotEmpty();
      } catch (CoreIrFormatException e) {
        failures.add(e.getMessage().lines().findFirst().orElse(""));
      }
    }
    assertThat(count).as("种子中的 Core IR 份数").isEqualTo(53);
    assertThat(failures).isEmpty();
  }
}
