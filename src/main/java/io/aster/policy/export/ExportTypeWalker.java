package io.aster.policy.export;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 入口类型遍历（ADR 0059 修订 3 P2-R4）：把一个 Core IR 类型映射为 JSON Schema 节点，同时产出 E733（无 JSON 形态）与
 * E738（值形态未定义：DateTime / Date / Time / PII）。映射逐条照 conformance {@code README.md}「输入」表与
 * aster-lang-test {@code scripts/lib/package-v1/fixtures.mjs} 的 {@code typeSchema}（输出方向同文件）。
 *
 * <p>Data 定义写入 {@code $defs["data.<T>"]}，先占位再填充，递归类型不会无限展开；已生成过的 Data 不再遍历，
 * 同一位置的诊断不重复。函数类型只报 E733，不进入其内部。
 */
final class ExportTypeWalker {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String END = "(?![\\s\\S])";
  private static final long SAFE = 9_007_199_254_740_991L;
  private static final Set<String> UNDEFINED_FORM = Set.of("DateTime", "Date", "Time");

  /** 遍历方向：输入（形参）或输出（返回值）。 */
  enum Direction { INPUT, OUTPUT }

  private final Map<String, CoreModel.Data> datas = new HashMap<>();
  private final Map<String, CoreModel.Enum> enums = new HashMap<>();
  private final List<ExportDiagnostic> diagnostics = new ArrayList<>();
  private final Set<String> reported = new LinkedHashSet<>();

  ExportTypeWalker(CoreModel.Module module) {
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Data data) {
        datas.put(data.name, data);
      } else if (d instanceof CoreModel.Enum en) {
        enums.put(en.name, en);
      }
    }
  }

  List<ExportDiagnostic> diagnostics() {
    return List.copyOf(diagnostics);
  }

  /**
   * @param defs   该方向文档的 {@code $defs}
   * @param entry  入口函数名（诊断用）
   * @param path   位置，如 {@code claim} 或 {@code return}
   */
  ObjectNode schema(CoreModel.Type type, Direction dir, ObjectNode defs, String entry, String path) {
    return switch (normalize(type)) {
      case null -> noForm(entry, path, "?");
      case CoreModel.ListT l -> F.objectNode().put("type", "array")
          .set("items", schema(l.type, dir, defs, entry, path + ".List<" + display(l.type) + ">"));
      case CoreModel.MapT m -> mapSchema(m, dir, defs, entry, path);
      case CoreModel.Maybe m -> optional(m.type, dir, defs, entry, path, "Maybe");
      case CoreModel.Option o -> optional(o.type, dir, defs, entry, path, "Option");
      case CoreModel.Result r -> resultSchema(r, dir, entry, path);
      case CoreModel.PiiType pii -> {
        undefinedForm(entry, path, display(pii));
        yield schema(pii.baseType, dir, defs, entry, path);
      }
      case CoreModel.TypeName tn -> named(tn.name, dir, defs, entry, path);
      case CoreModel.FuncType ft -> noForm(entry, path, display(ft));
      case CoreModel.TypeVar tv -> noForm(entry, path, display(tv));
      case CoreModel.TypeApp app -> noForm(entry, path, display(app));
    };
  }

  private ObjectNode mapSchema(CoreModel.MapT m, Direction dir, ObjectNode defs, String entry, String path) {
    if (!(normalize(m.key) instanceof CoreModel.TypeName k) || !"Text".equals(k.name)) {
      return noForm(entry, path, display(m));
    }
    return F.objectNode().put("type", "object")
        .set("additionalProperties", schema(m.val, dir, defs, entry, path + ".Map<Text, " + display(m.val) + ">"));
  }

  private ObjectNode optional(CoreModel.Type inner, Direction dir, ObjectNode defs, String entry, String path,
                              String wrapper) {
    String innerPath = path + "." + wrapper + "<" + display(inner) + ">";
    if (dir == Direction.INPUT) {
      CoreModel.Type n = normalize(inner);
      if (n instanceof CoreModel.Maybe || n instanceof CoreModel.Option) {
        return noForm(entry, path, wrapper + "<" + display(inner) + ">");
      }
      ArrayNode any = F.arrayNode().add(F.objectNode().put("type", "null"))
          .add(schema(inner, dir, defs, entry, innerPath));
      return F.objectNode().set("anyOf", any);
    }
    ObjectNode some = closedObject(List.of("__type", "value"));
    ((ObjectNode) some.get("properties")).set("__type", F.objectNode().put("const", "Some"));
    ((ObjectNode) some.get("properties")).set("value", schema(inner, dir, defs, entry, innerPath));
    ObjectNode none = closedObject(List.of("__type"));
    ((ObjectNode) none.get("properties")).set("__type", F.objectNode().put("const", "None"));
    return F.objectNode().set("anyOf", F.arrayNode().add(some).add(none));
  }

  private ObjectNode resultSchema(CoreModel.Result r, Direction dir, String entry, String path) {
    if (dir == Direction.INPUT) {
      return noForm(entry, path, display(r));
    }
    ObjectNode node = closedObject(List.of("__type", "value"));
    ObjectNode props = (ObjectNode) node.get("properties");
    props.set("__type", F.objectNode().set("enum", F.arrayNode().add("Ok").add("Err")));
    props.set("value", F.objectNode());
    return node;
  }

  private ObjectNode named(String name, Direction dir, ObjectNode defs, String entry, String path) {
    if (UNDEFINED_FORM.contains(name)) {
      undefinedForm(entry, path, name);
      return F.objectNode();
    }
    ObjectNode primitive = dir == Direction.INPUT ? primitiveInput(name) : primitiveOutput(name);
    if (primitive != null) {
      return primitive;
    }
    CoreModel.Enum en = enums.get(name);
    if (en != null) {
      return enumSchema(en, dir);
    }
    CoreModel.Data data = datas.get(name);
    if (data == null) {
      return noForm(entry, path, name);
    }
    String key = "data." + name;
    if (!defs.has(key)) {
      defs.set(key, F.objectNode());
      defs.set(key, dataSchema(data, dir, defs, entry, path));
    }
    return F.objectNode().put("$ref", "#/$defs/" + key);
  }

  private ObjectNode dataSchema(CoreModel.Data data, Direction dir, ObjectNode defs, String entry, String path) {
    List<String> required = new ArrayList<>();
    if (dir == Direction.OUTPUT) {
      required.add("__type");
    }
    data.fields.forEach(f -> required.add(f.name));
    ObjectNode node = closedObject(required);
    ObjectNode props = (ObjectNode) node.get("properties");
    if (dir == Direction.OUTPUT) {
      props.set("__type", F.objectNode().put("const", data.name));
    }
    for (CoreModel.Field f : data.fields) {
      props.set(f.name, schema(f.type, dir, defs, entry, path + "." + f.name));
    }
    return node;
  }

  private static ObjectNode enumSchema(CoreModel.Enum en, Direction dir) {
    ArrayNode names = F.arrayNode();
    en.variants.forEach(names::add);
    if (dir == Direction.INPUT) {
      return F.objectNode().put("type", "string").set("enum", names);
    }
    ObjectNode node = closedObject(List.of("_enum", "args", "value", "variant"));
    ObjectNode props = (ObjectNode) node.get("properties");
    props.set("_enum", F.objectNode().put("const", en.name));
    props.set("args", F.objectNode().put("type", "array"));
    props.set("value", F.objectNode().set("enum", names.deepCopy()));
    props.set("variant", F.objectNode().set("enum", names.deepCopy()));
    return node;
  }

  static ObjectNode primitiveInput(String name) {
    return switch (name) {
      case "Int" -> F.objectNode().put("type", "integer").put("minimum", Integer.MIN_VALUE).put("maximum", Integer.MAX_VALUE);
      case "Long" -> F.objectNode().set("anyOf", F.arrayNode()
          .add(F.objectNode().put("type", "integer").put("minimum", -SAFE).put("maximum", SAFE))
          .add(F.objectNode().put("type", "string").put("pattern", "^-?(0|[1-9][0-9]{0,18})" + END)));
      case "Double" -> F.objectNode().put("type", "number");
      case "Decimal" -> F.objectNode().put("type", "string").put("pattern", "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?" + END);
      case "Text" -> F.objectNode().put("type", "string");
      case "Bool" -> F.objectNode().put("type", "boolean");
      default -> null;
    };
  }

  private static ObjectNode primitiveOutput(String name) {
    return switch (name) {
      case "Verdict" -> verdictOutput();
      case "Decimal", "Text" -> F.objectNode().put("type", "string");
      case "Long", "Int" -> F.objectNode().put("type", "integer");
      case "Double" -> F.objectNode().put("type", "number");
      case "Bool" -> F.objectNode().put("type", "boolean");
      default -> null;
    };
  }

  private static ObjectNode verdictOutput() {
    ObjectNode node = closedObject(List.of("__type", "outcome"));
    ObjectNode props = (ObjectNode) node.get("properties");
    props.set("__type", F.objectNode().put("const", "Verdict"));
    props.set("outcome", F.objectNode().set("enum",
        F.arrayNode().add("ALLOW").add("DENY").add("REQUIRE_APPROVAL").add("ESCALATE")));
    props.set("reason", F.objectNode().put("type", "string"));
    props.set("role", F.objectNode().put("type", "string"));
    return node;
  }

  private static ObjectNode closedObject(List<String> required) {
    ObjectNode node = F.objectNode().put("type", "object");
    node.set("properties", F.objectNode());
    ArrayNode req = F.arrayNode();
    required.forEach(req::add);
    node.set("required", req);
    node.put("additionalProperties", false);
    return node;
  }

  private ObjectNode noForm(String entry, String path, String type) {
    report(ErrorCode.EXPORT_ENTRY_TYPE_UNSUPPORTED, entry, path, type,
        Map.of("entry", entry, "position", "at " + path, "type", type));
    return F.objectNode();
  }

  private void undefinedForm(String entry, String path, String type) {
    report(ErrorCode.EXPORT_ENTRY_VALUE_FORM_UNDEFINED, entry, path, type, Map.of("entry", entry, "type", type, "path", path));
  }

  private void report(ErrorCode code, String entry, String path, String type, Map<String, String> params) {
    if (reported.add(code + "|" + entry + "|" + path)) {
      diagnostics.add(ExportDiagnostic.of(code, null, null, params));
    }
  }

  /** {@code TypeApp} 形式的 List / Map / Maybe / Option / Result 归一为专用节点；其余原样。 */
  private static CoreModel.Type normalize(CoreModel.Type type) {
    if (!(type instanceof CoreModel.TypeApp app) || app.args == null) {
      return type;
    }
    List<CoreModel.Type> a = app.args;
    switch (app.base == null ? "" : app.base) {
      case "List" -> {
        if (a.size() == 1) {
          CoreModel.ListT l = new CoreModel.ListT();
          l.type = a.get(0);
          return l;
        }
      }
      case "Map" -> {
        if (a.size() == 2) {
          CoreModel.MapT m = new CoreModel.MapT();
          m.key = a.get(0);
          m.val = a.get(1);
          return m;
        }
      }
      case "Maybe", "Option" -> {
        if (a.size() == 1) {
          CoreModel.Maybe m = new CoreModel.Maybe();
          m.type = a.get(0);
          return m;
        }
      }
      case "Result" -> {
        if (a.size() == 2) {
          CoreModel.Result r = new CoreModel.Result();
          r.ok = a.get(0);
          r.err = a.get(1);
          return r;
        }
      }
      default -> {
        return type;
      }
    }
    return type;
  }

  /** 诊断里的类型写法：{@code List<Claim>}、{@code Map<Text, Int>}、{@code Result<Int, Text>}。 */
  static String display(CoreModel.Type type) {
    return switch (type) {
      case null -> "?";
      case CoreModel.TypeName tn -> tn.name;
      case CoreModel.TypeVar tv -> tv.name;
      case CoreModel.ListT l -> "List<" + display(l.type) + ">";
      case CoreModel.MapT m -> "Map<" + display(m.key) + ", " + display(m.val) + ">";
      case CoreModel.Maybe m -> "Maybe<" + display(m.type) + ">";
      case CoreModel.Option o -> "Option<" + display(o.type) + ">";
      case CoreModel.Result r -> "Result<" + display(r.ok) + ", " + display(r.err) + ">";
      case CoreModel.PiiType p -> "PII<" + display(p.baseType) + ">";
      case CoreModel.FuncType ft -> "Function<" + (ft.params == null ? "" : ft.params.stream()
          .map(ExportTypeWalker::display).collect(Collectors.joining(", "))) + " -> " + display(ft.ret) + ">";
      case CoreModel.TypeApp app -> app.base + "<" + (app.args == null ? "" : app.args.stream()
          .map(ExportTypeWalker::display).collect(Collectors.joining(", "))) + ">";
    };
  }
}
