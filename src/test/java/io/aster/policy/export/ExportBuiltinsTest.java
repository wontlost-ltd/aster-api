package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** jre 白名单副本：与兄弟 aster-lang-truffle 的生成文件逐字节相同（兄弟目录不存在时跳过），names 升序无重复，别名归一。 */
class ExportBuiltinsTest {
  private static final Path COPY = Path.of("src/main/resources", ExportBuiltins.JRE_RESOURCE);

  @Test
  void namesSortedUniqueAndAliasesNormalize() throws Exception {
    JsonNode root = new ObjectMapper().readTree(COPY.toFile());
    List<String> names = new ArrayList<>();
    root.get("names").forEach(n -> names.add(n.asText()));
    assertThat(names).isSorted().doesNotHaveDuplicates();
    assertThat(root.get("engine").asText()).isEqualTo("aster-lang-truffle");
    ExportBuiltins jre = ExportBuiltins.forTarget("jre").orElseThrow();
    assertThat(jre.contains("times")).isTrue();
    assertThat(jre.contains("mul")).isTrue();
    assertThat(jre.contains("IO.print")).isFalse();
    assertThat(ExportBuiltins.forTarget("node")).isEmpty();
  }

  @Test
  void identicalToSiblingTruffleWhenPresent() throws Exception {
    Path sibling = Path.of("../aster-lang-truffle/src/main/resources/aster/truffle/export-builtins.json");
    if (!Files.exists(sibling)) {
      return;
    }
    assertThat(Files.readAllBytes(COPY)).isEqualTo(Files.readAllBytes(sibling));
  }
}
