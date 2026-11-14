package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.BuiltinTypes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 从<b>本次评估实际执行的</b> Core IR 推导输入分类（ADR 0042 §12 D2）：声明树 + {@code @pii} 标记。
 *
 * <p>判定只看声明类型，不看值。展开规则：
 * <ul>
 *   <li>{@code PiiType} / {@code Option} / {@code Maybe} 逐层解包，遇到的最外层 {@code @pii} 即该路径的标记；</li>
 *   <li>记录类型（模块内 {@code Data}）按字段展开为 {@code <prefix>.<字段>}；</li>
 *   <li>{@code List of 记录} 展开为 {@code <prefix>[].<字段>}，{@code List of 标量} 是一个叶子；</li>
 *   <li>基础类型与枚举是已声明叶子；{@code Map}、未知类型名、类型变量与源码未标注（{@code typeInferred}）的字段
 *       不进声明树——视图里按 {@code unknown} 整体隐藏；</li>
 *   <li>同一条展开路径上再次遇到同名记录类型（环）时停止，该字段不进声明树。</li>
 * </ul>
 * 超上限（声明叶子 &gt; {@link #MAX_DECLARED}，或记录嵌套深度 &gt; {@link #MAX_DEPTH}）整体返回 null（fail-closed → {@code none}）。
 */
public final class InputClassifier {
  /** 声明叶子上限（ADR 0042 §12 D7）。 */
  public static final int MAX_DECLARED = 512;
  /** 记录嵌套深度上限，与 guard context 深度上限一致（参数自身的记录为第 1 层）。 */
  public static final int MAX_DEPTH = 8;

  private InputClassifier() {
  }

  /**
   * guard 形状（{@code POST /api/v1/guard/decisions}）：规则第一个参数是扁平化的 Action 记录，字段名经
   * {@code flatKeyPaths}（{@code GuardActionMapper.FlatField} 常量表：扁平键 → 请求路径）映射为请求路径；
   * 表外字段不产生路径。
   *
   * @return 分类；函数不存在、第一个参数不是记录或超上限时为 null
   */
  public static InputClassification guardShape(CoreModel.Module module, String function,
                                               Map<String, String> flatKeyPaths) {
    CoreModel.Func f = findFunc(module, function);
    if (f == null || f.params == null || f.params.isEmpty() || flatKeyPaths == null) {
      return null;
    }
    Walker w = new Walker(module);
    CoreModel.Data record = w.recordOf(f.params.get(0));
    if (record == null) {
      return null;
    }
    w.enter(record.name);
    for (CoreModel.Field field : fieldsOf(record)) {
      String path = flatKeyPaths.get(field.name);
      if (path != null && !field.typeInferred) {
        w.walk(field.type, path, 1);
      }
    }
    return w.result();
  }

  /**
   * 证据形状（from-evidence 锚定的 {@code /evaluate*} 事件）：参数 i 的前缀为 {@code context.<paramKeys[i]>}；
   * 未匹配到命名键（null）或键无法用点路径无歧义表示的参数不产生路径。
   *
   * @param paramKeys 每个参数实际匹配到的输入键（{@code NamedContextMapper.MappingResult#paramKeys}）
   * @return 分类；函数不存在或超上限时为 null
   */
  public static InputClassification evidenceShape(CoreModel.Module module, String function, List<String> paramKeys) {
    CoreModel.Func f = findFunc(module, function);
    if (f == null) {
      return null;
    }
    Walker w = new Walker(module);
    List<CoreModel.Param> params = f.params == null ? List.of() : f.params;
    for (int i = 0; i < params.size(); i++) {
      String key = paramKeys == null || i >= paramKeys.size() ? null : paramKeys.get(i);
      CoreModel.Param p = params.get(i);
      if (representableKey(key) && p != null && !p.typeInferred) {
        w.walk(p.type, "context." + key, 0);
      }
    }
    return w.result();
  }

  /** 非空、不含点与方括号、不含控制字符：否则路径无法无歧义地拆回键。 */
  static boolean representableKey(String key) {
    return key != null && !key.isEmpty()
        && key.chars().noneMatch(c -> c == '.' || c == '[' || c == ']' || c < 0x20);
  }

  private static CoreModel.Func findFunc(CoreModel.Module module, String function) {
    if (module == null || module.decls == null || function == null) {
      return null;
    }
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && function.equals(f.name)) {
        return f;
      }
    }
    return null;
  }

  private static List<CoreModel.Field> fieldsOf(CoreModel.Data data) {
    return data.fields == null ? List.of() : data.fields;
  }

  /** 解包结果：去掉 PII / Option / Maybe 外壳后的类型，及遇到的最外层 PII 注解（可无）。 */
  private record Unwrapped(CoreModel.Type type, CoreModel.PiiType pii) {
  }

  private static Unwrapped unwrap(CoreModel.Type type) {
    CoreModel.PiiType pii = null;
    CoreModel.Type t = type;
    while (true) {
      if (t instanceof CoreModel.PiiType p) {
        pii = pii == null ? p : pii;
        t = p.baseType;
      } else if (t instanceof CoreModel.Option o) {
        t = o.type;
      } else if (t instanceof CoreModel.Maybe m) {
        t = m.type;
      } else {
        return new Unwrapped(t, pii);
      }
    }
  }

  /** 单次推导的可变状态；超上限后 {@link #overflow} 置位，结果为 null。 */
  private static final class Walker {
    private final Map<String, CoreModel.Data> records = new HashMap<>();
    private final Set<String> enums = new HashSet<>();
    private final Set<String> expanding = new HashSet<>();
    private final TreeSet<String> declared = new TreeSet<>();
    private final TreeMap<String, PiiMark> marks = new TreeMap<>();
    private boolean overflow;

    Walker(CoreModel.Module module) {
      for (CoreModel.Decl d : module.decls) {
        if (d instanceof CoreModel.Data data && data.name != null) {
          records.put(data.name, data);
        } else if (d instanceof CoreModel.Enum e && e.name != null) {
          enums.add(e.name);
        }
      }
    }

    CoreModel.Data recordOf(CoreModel.Param p) {
      if (p == null || p.typeInferred) {
        return null;
      }
      return unwrap(p.type).type() instanceof CoreModel.TypeName tn ? records.get(tn.name) : null;
    }

    void enter(String recordName) {
      expanding.add(recordName);
    }

    InputClassification result() {
      return overflow ? null : new InputClassification(List.copyOf(declared), List.copyOf(marks.values()));
    }

    /** depth 为 {@code path} 所在的记录层数（参数前缀自身为 0）。 */
    void walk(CoreModel.Type type, String path, int depth) {
      if (overflow) {
        return;
      }
      Unwrapped u = unwrap(type);
      CoreModel.Type base = u.type();
      if (base instanceof CoreModel.ListT list) {
        walkList(list.type, path, depth, u.pii());
      } else if (base instanceof CoreModel.TypeApp app && "List".equals(app.base)
          && app.args != null && app.args.size() == 1) {
        walkList(app.args.get(0), path, depth, u.pii());
      } else if (base instanceof CoreModel.TypeName tn && records.containsKey(tn.name) && u.pii() == null) {
        walkRecord(records.get(tn.name), path, depth);
      } else if (base instanceof CoreModel.TypeName tn && isLeafType(tn.name)) {
        leaf(path, u.pii());
      } else if (base instanceof CoreModel.TypeName && u.pii() != null) {
        // @pii 包住记录类型：整个子树作为一个打码叶子
        leaf(path, u.pii());
      }
      // Map、类型变量、函数类型、未知类型名：不进声明树
    }

    private void walkList(CoreModel.Type element, String path, int depth, CoreModel.PiiType listPii) {
      Unwrapped e = unwrap(element);
      if (listPii == null && e.pii() == null && e.type() instanceof CoreModel.TypeName tn
          && records.containsKey(tn.name)) {
        walkRecord(records.get(tn.name), path + "[]", depth);
        return;
      }
      if (listPii != null || e.pii() != null
          || (e.type() instanceof CoreModel.TypeName tn && isLeafType(tn.name))) {
        leaf(path, listPii != null ? listPii : e.pii());
      }
    }

    private void walkRecord(CoreModel.Data record, String path, int depth) {
      if (depth + 1 > MAX_DEPTH) {
        overflow = true;
        return;
      }
      if (!expanding.add(record.name)) {
        return;
      }
      for (CoreModel.Field field : fieldsOf(record)) {
        if (field != null && field.name != null && !field.typeInferred) {
          walk(field.type, path + "." + field.name, depth + 1);
        }
      }
      expanding.remove(record.name);
    }

    private boolean isLeafType(String name) {
      return name != null && (BuiltinTypes.isPrimitiveType(name) || enums.contains(name));
    }

    private void leaf(String path, CoreModel.PiiType pii) {
      declared.add(path);
      if (pii != null) {
        marks.put(path, new PiiMark(path, pii.sensitivity, pii.category, PiiMark.SOURCE_IR));
      }
      if (declared.size() > MAX_DECLARED) {
        overflow = true;
      }
    }
  }
}
