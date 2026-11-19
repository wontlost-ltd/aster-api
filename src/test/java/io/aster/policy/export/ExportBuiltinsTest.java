package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** jre 白名单读 truffle jar 内的生成文件、node 白名单是 ts 生成文件的副本：names 升序无重复，别名归一。 */
class ExportBuiltinsTest {

  @Test
  void namesSortedUniqueAndAliasesNormalize() throws Exception {
    JsonNode root;
    try (InputStream in = ExportBuiltins.class.getClassLoader().getResourceAsStream(ExportBuiltins.JRE_RESOURCE)) {
      assertThat(in).as("truffle jar 内的 " + ExportBuiltins.JRE_RESOURCE).isNotNull();
      root = new ObjectMapper().readTree(in);
    }
    List<String> names = new ArrayList<>();
    root.get("names").forEach(n -> names.add(n.asText()));
    assertThat(names).isSorted().doesNotHaveDuplicates();
    assertThat(root.get("engine").asText()).isEqualTo("aster-lang-truffle");
    ExportBuiltins jre = ExportBuiltins.forTarget("jre").orElseThrow();
    assertThat(jre.contains("times")).isTrue();
    assertThat(jre.contains("mul")).isTrue();
    assertThat(jre.contains("IO.print")).isFalse();
    ExportBuiltins node = ExportBuiltins.forTarget("node").orElseThrow();
    assertThat(node.engine()).isEqualTo("aster-lang-ts-interpreter");
    assertThat(node.contains("times")).isTrue();
    assertThat(node.contains("IO.print")).isFalse();
    assertThat(ExportBuiltins.forTarget("wasm")).isEmpty();
  }

  /** node 白名单是 ts 生成文件的逐字节副本（兄弟检出存在且与锁文件 node 版本同 tag 时比对）。 */
  @Test
  void nodeCopyIdenticalToSiblingTsWhenPresent() throws Exception {
    Path sibling = Path.of("../aster-lang-ts/src/policy-package/export-builtins.json");
    if (!Files.exists(sibling)) {
      return;
    }
    assertThat(Files.readAllBytes(Path.of("src/main/resources", ExportBuiltins.NODE_RESOURCE)))
        .isEqualTo(Files.readAllBytes(sibling));
  }
}
