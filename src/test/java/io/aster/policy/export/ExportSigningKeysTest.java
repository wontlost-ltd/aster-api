package io.aster.policy.export;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 导出签名密钥（ADR 0059 修订 3 P2-R2）：缺省可启动、只配一项与畸形配置启动失败、prod 拒绝测试 kid。 */
class ExportSigningKeysTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
  private static final List<String> PROD = List.of("prod");
  private static final String SEED = "Dg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4";

  private static String config(String kid) {
    return "{\"revision\":1,\"keys\":[{\"kid\":\"" + kid + "\",\"privateKey\":\"" + SEED + "\"}]}";
  }

  @Test
  void bothMissingYieldsEmptyRing() {
    assertThat(ExportSigningKeys.load(Optional.empty(), Optional.empty(), CLOCK, PROD)).isEmpty();
    assertThat(ExportSigningKeys.load(Optional.of(" "), Optional.of(""), CLOCK, PROD)).isEmpty();
  }

  @Test
  void onlyOneOfTheTwoFailsStartup() {
    assertThatThrownBy(() -> ExportSigningKeys.load(Optional.empty(), Optional.of("export-2026-11a"), CLOCK, PROD))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("同时配置");
    assertThatThrownBy(() -> ExportSigningKeys.load(Optional.of(config("k")), Optional.empty(), CLOCK, PROD))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASTER_EXPORT_ACTIVE_KID");
  }

  @Test
  void malformedConfigFailsWithoutLeakingSeed() {
    String bad = "{\"revision\":1,\"keys\":[{\"kid\":\"k\",\"privateKey\":\"" + SEED + "\" oops}]}";
    assertThatThrownBy(() -> ExportSigningKeys.load(Optional.of(bad), Optional.of("k"), CLOCK, PROD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aster.export.signing-keys")
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain(SEED));
  }

  @Test
  void testKidRejectedOnlyInProd() {
    assertThatThrownBy(() -> ExportSigningKeys.load(Optional.of(config("test-only-export")),
        Optional.of("test-only-export"), CLOCK, PROD))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("test-");
    assertThat(ExportSigningKeys.load(Optional.of(config("test-only-export")), Optional.of("test-only-export"),
        CLOCK, List.of("test"))).get().extracting(r -> r.active().kid()).isEqualTo("test-only-export");
    assertThat(ExportSigningKeys.load(Optional.of(config("export-2026-11a")), Optional.of("export-2026-11a"),
        CLOCK, PROD)).isPresent();
  }
}
