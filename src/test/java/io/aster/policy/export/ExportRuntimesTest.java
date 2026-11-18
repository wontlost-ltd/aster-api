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

  /**
   * 锁文件 jre.cliVersion 不得比生态 pin 新（落后允许：列车 L4 自动开的 pin PR 只改生态 pin，锁文件与信任锚由列车后的
   * 单独 PR 更新，本测试不得让该自动 PR 失败）。
   */
  @Test
  void jreCliVersionNotNewerThanEcosystemPin() throws Exception {
    Matcher m = Pattern.compile("aster-lang-platform:([0-9][0-9.]*)'").matcher(Files.readString(Path.of("settings.gradle")));
    assertThat(m.find()).isTrue();
    ExportRuntimes.Lock lock = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    assertThat(ExportRuntimes.compareVersions(lock.targets().get("jre").cliVersion(), m.group(1))).isLessThanOrEqualTo(0);
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
  void lockMayLagTheEngineButNeverLeadIt() {
    ExportRuntimes.Lock lock = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    assertThat(ExportRuntimes.checkEngineVersion(lock, "dev")).isFalse();
    assertThat(ExportRuntimes.checkEngineVersion(lock, "1.0.49")).isFalse();
    assertThat(ExportRuntimes.checkEngineVersion(lock, "1.0.50")).as("列车后、锁文件 PR 前：落后，只告警").isTrue();
    assertThat(ExportRuntimes.checkEngineVersion(lock, "1.1.0")).isTrue();
    assertThatThrownBy(() -> ExportRuntimes.checkEngineVersion(lock, "1.0.48"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("1.0.48");
  }

  @Test
  void versionsCompareNumerically() {
    assertThat(ExportRuntimes.compareVersions("1.0.49", "1.0.50")).isNegative();
    assertThat(ExportRuntimes.compareVersions("1.0.100", "1.0.99")).isPositive();
    assertThat(ExportRuntimes.compareVersions("1.0", "1.0.0")).isZero();
    assertThat(ExportRuntimes.compareVersions("2.0.0", "1.9.9")).isPositive();
  }
}
