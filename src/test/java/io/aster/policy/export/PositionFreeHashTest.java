package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 去列投影哈希：跑 conformance {@code vectors/position-free-hash.json}（含只差 col 的平台 / ts IR 对）。 */
class PositionFreeHashTest {

  @Test
  void vectors() throws Exception {
    JsonNode doc = new ObjectMapper().readTree(Path.of("src/test/resources/package-v1/vectors/position-free-hash.json").toFile());
    int checked = 0;
    for (JsonNode v : doc.get("vectors")) {
      String core = v.get("coreJson").asText();
      String name = v.get("name").asText();
      assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(core.getBytes(StandardCharsets.UTF_8))))
          .as(name).isEqualTo(v.get("sha256").asText());
      assertThat(PositionFreeHash.sha256(core)).as(name).isEqualTo(v.get("positionFreeSha256").asText());
      checked++;
    }
    assertThat(checked).isEqualTo(doc.get("vectors").size()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void rejectsNonIr() {
    assertThatThrownBy(() -> PositionFreeHash.sha256("{}")).isInstanceOf(IllegalArgumentException.class);
  }
}
