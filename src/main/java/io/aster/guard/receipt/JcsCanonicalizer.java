package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jcs-sha256/v1} 的规范化部分：RFC 8785（JSON Canonicalization Scheme）。
 *
 * <p>字节级定义与语言无关，由共享向量 {@code src/test/resources/guard/jcs-sha256-v1-vectors.json} 锁定：
 * <ul>
 *   <li>对象成员按键的 UTF-16 码元序排序（{@link String#compareTo}），无空白；</li>
 *   <li>字符串是 Jackson 解码全部转义后的值，按 RFC 8785 §3.2.2.2 重新输出：只转义 {@code "}、{@code \}
 *       与 U+0000–U+001F（{@code \b \t \n \f \r} 用短形式，其余为反斜杠 + u00xx 小写 hex），其余字符原样 UTF-8；
 *       不做 Unicode 正规化；孤立代理项无法编码为 UTF-8，拒绝；</li>
 *   <li>数字一律按 IEEE 754 double 解释，按 {@link #formatNumber} 输出；先过 I-JSON（RFC 7493 §2.2）检查：
 *       非有限数、整数值 |x| &gt; 2^53−1 一律拒绝，不做舍入（这类值须以字符串发送）；</li>
 *   <li>{@code true / false / null} 原样保留：null 叶子与缺键是不同文档。</li>
 * </ul>
 *
 * <p>为何自行实现：运行期 classpath 没有任何 JCS 实现，且不新增依赖；RFC 8785 只有数字格式一处需要算法，
 * 其余是排序与转义，规模可控。
 *
 * <p>违反上述约束一律抛 {@link IllegalArgumentException}，由调用方映射为各自的 400 错误码。
 */
public final class JcsCanonicalizer {
  /** I-JSON 安全整数上界 2^53−1。 */
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final BigInteger MAX_SAFE = BigInteger.valueOf(MAX_SAFE_INTEGER);
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private JcsCanonicalizer() {
  }

  /** 把 JSON 树规范化为 RFC 8785 的 UTF-8 字节串。 */
  public static byte[] canonicalize(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node == null) {
      throw new IllegalArgumentException("JSON 值缺失");
    }
    switch (node.getNodeType()) {
      case OBJECT -> writeObject(node, out);
      case ARRAY -> writeArray(node, out);
      case STRING -> writeString(node.textValue(), out);
      case NUMBER -> out.append(formatNumber(requireIJsonNumber(node)));
      case BOOLEAN -> out.append(node.booleanValue());
      case NULL -> out.append("null");
      default -> throw new IllegalArgumentException("不是 JSON 值: " + node.getNodeType());
    }
  }

  private static void writeObject(JsonNode node, StringBuilder out) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    keys.sort(String::compareTo);
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      writeString(keys.get(i), out);
      out.append(':');
      write(node.get(keys.get(i)), out);
    }
    out.append('}');
  }

  private static void writeArray(JsonNode node, StringBuilder out) {
    out.append('[');
    for (int i = 0; i < node.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      write(node.get(i), out);
    }
    out.append(']');
  }

  /** RFC 8785 §3.2.2.2；代理对原样保留（UTF-8 编码时合成一个码点），孤立代理项拒绝。 */
  private static void writeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        default -> i = appendChar(s, i, out);
      }
    }
    out.append('"');
  }

  /** 输出位置 {@code i} 的字符，返回最后消费的下标（代理对消费两个码元）。 */
  private static int appendChar(String s, int i, StringBuilder out) {
    char c = s.charAt(i);
    if (c < 0x20) {
      out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
      return i;
    }
    if (!Character.isSurrogate(c)) {
      out.append(c);
      return i;
    }
    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
      out.append(c).append(s.charAt(i + 1));
      return i + 1;
    }
    throw new IllegalArgumentException("字符串含孤立代理项 U+" + Integer.toHexString(c).toUpperCase());
  }

  /**
   * I-JSON（RFC 7493 §2.2）数值检查，通过后返回 double 值。整数节点按精确值比较（避免 long → double 的舍入掩盖越界）；
   * 小数节点按 double 比较：|x| &gt; 2^53−1 的 double 必为整数，因此一律视为不安全整数。
   */
  private static double requireIJsonNumber(JsonNode node) {
    if (node.isIntegralNumber()) {
      if (node.bigIntegerValue().abs().compareTo(MAX_SAFE) > 0) {
        throw new IllegalArgumentException("整数超出 I-JSON 安全范围 ±(2^53−1)，须以字符串发送: " + node.asText());
      }
      return node.doubleValue();
    }
    return requireIJsonNumber(node.numberValue());
  }

  /**
   * I-JSON 数值检查（供已反序列化为 Java 数字的 {@code context} 复用同一规则）。
   *
   * @return 该数字的 double 值
   * @throws IllegalArgumentException 非有限数或整数值 |x| &gt; 2^53−1
   */
  public static double requireIJsonNumber(Number n) {
    if (n instanceof BigInteger || n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
      BigInteger exact = n instanceof BigInteger b ? b : BigInteger.valueOf(n.longValue());
      if (exact.abs().compareTo(MAX_SAFE) > 0) {
        throw new IllegalArgumentException("整数超出 I-JSON 安全范围 ±(2^53−1)，须以字符串发送: " + n);
      }
      return exact.doubleValue();
    }
    double d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
    if (!Double.isFinite(d)) {
      throw new IllegalArgumentException("数字不是有限值: " + n);
    }
    if (Math.abs(d) > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("整数超出 I-JSON 安全范围 ±(2^53−1)，须以字符串发送: " + n);
    }
    return d;
  }

  /**
   * ECMA-262 {@code Number::toString(x)}（RFC 8785 §3.2.2.3 引用之）。
   *
   * <p>x = 0（含 −0）→ {@code 0}；负数输出 {@code -} 后处理 |x|。取最短往返十进制表示：整数 k ≥ 1、n 与
   * k 位不以 0 结尾的数字串 s，使 x = s × 10^(n−k)；k 最小，多个候选时取最接近 x 者。然后：
   * <ul>
   *   <li>k ≤ n ≤ 21：s 后补 n−k 个 0；</li>
   *   <li>0 &lt; n ≤ 21：s 前 n 位、{@code .}、其余位；</li>
   *   <li>−6 &lt; n ≤ 0：{@code 0.}、−n 个 0、s；</li>
   *   <li>否则指数形式：s 首位、（k &gt; 1 时）{@code .} 与其余位、{@code e}、符号（恒写 {@code +}/{@code -}）、|n−1|。</li>
   * </ul>
   * 等价表述：定点当且仅当 x = 0 或 1e-6 ≤ |x| &lt; 1e21。
   *
   * <p>最短数字串取自 {@link Double#toString}（JDK 19+ 给出最短往返有效数字）。唯一差异：最短长度为 1 时
   * JDK 也考虑 2 位候选并取最接近者（如 {@code Double.MIN_VALUE} 给出 4.9E-324），ECMAScript 只取 1 位
   * （5e-324），见 {@link #preferSingleDigit}。
   *
   * @throws IllegalArgumentException NaN 或 Infinity
   */
  static String formatNumber(double x) {
    if (!Double.isFinite(x)) {
      throw new IllegalArgumentException("数字不是有限值: " + x);
    }
    if (x == 0) {
      return "0";
    }
    if (x < 0) {
      return "-" + formatNumber(-x);
    }
    Decimal d = preferSingleDigit(Decimal.shortest(x), x);
    return d.layout();
  }

  /** x = digits × 10^(n − k)，k = digits.length()，digits 不以 0 开头或结尾。 */
  private record Decimal(String digits, int n) {
    /** 从 {@link Double#toString} 的 {@code a.bE±e} / {@code a.b} 形式换算出 s 与 n。 */
    static Decimal shortest(double x) {
      String r = Double.toString(x);
      int e = r.indexOf('E');
      String mantissa = e < 0 ? r : r.substring(0, e);
      int exponent = e < 0 ? 0 : Integer.parseInt(r.substring(e + 1));
      int dot = mantissa.indexOf('.');
      return normalized(mantissa.substring(0, dot) + mantissa.substring(dot + 1), dot + exponent);
    }

    /** 去掉前导 0（每去一个小数点左移一位）与尾随 0。 */
    static Decimal normalized(String digits, int n) {
      int start = 0;
      while (start < digits.length() - 1 && digits.charAt(start) == '0') {
        start++;
      }
      int end = digits.length();
      while (end > start + 1 && digits.charAt(end - 1) == '0') {
        end--;
      }
      return new Decimal(digits.substring(start, end), n - start);
    }

    BigDecimal value() {
      return new BigDecimal(new BigInteger(digits), digits.length() - n);
    }

    String layout() {
      int k = digits.length();
      if (k <= n && n <= 21) {
        return digits + "0".repeat(n - k);
      }
      if (0 < n && n <= 21) {
        return digits.substring(0, n) + "." + digits.substring(n);
      }
      if (-6 < n && n <= 0) {
        return "0." + "0".repeat(-n) + digits;
      }
      int e = n - 1;
      String exp = "e" + (e < 0 ? "-" : "+") + Math.abs(e);
      return k == 1 ? digits + exp : digits.charAt(0) + "." + digits.substring(1) + exp;
    }
  }

  /**
   * JDK 只在 2 位时可能与 ECMAScript 不同：若首位截断或进一的 1 位候选也能往返到 x，改取其中最接近 x 者
   * （等距取偶数位，ECMA-262 Note 2）。
   */
  private static Decimal preferSingleDigit(Decimal d, double x) {
    if (d.digits().length() != 2) {
      return d;
    }
    BigDecimal exact = new BigDecimal(x);
    int first = d.digits().charAt(0) - '0';
    Decimal best = null;
    BigDecimal bestDistance = null;
    for (int candidate : new int[]{first, first + 1}) {
      Decimal c = Decimal.normalized(Integer.toString(candidate), d.n() + (candidate == 10 ? 1 : 0));
      if (Double.parseDouble(c.value().toString()) != x) {
        continue;
      }
      BigDecimal distance = c.value().subtract(exact).abs();
      int cmp = bestDistance == null ? -1 : distance.compareTo(bestDistance);
      if (cmp < 0 || (cmp == 0 && (c.digits().charAt(0) - '0') % 2 == 0)) {
        best = c;
        bestDistance = distance;
      }
    }
    return best == null ? d : best;
  }
}
