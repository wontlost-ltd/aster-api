package io.aster.policy.api.convert;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * aster-api#558：{@code mapContextByDeclaredTypes(context, params, module)} 按声明类型转换 Decimal 与 Long——形参、Data 字段、
 * List 元素、Map 值、Maybe 内层；十进制字符串、带小数的数字、整数都得到 {@link BigDecimal}，非法字符串报带路径的错误。
 */
class NamedContextMapperDeclaredTypeTest {

  private static CoreModel.TypeName named(String name) {
    CoreModel.TypeName t = new CoreModel.TypeName();
    t.name = name;
    return t;
  }

  private static CoreModel.ListT listOf(CoreModel.Type element) {
    CoreModel.ListT t = new CoreModel.ListT();
    t.type = element;
    return t;
  }

  private static CoreModel.MapT mapOf(CoreModel.Type val) {
    CoreModel.MapT t = new CoreModel.MapT();
    t.key = named("Text");
    t.val = val;
    return t;
  }

  private static CoreModel.Maybe maybe(CoreModel.Type inner) {
    CoreModel.Maybe t = new CoreModel.Maybe();
    t.type = inner;
    return t;
  }

  private static CoreModel.Param param(String name, CoreModel.Type type) {
    CoreModel.Param p = new CoreModel.Param();
    p.name = name;
    p.type = type;
    return p;
  }

  private static CoreModel.Field field(String name, CoreModel.Type type) {
    CoreModel.Field f = new CoreModel.Field();
    f.name = name;
    f.type = type;
    return f;
  }

  /** Data Claim { amount: Decimal, lines: List of Decimal, count: Long, note: Text }。 */
  private static CoreModel.Module module() {
    CoreModel.Data claim = new CoreModel.Data();
    claim.name = "Claim";
    claim.fields = List.of(field("amount", named("Decimal")), field("lines", listOf(named("Decimal"))),
        field("count", named("Long")), field("note", named("Text")));
    CoreModel.Module m = new CoreModel.Module();
    m.name = "t.decimal";
    m.decls = List.of(claim);
    return m;
  }

  private static Object single(CoreModel.Type type, Object value) {
    NamedContextMapper.MappingResult r = NamedContextMapper.mapContextByDeclaredTypes(Map.of("x", value),
        List.of(param("x", type)), module());
    assertThat(r.success()).as(r.error()).isTrue();
    return r.positionalArgs()[0];
  }

  private static String error(CoreModel.Type type, Object value) {
    NamedContextMapper.MappingResult r = NamedContextMapper.mapContextByDeclaredTypes(Map.of("x", value),
        List.of(param("x", type)), module());
    assertThat(r.success()).isFalse();
    return r.error();
  }

  @Test
  void decimalParameterAcceptsStringFractionalNumberAndInteger() {
    assertThat(single(named("Decimal"), "12.34")).isEqualTo(new BigDecimal("12.34"));
    assertThat(single(named("Decimal"), 12.34)).isEqualTo(new BigDecimal("12.34"));
    assertThat(single(named("Decimal"), 12)).isEqualTo(new BigDecimal("12"));
    assertThat(single(named("Decimal"), 12.0)).isEqualTo(new BigDecimal("12"));
    assertThat(single(named("Decimal"), 100.0)).isEqualTo(new BigDecimal("100"));
    assertThat(single(named("Decimal"), "-0.5")).isEqualTo(new BigDecimal("-0.5"));
    assertThat(error(named("Decimal"), "12,34")).isEqualTo("x: expected Decimal");
    assertThat(error(named("Decimal"), "1e3")).isEqualTo("x: expected Decimal");
    assertThat(error(named("Decimal"), "12.34\n")).isEqualTo("x: expected Decimal");
    assertThat(error(named("Decimal"), "1".repeat(81))).isEqualTo("x: expected Decimal");
  }

  @Test
  void decimalFieldOfData() {
    for (Object amount : new Object[] {"12.34", 12.34}) {
      Map<?, ?> claim = (Map<?, ?>) single(named("Claim"), Map.of("amount", amount, "note", "12.34"));
      assertThat(claim.get("amount")).isEqualTo(new BigDecimal("12.34"));
      assertThat(claim.get("note")).as("Text 字段不受影响").isEqualTo("12.34");
    }
    assertThat(((Map<?, ?>) single(named("Claim"), Map.of("amount", 7))).get("amount")).isEqualTo(new BigDecimal("7"));
    assertThat(error(named("Claim"), Map.of("amount", "abc"))).isEqualTo("x.amount: expected Decimal");
  }

  @Test
  void decimalListElementsTopLevelAndNested() {
    assertThat(single(listOf(named("Decimal")), List.of("1.5", 2.25, 3)))
        .isEqualTo(List.of(new BigDecimal("1.5"), new BigDecimal("2.25"), new BigDecimal("3")));
    Map<?, ?> claim = (Map<?, ?>) single(named("Claim"), Map.of("amount", "1", "lines", List.of("0.1", 0.2, 3)));
    assertThat(claim.get("lines")).isEqualTo(List.of(new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("3")));
    assertThat(error(listOf(named("Decimal")), List.of("1", "x"))).isEqualTo("x[1]: expected Decimal");
    assertThat(error(named("Claim"), Map.of("lines", List.of("1", "2", "bad")))).isEqualTo("x.lines[2]: expected Decimal");
  }

  @Test
  void decimalMapValuesMaybeAndListOfData() {
    Map<String, Object> rates = new LinkedHashMap<>();
    rates.put("a", "0.07");
    rates.put("b", 0.5);
    assertThat(single(mapOf(named("Decimal")), rates))
        .isEqualTo(Map.of("a", new BigDecimal("0.07"), "b", new BigDecimal("0.5")));
    assertThat(single(maybe(named("Decimal")), "9.99")).isEqualTo(new BigDecimal("9.99"));
    Map<String, Object> withNull = new LinkedHashMap<>();
    withNull.put("x", null);
    assertThat(NamedContextMapper.mapContextByDeclaredTypes(withNull, List.of(param("x", maybe(named("Decimal")))), module())
        .positionalArgs()[0]).isNull();
    List<?> claims = (List<?>) single(listOf(named("Claim")), List.of(Map.of("amount", "2.5")));
    assertThat(((Map<?, ?>) claims.get(0)).get("amount")).isEqualTo(new BigDecimal("2.5"));
  }

  @Test
  void longAcceptsSafeIntegersAndExactStrings() {
    assertThat(single(named("Long"), 42)).isEqualTo(42L);
    assertThat(single(named("Long"), "9007199254740993")).isEqualTo(9007199254740993L);
    assertThat(single(named("Long"), "-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
    assertThat(error(named("Long"), "9223372036854775808")).isEqualTo("x: expected Long");
    assertThat(error(named("Long"), "007")).isEqualTo("x: expected Long");
    assertThat(error(named("Long"), "1.5")).isEqualTo("x: expected Long");
    // 平台口径（裁决）：Jackson 已精确读入的 int64 整数照常接受（包格式的 ±(2⁵³−1) 只约束 CLI 输入）
    assertThat(single(named("Long"), 9007199254740993L)).isEqualTo(9007199254740993L);
    Map<?, ?> claim = (Map<?, ?>) single(named("Claim"), Map.of("count", "9007199254740993"));
    assertThat(claim.get("count")).isEqualTo(9007199254740993L);
  }

  @Test
  void otherTypesAndPositionalFormUnchangedExceptConversion() {
    assertThat(single(named("Text"), "12.34")).isEqualTo("12.34");
    assertThat(single(named("Double"), 12.34)).isEqualTo(12.34);
    assertThat(single(named("Int"), 7)).isEqualTo(7);
    NamedContextMapper.MappingResult positional = NamedContextMapper.mapContextByDeclaredTypes(List.of("1.25", "t"),
        List.of(param("a", named("Decimal")), param("b", named("Text"))), module());
    assertThat(positional.positionalArgs()).containsExactly(new BigDecimal("1.25"), "t");
    NamedContextMapper.MappingResult untyped = NamedContextMapper.mapContext(Map.of("x", "12.34"),
        List.of(param("x", named("Decimal"))));
    assertThat(untyped.positionalArgs()[0]).as("不带模块的旧重载行为不变").isEqualTo("12.34");
  }
}
