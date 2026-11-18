package io.aster.policy.export;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 值规范 JSON：跑 conformance {@code vectors/value-json.json} 全部条目，含两组各 10 000 个随机 double。 */
class ValueJsonTest {
  private static final Path VECTORS = Path.of("src/test/resources/package-v1/vectors/value-json.json");
  /** 整数 token 无损（BigInteger），浮点 token 为 double——与 /evaluate-json 结果树的口径一致。 */
  private static final ObjectMapper TREE = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  @Test
  void namedVectors() throws Exception {
    JsonNode doc = new ObjectMapper().readTree(VECTORS.toFile());
    int checked = 0;
    for (JsonNode v : doc.get("vectors")) {
      String name = v.get("name").asText();
      JsonNode tree = TREE.readTree(v.get("treeJson").asText());
      if (v.path("error").asBoolean(false)) {
        assertThatThrownBy(() -> ValueJson.write(tree)).as(name).isInstanceOf(IllegalArgumentException.class);
      } else {
        assertThat(ValueJson.write(tree)).as(name).isEqualTo(v.get("expected").asText());
      }
      checked++;
    }
    assertThat(checked).isEqualTo(doc.get("vectors").size()).isGreaterThan(50);
  }

  @Test
  void randomDoubles() throws Exception {
    JsonNode doc = new ObjectMapper().readTree(VECTORS.toFile());
    int checked = 0;
    for (String group : new String[] {"randomDoubles", "randomPlainDoubles"}) {
      for (JsonNode item : doc.get(group).get("items")) {
        double d = Double.longBitsToDouble(Long.parseUnsignedLong(item.get("bits").asText(), 16));
        assertThat(ValueJson.write(DoubleNode.valueOf(d))).as(item.get("bits").asText())
            .isEqualTo(item.get("expected").asText());
        checked++;
      }
    }
    assertThat(checked).isGreaterThanOrEqualTo(20_000);
  }
}
