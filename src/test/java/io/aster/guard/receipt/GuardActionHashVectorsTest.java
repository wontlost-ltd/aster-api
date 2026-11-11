package io.aster.guard.receipt;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.aster.guard.GuardInvalidActionException;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code jcs-sha256/v1} 共享向量（{@code src/test/resources/guard/jcs-sha256-v1-vectors.json}）。
 *
 * <p>#464 执行方 SDK 必须逐字节复制本文件并全部通过（含重复键须拒绝、不安全整数须拒绝）；
 * 任何一方改配方必须同时改两份向量并升版 {@code /v2}（ADR 0054 §4）。
 *
 * <p>向量格式：{@code [{ name, input（原文 JSON，保留转义与数字写法）, canonical, sha256 }]}，
 * 或以 {@code error} 代替后两者：{@code duplicate_key}（解析阶段拒绝）、{@code unsafe_integer}（规范化阶段拒绝：
 * 按 double 解释后 |x| &gt; 2^53−1，含 {@code 9007199254740991.5} 这类小数写法）、{@code invalid_string}（规范化阶段拒绝：
 * 字符串含孤立代理项，无法编码为 UTF-8）。
 * 所有 input 都是 JSON 对象，因此 SDK 可直接以 {@code hashAction(input)} 逐条比对。
 */
class GuardActionHashVectorsTest {
  private static final ObjectMapper PLAIN = new ObjectMapper();
  private static final ObjectMapper STRICT = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final ActionHasher hasher = new ActionHasher();

  private static List<Map<String, String>> vectors() throws Exception {
    try (InputStream in = GuardActionHashVectorsTest.class.getResourceAsStream("/guard/jcs-sha256-v1-vectors.json")) {
      assertThat(in).as("向量文件存在").isNotNull();
      return PLAIN.readValue(in, PLAIN.getTypeFactory().constructCollectionType(List.class,
          PLAIN.getTypeFactory().constructMapType(Map.class, String.class, String.class)));
    }
  }

  private static Map<String, String> byName(List<Map<String, String>> vectors, String field) {
    Map<String, String> m = new HashMap<>();
    vectors.stream().filter(v -> v.get(field) != null).forEach(v -> m.put(v.get("name"), v.get(field)));
    return m;
  }

  @Test
  void everyVectorMatches() throws Exception {
    List<Map<String, String>> vectors = vectors();
    assertThat(vectors).hasSizeGreaterThan(30);
    for (Map<String, String> v : vectors) {
      String name = v.get("name");
      String error = v.get("error");
      if ("duplicate_key".equals(error)) {
        assertThatThrownBy(() -> STRICT.readTree(v.get("input"))).as(name).isInstanceOf(JsonParseException.class);
        continue;
      }
      JsonNode input = STRICT.readTree(v.get("input"));
      if ("unsafe_integer".equals(error) || "invalid_string".equals(error)) {
        assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(input)).as(name)
            .isInstanceOfSatisfying(JcsCanonicalizer.InvalidJsonValueException.class,
                e -> assertThat(e.code()).as(name).isEqualTo(error));
        assertThatThrownBy(() -> hasher.hash(input)).as(name).isInstanceOf(GuardInvalidActionException.class);
        continue;
      }
      assertThat(error).as(name + " 未知 error 代码").isNull();
      assertThat(new String(JcsCanonicalizer.canonicalize(input), StandardCharsets.UTF_8)).as(name)
          .isEqualTo(v.get("canonical"));
      assertThat(hasher.hash(input)).as(name).isEqualTo(v.get("sha256"));
    }
  }

  /** 向量之间必须成立的等价 / 区分关系，防止有人只改 sha256 让单条向量「自洽」而丢掉语义。 */
  @Test
  void vectorRelationsHold() throws Exception {
    Map<String, String> sha = byName(vectors(), "sha256");
    assertThat(distinct(sha.get("number_int_1"), sha.get("number_1_point_0"), sha.get("number_1e0"))).hasSize(1);
    assertThat(sha.get("string_escaped_e_acute")).isEqualTo(sha.get("string_literal_e_acute"))
        .isNotEqualTo(sha.get("string_nfd_e_acute"));
    assertThat(sha.get("string_tab_short_escape")).isEqualTo(sha.get("string_tab_unicode_escape"));
    assertThat(sha.get("key_order_utf16_code_units")).isEqualTo(sha.get("key_order_utf16_code_units_reversed_input"));
    assertThat(distinct(sha.get("nested_null_leaf"), sha.get("nested_missing_key"), sha.get("nested_empty_string"))).hasSize(3);
    assertThat(distinct(sha.get("adr0054_cc_null"), sha.get("adr0054_cc_missing"), sha.get("adr0054_cc_empty_string"),
        sha.get("adr0054_digest_binds_body"))).hasSize(4);
    assertThat(sha.get("adr0054_digest_of_null_value")).isNotEqualTo(sha.get("adr0054_digest_key_missing"));

    Map<String, String> errors = byName(vectors(), "error");
    assertThat(errors.values().stream().collect(Collectors.toSet())).containsExactlyInAnyOrder("duplicate_key", "unsafe_integer",
        "invalid_string");
    assertThat(errors).containsKeys("unsafe_1e20", "unsafe_1e21", "unsafe_2_pow_53", "unsafe_2_pow_53_plus_1",
        "unsafe_large_integer", "unsafe_negative_2_pow_53", "duplicate_key_top_level", "duplicate_key_nested",
        "unsafe_fraction_above_max_safe", "string_lone_high_surrogate", "string_lone_low_surrogate");
    assertThat(errors).containsEntry("unsafe_fraction_above_max_safe", "unsafe_integer")
        .containsEntry("string_lone_high_surrogate", "invalid_string")
        .containsEntry("string_lone_low_surrogate", "invalid_string");
  }

  /** ADR 0054 §3.2：{@code $sha256} 摘要输入是被摘要值的 JCS 字节串（null 为 4 字节 {@code null}）。 */
  @Test
  void digestSubstitutionUsesJcsOfValue() throws Exception {
    Map<String, String> input = byName(vectors(), "input");
    JsonNode ccNull = STRICT.readTree(input.get("adr0054_cc_null"));
    assertThat(ccNull.at("/context/body/$sha256").asText())
        .isEqualTo(sha256Of(JcsCanonicalizer.canonicalize(JsonNodeFactory.instance.textNode("hi"))));
    JsonNode nullBody = STRICT.readTree(input.get("adr0054_digest_of_null_value"));
    assertThat(nullBody.at("/context/body/$sha256").asText())
        .isEqualTo(sha256Of(JcsCanonicalizer.canonicalize(JsonNodeFactory.instance.nullNode())));
  }

  @Test
  void hashRejectsNonObjectAction() {
    JsonNodeFactory f = JsonNodeFactory.instance;
    for (JsonNode bad : new JsonNode[]{null, MissingNode.getInstance(), f.nullNode(), f.arrayNode(), f.textNode("x"), f.numberNode(1)}) {
      assertThatThrownBy(() -> hasher.hash(bad)).as(String.valueOf(bad)).isInstanceOf(GuardInvalidActionException.class);
    }
    assertThat(hasher.hash(f.objectNode())).isEqualTo(sha256Of("{}".getBytes(StandardCharsets.UTF_8)))
        .matches("[0-9a-f]{64}");
  }

  /** 去重后的取值集合；任一为 null（向量缺失）时断言失败而非静默通过。 */
  private static Set<String> distinct(String... values) {
    assertThat(values).doesNotContainNull();
    return new HashSet<>(Arrays.asList(values));
  }

  private static String sha256Of(byte[] bytes) {
    return DigestUtils.sha256Hex(bytes);
  }
}
