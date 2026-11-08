package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RFC 8785（JCS）规范化：ECMAScript 数字格式、I-JSON 数值边界、字符串转义与键序。 */
class JcsCanonicalizerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static String canonical(String json) throws Exception {
    return new String(JcsCanonicalizer.canonicalize(JSON.readTree(json)), StandardCharsets.UTF_8);
  }

  /** RFC 8785 附录 B：IEEE 754 位模式 → 期望的 JSON 数字文本（NaN / Infinity 见 {@link #nanAndInfinityRejected}）。 */
  @Test
  void rfc8785Appendix_B_numberSamples() {
    Map<String, String> samples = new LinkedHashMap<>();
    samples.put("0000000000000000", "0");
    samples.put("8000000000000000", "0");
    samples.put("0000000000000001", "5e-324");
    samples.put("8000000000000001", "-5e-324");
    samples.put("7fefffffffffffff", "1.7976931348623157e+308");
    samples.put("ffefffffffffffff", "-1.7976931348623157e+308");
    samples.put("4340000000000000", "9007199254740992");
    samples.put("c340000000000000", "-9007199254740992");
    samples.put("4430000000000000", "295147905179352830000");
    samples.put("44b52d02c7e14af5", "9.999999999999997e+22");
    samples.put("44b52d02c7e14af6", "1e+23");
    samples.put("44b52d02c7e14af7", "1.0000000000000001e+23");
    samples.put("444b1ae4d6e2ef4e", "999999999999999700000");
    samples.put("444b1ae4d6e2ef4f", "999999999999999900000");
    samples.put("444b1ae4d6e2ef50", "1e+21");
    samples.put("3eb0c6f7a0b5ed8c", "9.999999999999997e-7");
    samples.put("3eb0c6f7a0b5ed8d", "0.000001");
    samples.put("41b3de4355555553", "333333333.3333332");
    samples.put("41b3de4355555554", "333333333.33333325");
    samples.put("41b3de4355555555", "333333333.3333333");
    samples.put("41b3de4355555556", "333333333.3333334");
    samples.put("41b3de4355555557", "333333333.33333343");
    samples.put("becbf647612f3696", "-0.0000033333333333333333");
    samples.put("43143ff3c1cb0959", "1424953923781206.2");

    samples.forEach((hex, expected) -> {
      double d = Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
      assertThat(JcsCanonicalizer.formatNumber(d)).as(hex).isEqualTo(expected);
    });
  }

  @Test
  void nanAndInfinityRejected() {
    for (double d : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThatThrownBy(() -> JcsCanonicalizer.formatNumber(d)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(NODES.objectNode().put("n", d)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /** 定点当且仅当 x = 0 或 1e-6 ≤ |x| < 1e21；指数形式的符号恒写。 */
  @Test
  void fixedVsExponentBoundaries() {
    assertThat(JcsCanonicalizer.formatNumber(1e-6)).isEqualTo("0.000001");
    assertThat(JcsCanonicalizer.formatNumber(1e-7)).isEqualTo("1e-7");
    assertThat(JcsCanonicalizer.formatNumber(1.5e-7)).isEqualTo("1.5e-7");
    assertThat(JcsCanonicalizer.formatNumber(1e20)).isEqualTo("100000000000000000000");
    assertThat(JcsCanonicalizer.formatNumber(1e21)).isEqualTo("1e+21");
    assertThat(JcsCanonicalizer.formatNumber(1.5e21)).isEqualTo("1.5e+21");
    assertThat(JcsCanonicalizer.formatNumber(123e-20)).isEqualTo("1.23e-18");
    assertThat(JcsCanonicalizer.formatNumber(-0.0)).isEqualTo("0");
    assertThat(JcsCanonicalizer.formatNumber(0.1)).isEqualTo("0.1");
    assertThat(JcsCanonicalizer.formatNumber(-2.5)).isEqualTo("-2.5");
    assertThat(JcsCanonicalizer.formatNumber(100)).isEqualTo("100");
    assertThat(JcsCanonicalizer.formatNumber(123.456)).isEqualTo("123.456");
    assertThat(JcsCanonicalizer.formatNumber(0.001)).isEqualTo("0.001");
  }

  /** I-JSON：整数值 |x| > 2^53−1 一律拒绝，不论 JSON 写法与 Jackson 节点类型。 */
  @Test
  void unsafeIntegersRejectedForEveryNodeType() throws Exception {
    assertThat(canonical("{\"n\":9007199254740991}")).isEqualTo("{\"n\":9007199254740991}");
    assertThat(canonical("{\"n\":-9007199254740991}")).isEqualTo("{\"n\":-9007199254740991}");
    for (JsonNode n : new JsonNode[]{
        NODES.numberNode(9_007_199_254_740_992L),
        NODES.numberNode(-9_007_199_254_740_992L),
        NODES.numberNode(new BigInteger("123456789012345680000")),
        NODES.numberNode(1e20),
        NODES.numberNode(new BigDecimal("9007199254740992")),
        NODES.numberNode(new BigDecimal("1e400"))}) {
      assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(NODES.arrayNode().add(n)))
          .as(n.toString()).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void stringsAreReEscapedPerRfc8785() throws Exception {
    assertThat(canonical("[\"\\u00e9\",\"é\",\"e\\u0301\"]")).isEqualTo("[\"é\",\"é\",\"e\u0301\"]");
    assertThat(canonical("[\"\\u001F\",\"\\u0009\",\"\\/\",\"\\u007f\"]")).isEqualTo("[\"\\u001f\",\"\\t\",\"/\",\"\u007f\"]");
    assertThat(canonical("[\"\\b\\f\\n\\r\\\"\\\\\"]")).isEqualTo("[\"\\b\\f\\n\\r\\\"\\\\\"]");
    assertThat(canonical("\"\\ud83d\\ude00\"").getBytes(StandardCharsets.UTF_8))
        .containsExactly(0x22, 0xf0, 0x9f, 0x98, 0x80, 0x22);
  }

  /** 孤立代理项无法编码为 UTF-8（I-JSON §2.1），不得被静默替换成 '?' 造成哈希碰撞。 */
  @Test
  void loneSurrogateRejected() {
    assertThatThrownBy(() -> canonical("{\"s\":\"\\ud800\"}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonical("{\"\\udc00\":1}")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keysSortedByUtf16CodeUnitsAndLiteralsKept() throws Exception {
    assertThat(canonical("{\"b\":1,\"a\":{\"d\":null,\"c\":true},\"\\uffff\":false,\"\\ud83d\\ude00\":[]}"))
        .isEqualTo("{\"a\":{\"c\":true,\"d\":null},\"b\":1,\"\uD83D\uDE00\":[],\"\uffff\":false}");
  }

  @Test
  void missingNodeRejected() {
    assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(MissingNode.getInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
