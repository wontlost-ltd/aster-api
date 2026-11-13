package io.aster.policy.api;

import aster.core.ir.CoreModel;
import io.aster.common.JacksonMappers;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试种子里的预编译 Core IR 必须能被 core 的 {@link CoreModel.Module} 反序列化：规则元数据（ruleId / controls /
 * profile / inputRefs）就是用它解析的。此前种子的 {@code Func.body} 缺 {@code "kind": "Block"}，truffle 的宽松
 * Loader 照样能执行，但元数据解析每次都报 {@code missing type id property 'kind'} 并降级为无元数据。
 */
class SeedCoreIrParsesTest {
  private static final String SEED = "/db/seed/V99.0.0__seed_test_policies.sql";
  /** 种子里每份 Core IR 都写成 {@code E'{ "name": … }'}，后接 {@code ::bytea} 或逗号。 */
  private static final Pattern CORE_IR = Pattern.compile("E'(\\{\\s*\"name\".*?)'(?:::|,)", Pattern.DOTALL);

  @Test
  void everySeededCoreIrDeserializesAsCoreModule() throws Exception {
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
        CoreModel.Module module = JacksonMappers.LENIENT.readValue(json, CoreModel.Module.class);
        assertThat(module.decls).isNotEmpty();
      } catch (Exception e) {
        failures.add(e.getMessage().lines().findFirst().orElse(""));
      }
    }
    assertThat(count).as("种子中的 Core IR 份数").isGreaterThan(40);
    assertThat(failures).isEmpty();
  }
}
