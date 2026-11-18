package io.aster.policy.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 导出运行时锁文件（ADR 0059 修订 3 P2-R3）：首个提交内容、严格解析、与引擎版本及生态 pin 一致。 */
class ExportRuntimesTest {

  @Test
  void firstLockFileHasJre1049WithoutAnchorsAndNoNode() {
    ExportRuntimes.Lock lock = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    assertThat(lock.targets()).containsOnlyKeys("jre");
    ExportRuntimes.Target jre = lock.targets().get("jre");
    assertThat(jre.cliName()).isEqualTo("cloud.aster-lang:aster-cli");
    assertThat(jre.cliVersion()).isEqualTo("1.0.49");
    assertThat(jre.trustAnchorKids()).isEmpty();
  }

  /** 列车 L4 pin bump 只改 settings.gradle 而漏改锁文件时，生产启动会失败；在 PR CI 就拦下。 */
  @Test
  void jreCliVersionEqualsEcosystemPin() throws Exception {
    Matcher m = Pattern.compile("aster-lang-platform:([0-9][0-9.]*)'").matcher(Files.readString(Path.of("settings.gradle")));
    assertThat(m.find()).isTrue();
    ExportRuntimes.Lock lock = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    assertThat(lock.targets().get("jre").cliVersion()).isEqualTo(m.group(1));
  }

  @Test
  void rejectsUnknownMissingAndMistypedFields() {
    String jre = "{\"cliName\":\"c\",\"cliVersion\":\"1\",\"trustAnchorKids\":[]}";
    List<String> bad = List.of(
        "not json",
        "{\"formatVersion\":1,\"jre\":" + jre + ",\"node\":null,\"extra\":1}",
        "{\"formatVersion\":1,\"jre\":" + jre + "}",
        "{\"formatVersion\":2,\"jre\":" + jre + ",\"node\":null}",
        "{\"formatVersion\":1,\"jre\":{\"cliName\":\"c\",\"cliVersion\":\"1\",\"trustAnchorKids\":[],\"sha256\":\"x\"},\"node\":null}",
        "{\"formatVersion\":1,\"jre\":{\"cliName\":\"c\",\"cliVersion\":\"1\",\"trustAnchorKids\":\"k\"},\"node\":null}",
        "{\"formatVersion\":1,\"jre\":{\"cliName\":\"\",\"cliVersion\":\"1\",\"trustAnchorKids\":[]},\"node\":null}",
        "{\"formatVersion\":1,\"formatVersion\":1,\"jre\":" + jre + ",\"node\":null}",
        "{\"formatVersion\":1,\"jre\":null,\"node\":null}");
    for (String json : bad) {
      assertThatThrownBy(() -> ExportRuntimes.parse(json)).as(json).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("export-runtimes.lock.json");
    }
  }

  @Test
  void engineVersionMustMatchUnlessDev() {
    ExportRuntimes.Lock lock = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    ExportRuntimes.requireEngineMatches(lock, "dev");
    ExportRuntimes.requireEngineMatches(lock, "1.0.49");
    assertThatThrownBy(() -> ExportRuntimes.requireEngineMatches(lock, "1.0.50"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("1.0.50");
  }
}
