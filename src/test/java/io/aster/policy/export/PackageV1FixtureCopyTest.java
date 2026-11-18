package io.aster.policy.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** package-v1 夹具副本（src/test/resources/package-v1）：按 SOURCE.md 清单校验，防止被手改；有兄弟检出时逐字节比对。 */
class PackageV1FixtureCopyTest {
  private static final Path ROOT = Path.of("src/test/resources/package-v1");
  private static final Pattern LINE = Pattern.compile("(?m)^([0-9a-f]{64})  (\\S+)$");

  static Map<String, String> manifest() throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher m = LINE.matcher(Files.readString(ROOT.resolve("SOURCE.md")));
    while (m.find()) {
      out.put(m.group(2), m.group(1));
    }
    return out;
  }

  /** 兄弟 aster-lang-test 检出（{@code ASTER_LANG_TEST_DIR}，缺省 {@code ../aster-lang-test}）；不存在返回 null。 */
  static Path siblingConformance() {
    String dir = System.getenv().getOrDefault("ASTER_LANG_TEST_DIR", "../aster-lang-test");
    Path p = Path.of(dir, "conformance/package-v1");
    return Files.isDirectory(p) ? p : null;
  }

  @Test
  void everyCopiedFileMatchesTheManifest() throws Exception {
    Map<String, String> sums = manifest();
    assertThat(sums).isNotEmpty();
    for (Map.Entry<String, String> e : sums.entrySet()) {
      byte[] bytes = Files.readAllBytes(ROOT.resolve(e.getKey()));
      assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
          .as(e.getKey()).isEqualTo(e.getValue());
    }
    try (Stream<Path> files = Files.walk(ROOT)) {
      long listed = files.filter(Files::isRegularFile).map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
          .filter(p -> !p.equals("SOURCE.md")).filter(p -> !sums.containsKey(p))
          .peek(p -> { throw new AssertionError("未登记进 SOURCE.md: " + p); }).count();
      assertThat(listed).isZero();
    }
  }

  @Test
  void identicalToSiblingCheckoutWhenPresent() throws Exception {
    Path sibling = siblingConformance();
    if (sibling == null) {
      return;
    }
    for (String rel : manifest().keySet()) {
      Path upstream = sibling.resolve(rel);
      if (Files.exists(upstream)) {
        assertThat(Files.readAllBytes(ROOT.resolve(rel))).as(rel).isEqualTo(Files.readAllBytes(upstream));
      }
    }
  }
}
