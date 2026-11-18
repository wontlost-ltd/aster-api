package io.aster.policy.api.convert;

import aster.core.ir.CoreModel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 按入口函数的<strong>声明类型</strong>转换 JSON 入参中的 Decimal 与 Long（aster-api#558，ADR 0059 包输入约定）。
 *
 * <p>JSON 本身区分不出 Decimal 与 Double、Long 与 Text：带小数的数字被 Jackson 读成 {@code Double}（与 Decimal
 * 运算即「Cannot combine Decimal and Double」），十进制字符串是 {@code String}（被当作 Text）。本类沿声明类型
 * 下降（形参 → Data 字段 → List 元素 → Map 值 → Maybe / Option 内层 → PII 基础类型），只改写声明为 Decimal / Long
 * 的叶子，其余值原样保留，因此其它类型的现有行为不变：
 * <ul>
 *   <li><b>Decimal</b>：十进制字符串 {@code ^-?(0|[1-9][0-9]*)(\.[0-9]+)?}（与 conformance package-v1 README 相同，
 *       整体匹配，数字至多 80 位）或任意 JSON 数字 → {@link BigDecimal}；其它字符串 → 错误 {@code <路径>: expected Decimal}。
 *       带小数的 JSON 数字按 {@code Double.toString} 的最短十进制写法取值（JDK 19+ 为最短往返表示）。</li>
 *   <li><b>Long</b>：int / long 整数与无小数部分的安全范围浮点数（{@code 42.0}）→ {@link Long}；字符串
 *       {@code ^-?(0|[1-9][0-9]{0,18})} 且在 int64 内 → {@link Long}；其它字符串 → 错误 {@code <路径>: expected Long}。
 *       超出 int64 的整数与带小数的数字保持原样（沿用既有口径）。</li>
 * </ul>
 * 嵌套层数超过 {@link #MAX_DEPTH} 的部分原样保留，交给宿主 → guest 转换按 {@code input_too_deep} 拒绝。
 */
public final class DeclaredTypeConverter {
  /** 与 {@code PolyglotValues.MAX_DEPTH} 相同的容器嵌套上限。 */
  public static final int MAX_DEPTH = 64;
  static final int MAX_DECIMAL_DIGITS = 80;

  private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?(?![\\s\\S])");
  private static final Pattern LONG = Pattern.compile("-?(0|[1-9][0-9]{0,18})(?![\\s\\S])");
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  /** 入参不符合声明类型；消息形如 {@code 申请.金额: expected Decimal}。 */
  public static final class ConversionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ConversionException(String message) {
      super(message);
    }
  }

  private final Map<String, CoreModel.Data> dataDecls;

  private DeclaredTypeConverter(Map<String, CoreModel.Data> dataDecls) {
    this.dataDecls = dataDecls;
  }

  /** 以模块的 Data 声明构造；{@code module} 为 null 时只转换顶层标量。 */
  public static DeclaredTypeConverter of(CoreModel.Module module) {
    Map<String, CoreModel.Data> decls = new HashMap<>();
    if (module != null && module.decls != null) {
      for (CoreModel.Decl d : module.decls) {
        if (d instanceof CoreModel.Data data && data.name != null) {
          decls.put(data.name, data);
        }
      }
    }
    return new DeclaredTypeConverter(decls);
  }

  /**
   * 按位置把实参转换为声明类型；实参数多于形参的部分原样保留。
   *
   * @throws ConversionException 某个 Decimal / Long 位置的字符串不合法
   */
  public Object[] convertArgs(Object[] args, List<CoreModel.Param> params) {
    Object[] out = args.clone();
    for (int i = 0; i < out.length && i < params.size(); i++) {
      CoreModel.Param p = params.get(i);
      out[i] = convert(out[i], p.type, p.name, 0);
    }
    return out;
  }

  /** 把一个值按声明类型转换；{@code path} 只用于错误消息。 */
  Object convert(Object value, CoreModel.Type type, String path, int depth) {
    if (value == null || type == null || depth > MAX_DEPTH) {
      return value;
    }
    return switch (type) {
      case CoreModel.TypeName tn -> convertNamed(value, tn.name, path, depth);
      case CoreModel.Maybe m -> convert(value, m.type, path, depth);
      case CoreModel.Option o -> convert(value, o.type, path, depth);
      case CoreModel.PiiType pii -> convert(value, pii.baseType, path, depth);
      case CoreModel.ListT l -> convertList(value, l.type, path, depth);
      case CoreModel.MapT m -> convertMapValues(value, m.val, path, depth);
      case CoreModel.TypeApp app -> convertApp(value, app, path, depth);
      default -> value;
    };
  }

  private Object convertApp(Object value, CoreModel.TypeApp app, String path, int depth) {
    List<CoreModel.Type> args = app.args == null ? List.of() : app.args;
    if ("List".equals(app.base) && args.size() == 1) {
      return convertList(value, args.get(0), path, depth);
    }
    if ("Map".equals(app.base) && args.size() == 2) {
      return convertMapValues(value, args.get(1), path, depth);
    }
    if (("Maybe".equals(app.base) || "Option".equals(app.base)) && args.size() == 1) {
      return convert(value, args.get(0), path, depth);
    }
    return value;
  }

  private Object convertNamed(Object value, String name, String path, int depth) {
    if ("Decimal".equals(name)) {
      return toDecimal(value, path);
    }
    if ("Long".equals(name)) {
      return toLong(value, path);
    }
    CoreModel.Data data = name == null ? null : dataDecls.get(name);
    if (data == null || data.fields == null || !(value instanceof Map<?, ?> map)) {
      return value;
    }
    Map<Object, Object> out = new LinkedHashMap<>(map);
    for (CoreModel.Field f : data.fields) {
      if (f != null && f.name != null && map.containsKey(f.name)) {
        out.put(f.name, convert(map.get(f.name), f.type, path + "." + f.name, depth + 1));
      }
    }
    return out;
  }

  private Object convertList(Object value, CoreModel.Type element, String path, int depth) {
    List<?> items = value instanceof List<?> l ? l : value instanceof Object[] arr ? java.util.Arrays.asList(arr) : null;
    if (items == null) {
      return value;
    }
    List<Object> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      out.add(convert(items.get(i), element, path + "[" + i + "]", depth + 1));
    }
    return out;
  }

  private Object convertMapValues(Object value, CoreModel.Type valType, String path, int depth) {
    if (!(value instanceof Map<?, ?> map)) {
      return value;
    }
    Map<Object, Object> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, convert(v, valType, path + "." + k, depth + 1)));
    return out;
  }

  static Object toDecimal(Object value, String path) {
    return switch (value) {
      case BigDecimal d -> d;
      case BigInteger i -> new BigDecimal(i);
      case Integer i -> BigDecimal.valueOf(i);
      case Long l -> BigDecimal.valueOf(l);
      case Short s -> BigDecimal.valueOf(s);
      case Byte b -> BigDecimal.valueOf(b);
      case Double d when Double.isFinite(d) -> normalize(new BigDecimal(Double.toString(d)));
      case Float f when Float.isFinite(f) -> normalize(new BigDecimal(Float.toString(f)));
      case String s when isDecimalText(s) -> new BigDecimal(s);
      case String s -> throw new ConversionException(path + ": expected Decimal");
      default -> value;
    };
  }

  static Object toLong(Object value, String path) {
    return switch (value) {
      case Integer i -> i.longValue();
      case Short s -> s.longValue();
      case Byte b -> b.longValue();
      case Double d when isSafeIntegral(d) -> d.longValue();
      case Float f when isSafeIntegral(f.doubleValue()) -> (long) f.floatValue();
      case String s when isLongText(s) -> Long.parseLong(s);
      case String s -> throw new ConversionException(path + ": expected Long");
      default -> value;
    };
  }

  static boolean isDecimalText(String s) {
    if (!DECIMAL.matcher(s).matches()) {
      return false;
    }
    long digits = s.chars().filter(Character::isDigit).count();
    return digits <= MAX_DECIMAL_DIGITS;
  }

  /** 有限、无小数部分、在 ±(2⁵³−1) 内的浮点数（如 JSON 的 {@code 42.0}）可无损视为 Long。 */
  static boolean isSafeIntegral(double d) {
    return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) <= 9_007_199_254_740_991d;
  }

  static boolean isLongText(String s) {
    if (!LONG.matcher(s).matches()) {
      return false;
    }
    BigInteger v = new BigInteger(s);
    return v.compareTo(LONG_MIN) >= 0 && v.compareTo(LONG_MAX) <= 0;
  }

  /** {@code 12.0} → {@code 12}：去掉小数部分末尾的 0，且不产生负 scale（{@code 100} 不变成 {@code 1E+2}）。 */
  private static BigDecimal normalize(BigDecimal d) {
    BigDecimal stripped = d.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
