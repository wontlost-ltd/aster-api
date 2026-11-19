package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 值规范 JSON（aster-lang-test {@code conformance/package-v1/VALUE-FORMS.md}）：探针期望值与 CLI {@code run} 信封的
 * {@code value} 用同一字节形态比较。输入是 {@code /evaluate-json} 响应 {@code result} 的同一棵 JSON 树
 * （整数节点视为精确整数，浮点节点视为 IEEE-754 double）。
 *
 * <p>规则：对象键按 Unicode 码点升序；数组保序；无空白；字符串按 RFC 8785 §3.2.2.2 转义（同 ECMAScript
 * {@code JSON.stringify}）；整数输出精确十进制（不限 2⁵³）；非整数 Double 按 ECMAScript {@code Number::toString}；
 * {@code -0} 输出 {@code 0}；顶层值为第 0 层，任何值位于第 65 层或更深、孤立代理项、NaN / ±Infinity 一律抛
 * {@link IllegalArgumentException}（探针据此记为求值失败）。
 *
 * <p>Double 的写法与 aster-lang-truffle CLI {@code aster.cli.ValueJson#number} 逐行相同（两侧跑同一组向量）。truffle 的实现在
 * {@code cli} 子项目（不随引擎 jar 发布，aster-api 无从依赖），故本类保留；两侧字节一致由 jre CLI 黄金门
 * {@code JreCliGoldenGateTest} 逐条探针比对守住。
 */
public final class ValueJson {
  /** 值的最大嵌套层级（与 ts {@code SERIALIZE_MAX_DEPTH} 相同）。 */
  public static final int MAX_VALUE_LEVEL = 64;

  private ValueJson() {
  }

  /** 把值树写成值规范 JSON。 */
  public static String write(JsonNode tree) {
    StringBuilder sb = new StringBuilder();
    write(tree, 0, sb);
    return sb.toString();
  }

  private static void write(JsonNode node, int level, StringBuilder sb) {
    if (level > MAX_VALUE_LEVEL) {
      throw new IllegalArgumentException("值嵌套超过 " + MAX_VALUE_LEVEL + " 层");
    }
    if (node == null || node.isNull() || node.isMissingNode()) {
      sb.append("null");
    } else if (node.isBoolean()) {
      sb.append(node.booleanValue() ? "true" : "false");
    } else if (node.isTextual()) {
      string(node.textValue(), sb);
    } else if (node.isIntegralNumber()) {
      sb.append(node.bigIntegerValue());
    } else if (node.isNumber()) {
      sb.append(number(node.doubleValue()));
    } else if (node.isArray()) {
      array(node, level, sb);
    } else if (node.isObject()) {
      object(node, level, sb);
    } else {
      throw new IllegalArgumentException("不支持的值节点 " + node.getNodeType());
    }
  }

  private static void object(JsonNode node, int level, StringBuilder sb) {
    List<String> keys = new ArrayList<>(node.size());
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      keys.add(it.next());
    }
    keys.sort(ValueJson::compareByCodePoint);
    sb.append('{');
    for (int k = 0; k < keys.size(); k++) {
      if (k > 0) {
        sb.append(',');
      }
      string(keys.get(k), sb);
      sb.append(':');
      write(node.get(keys.get(k)), level + 1, sb);
    }
    sb.append('}');
  }

  private static void array(JsonNode node, int level, StringBuilder sb) {
    sb.append('[');
    for (int k = 0; k < node.size(); k++) {
      if (k > 0) {
        sb.append(',');
      }
      write(node.get(k), level + 1, sb);
    }
    sb.append(']');
  }

  /** 按 Unicode 码点比较（非 BMP 字符按其码点，而非 UTF-16 码元）。 */
  public static int compareByCodePoint(String a, String b) {
    int ia = 0;
    int ib = 0;
    while (ia < a.length() && ib < b.length()) {
      int ca = a.codePointAt(ia);
      int cb = b.codePointAt(ib);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      ia += Character.charCount(ca);
      ib += Character.charCount(cb);
    }
    return Boolean.compare(ia < a.length(), ib < b.length());
  }

  /** 字符串不含孤立代理项。 */
  static boolean isWellFormed(String s) {
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      if (Character.isHighSurrogate(c)) {
        if (k + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(k + 1))) {
          return false;
        }
        k++;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  private static void string(String s, StringBuilder sb) {
    if (!isWellFormed(s)) {
      throw new IllegalArgumentException("字符串含孤立代理项");
    }
    sb.append('"');
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /** 把十进制数字串四舍五入到 {@code len} 位；进位溢出时返回 len + 1 位（首位为 1）。 */
  private static String roundDigits(String digits, int len) {
    char[] d = digits.substring(0, len).toCharArray();
    if (digits.charAt(len) >= '5') {
      int k = len - 1;
      while (k >= 0 && d[k] == '9') {
        d[k--] = '0';
      }
      if (k < 0) {
        return "1" + new String(d);
      }
      d[k]++;
    }
    return new String(d);
  }

  /**
  * ECMAScript {@code Number::toString(x)}（RFC 8785 §3.2.2.3）：有效数字取 {@link Double#toString}（JDK 19 起为最短往返位数），
  * 再按 ECMAScript 规则决定普通或指数写法。
  */
  public static String number(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) {
      throw new IllegalArgumentException("值中不得出现 " + x);
    }
    if (x == 0) {
      return "0";
    }
    String sign = x < 0 ? "-" : "";
    String java = Double.toString(Math.abs(x));
    int ePos = java.indexOf('E');
    String mantissa = ePos < 0 ? java : java.substring(0, ePos);
    int exp = ePos < 0 ? 0 : Integer.parseInt(java.substring(ePos + 1));
    int dot = mantissa.indexOf('.');
    String intPart = mantissa.substring(0, dot);
    String fracPart = mantissa.substring(dot + 1);
    // 有效数字串 digits 与十进制指数 n：x = 0.d1d2…dk × 10^n
    String all = intPart + fracPart;
    int n = intPart.length() + exp;
    int lead = 0;
    while (lead < all.length() - 1 && all.charAt(lead) == '0') {
      lead++;
    }
    String digits = all.substring(lead);
    n -= lead;
    int end = digits.length();
    while (end > 1 && digits.charAt(end - 1) == '0') {
      end--;
    }
    digits = digits.substring(0, end);
    // Double.toString 对少数值（如 Double.MIN_VALUE → 4.9E-324）给出多于最短往返所需的位数：逐位缩短，仍往返即采用
    while (digits.length() > 1) {
      String shorter = roundDigits(digits, digits.length() - 1);
      int shift = shorter.length() > digits.length() - 1 ? 1 : 0;
      String candidate = "0." + shorter.substring(0, digits.length() - 1) + "E" + (n + shift);
      if (Double.parseDouble(candidate) != Math.abs(x)) {
        break;
      }
      String trimmed = shorter.substring(0, digits.length() - 1).replaceAll("0+$", "");
      digits = trimmed.isEmpty() ? "1" : trimmed;
      n += shift;
    }
    int k = digits.length();
    StringBuilder sb = new StringBuilder(sign);
    if (k <= n && n <= 21) {
      sb.append(digits).append("0".repeat(n - k));
    } else if (0 < n && n <= 21) {
      sb.append(digits, 0, n).append('.').append(digits, n, k);
    } else if (-6 < n && n <= 0) {
      sb.append("0.").append("0".repeat(-n)).append(digits);
    } else {
      int e = n - 1;
      sb.append(digits.charAt(0));
      if (k > 1) {
        sb.append('.').append(digits, 1, k);
      }
      sb.append('e').append(e >= 0 ? "+" : "-").append(Math.abs(e));
    }
    return sb.toString();
  }
}
