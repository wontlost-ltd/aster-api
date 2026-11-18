package io.aster.policy.export;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策略包探针输入生成（ADR 0059 修订 3 P2-R12，版本 {@link #VERSION} 进可复现元组）。
 *
 * <p>基准输入 = 每个类型的基准值（Int / Long / Double 为 0，Decimal 为 {@code "0"}，Text 为 {@code ""}，Bool {@code false}，
 * List {@code []}，Map {@code {}}，Maybe / Option {@code null}，Enum 首变体，Data 逐字段递归）。之后按叶子路径（形参顺序、
 * Data 字段顺序深度优先）逐个、一次只改一处：Bool 取 {@code true}；Enum 取每个变体；Maybe 取内层的基准值；数值取模块
 * IR 中出现的每个同类数值字面量及其 −1 / +1（Int 截到 int32、Long 截到 ±(2⁵³−1)、Decimal 以字符串写出）；
 * Text 取模块中出现的每个字符串字面量。按生成顺序（值规范 JSON 字节）去重，取前 {@code max} 条，首条恒为基准输入。
 * List / Map 只用空集合，不在其内部变化。字面量按模块规范 IR 的遍历顺序收集，与 JVM 默认时区、locale 无关。
 */
public final class ProbeGenerator {
  public static final String VERSION = "1";
  public static final int MAX_PROBES = 256;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long SAFE = 9_007_199_254_740_991L;
  /** 递归 Data 的展开深度上限；超出处写 null（这样的输入在平台上按入参不合法丢弃）。 */
  private static final int MAX_DATA_DEPTH = 16;

  private record Leaf(List<String> path, CoreModel.Type type) {
  }

  private final Map<String, CoreModel.Data> datas = new HashMap<>();
  private final Map<String, CoreModel.Enum> enums = new HashMap<>();
  private final Map<String, List<JsonNode>> literals = new HashMap<>();

  private ProbeGenerator(CoreModel.Module module) {
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Data data) {
        datas.put(data.name, data);
      } else if (d instanceof CoreModel.Enum en) {
        enums.put(en.name, en);
      }
    }
    collectLiterals(module);
  }

  /** 生成入口的探针输入（每条是 {@code {形参名: 值}} 对象）。 */
  public static List<JsonNode> inputs(CoreModel.Module module, String entry, int max) {
    return new ProbeGenerator(module).generate(EntrySchemaGenerator.findFunc(module, entry), max);
  }

  private List<JsonNode> generate(CoreModel.Func func, int max) {
    ObjectNode base = F.objectNode();
    List<Leaf> leaves = new ArrayList<>();
    for (CoreModel.Param p : func.params) {
      base.set(p.name, baseValue(p.type, 0));
      collectLeaves(p.type, List.of(p.name), leaves, 0);
    }
    Set<String> seen = new LinkedHashSet<>();
    List<JsonNode> out = new ArrayList<>();
    add(base, seen, out, max);
    for (Leaf leaf : leaves) {
      for (JsonNode v : variations(leaf.type())) {
        if (out.size() >= max) {
          return out;
        }
        ObjectNode copy = base.deepCopy();
        set(copy, leaf.path(), v);
        add(copy, seen, out, max);
      }
    }
    return out;
  }

  private static void add(JsonNode input, Set<String> seen, List<JsonNode> out, int max) {
    if (out.size() < max && seen.add(ValueJson.write(input))) {
      out.add(input);
    }
  }

  private static void set(ObjectNode root, List<String> path, JsonNode value) {
    ObjectNode node = root;
    for (int i = 0; i < path.size() - 1; i++) {
      node = (ObjectNode) node.get(path.get(i));
    }
    node.set(path.getLast(), value);
  }

  private JsonNode baseValue(CoreModel.Type type, int depth) {
    return switch (type) {
      case CoreModel.TypeName tn -> namedBase(tn.name, depth);
      case CoreModel.ListT l -> F.arrayNode();
      case CoreModel.MapT m -> F.objectNode();
      case CoreModel.Maybe m -> F.nullNode();
      case CoreModel.Option o -> F.nullNode();
      case CoreModel.PiiType p -> baseValue(p.baseType, depth);
      case null, default -> F.nullNode();
    };
  }

  private JsonNode namedBase(String name, int depth) {
    switch (name) {
      case "Int", "Long" -> {
        return F.numberNode(0);
      }
      case "Double" -> {
        return F.numberNode(0.0);
      }
      case "Decimal" -> {
        return F.textNode("0");
      }
      case "Text" -> {
        return F.textNode("");
      }
      case "Bool" -> {
        return F.booleanNode(false);
      }
      default -> {
        CoreModel.Enum en = enums.get(name);
        if (en != null) {
          return en.variants.isEmpty() ? F.nullNode() : F.textNode(en.variants.getFirst());
        }
        CoreModel.Data data = datas.get(name);
        if (data == null || depth >= MAX_DATA_DEPTH) {
          return F.nullNode();
        }
        ObjectNode obj = F.objectNode();
        data.fields.forEach(f -> obj.set(f.name, baseValue(f.type, depth + 1)));
        return obj;
      }
    }
  }

  private void collectLeaves(CoreModel.Type type, List<String> path, List<Leaf> out, int depth) {
    if (type instanceof CoreModel.TypeName tn && datas.containsKey(tn.name)) {
      if (depth < MAX_DATA_DEPTH) {
        for (CoreModel.Field f : datas.get(tn.name).fields) {
          List<String> child = new ArrayList<>(path);
          child.add(f.name);
          collectLeaves(f.type, child, out, depth + 1);
        }
      }
      return;
    }
    if (type instanceof CoreModel.PiiType p) {
      collectLeaves(p.baseType, path, out, depth);
      return;
    }
    if (!(type instanceof CoreModel.ListT) && !(type instanceof CoreModel.MapT)) {
      out.add(new Leaf(path, type));
    }
  }

  private List<JsonNode> variations(CoreModel.Type type) {
    return switch (type) {
      case CoreModel.Maybe m -> List.of(baseValue(m.type, 0));
      case CoreModel.Option o -> List.of(baseValue(o.type, 0));
      case CoreModel.TypeName tn -> namedVariations(tn.name);
      case null, default -> List.of();
    };
  }

  private List<JsonNode> namedVariations(String name) {
    List<JsonNode> out = new ArrayList<>();
    switch (name) {
      case "Bool" -> out.add(F.booleanNode(true));
      case "Int" -> literals.getOrDefault("Int", List.of()).forEach(v -> {
        long n = v.longValue();
        for (long x : new long[] {n, n - 1, n + 1}) {
          out.add(F.numberNode(Math.clamp(x, Integer.MIN_VALUE, Integer.MAX_VALUE)));
        }
      });
      case "Long" -> literals.getOrDefault("Long", List.of()).forEach(v -> {
        long n = v.longValue();
        // ±1 先饱和再截断：Long.MIN_VALUE / MAX_VALUE 字面量的 ±1 不得回绕到相反的边界
        for (long x : new long[] {n, n == Long.MIN_VALUE ? n : n - 1, n == Long.MAX_VALUE ? n : n + 1}) {
          out.add(F.numberNode(Math.clamp(x, -SAFE, SAFE)));
        }
      });
      case "Double" -> literals.getOrDefault("Double", List.of()).forEach(v -> {
        double d = v.doubleValue();
        for (double x : new double[] {d, d - 1, d + 1}) {
          out.add(F.numberNode(x));
        }
      });
      case "Decimal" -> literals.getOrDefault("Decimal", List.of()).forEach(v -> {
        BigDecimal d = new BigDecimal(v.textValue());
        for (BigDecimal x : new BigDecimal[] {d, d.subtract(BigDecimal.ONE), d.add(BigDecimal.ONE)}) {
          out.add(F.textNode(plain(x)));
        }
      });
      case "Text" -> out.addAll(literals.getOrDefault("Text", List.of()));
      default -> {
        CoreModel.Enum en = enums.get(name);
        if (en != null) {
          en.variants.forEach(v -> out.add(F.textNode(v)));
        }
      }
    }
    return out;
  }

  /** 与输入模式 {@code ^-?(0|[1-9][0-9]*)(\.[0-9]+)?} 相符的十进制写法。 */
  static String plain(BigDecimal d) {
    return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
  }

  /** 按模块规范 IR（键码点序）的遍历顺序收集数值与字符串字面量，按值去重。 */
  private void collectLiterals(CoreModel.Module module) {
    JsonNode tree;
    try {
      tree = JSON.readTree(CoreIrJson.serialize(module));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Core IR 无法序列化", e);
    }
    Map<String, Set<String>> seen = new HashMap<>();
    walk(tree, seen);
  }

  private void walk(JsonNode node, Map<String, Set<String>> seen) {
    if (node.isArray()) {
      node.forEach(n -> walk(n, seen));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    JsonNode kind = node.get("kind");
    JsonNode value = node.get("value");
    if (kind != null && kind.isTextual() && value != null) {
      String k = switch (kind.textValue()) {
        case "Int" -> "Int";
        case "Long" -> "Long";
        case "Double" -> "Double";
        case "Decimal" -> "Decimal";
        case "String" -> "Text";
        default -> null;
      };
      if (k != null) {
        JsonNode lit = literal(k, value);
        if (lit != null && seen.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(lit.toString())) {
          literals.computeIfAbsent(k, x -> new ArrayList<>()).add(lit);
        }
      }
    }
    node.forEach(n -> walk(n, seen));
  }

  private static JsonNode literal(String kind, JsonNode value) {
    return switch (kind) {
      case "Int" -> value.canConvertToLong() ? F.numberNode(value.longValue()) : null;
      case "Long" -> F.numberNode(Long.parseLong(value.asText()));
      case "Double" -> F.numberNode(Double.parseDouble(value.asText()));
      case "Decimal", "Text" -> value.isTextual() ? F.textNode(value.textValue()) : null;
      default -> null;
    };
  }
}
