package io.aster.policy.export;

import aster.core.ir.CoreModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闭世界导出检查（ADR 0059 §8，修订 3 P2-R6）：在平台 Core IR 上判定 E730（声明了 IO）、E731（workflow / async / await）、
 * E732（名字不在闭世界）与 E737（JVM 上入口被 {@code main} 抢占）。E733 / E738 由 {@link EntrySchemaGenerator} 产出。
 *
 * <p>E730 只看<strong>声明</strong>（{@code It performs io} 或显式声明的 IO 类能力），不看能力推断。
 * E732 在每个函数体上自顶向下维护作用域（形参、Let、lambda 形参、match 绑定）：一个 {@code Name} 依次查局部作用域
 * （带点的名字查首段，即成员访问）→ 模块函数 → 类型构造器（Enum 变体、{@code <Enum>.<变体>}）→ target 引擎的纯内置函数白名单；
 * 一个都不中即报，诊断带 1-based 行列。
 */
public final class ExportChecker {
  /** core {@code CapabilityKind} 中效应类为 IO 的能力。 */
  static final Set<String> IO_CAPABILITIES = Set.of(
      "Http", "Network", "Sql", "Time", "Files", "Secrets", "Process", "AiModel", "Payment", "Inventory");

  private final CoreModel.Module module;
  private final ExportBuiltins builtins;
  private final String target;
  private final Set<String> functions = new HashSet<>();
  private final Set<String> constructors = new HashSet<>();
  private final List<ExportDiagnostic> out = new ArrayList<>();

  private ExportChecker(CoreModel.Module module, ExportBuiltins builtins, String target) {
    this.module = module;
    this.builtins = builtins;
    this.target = target;
    for (CoreModel.Decl d : module.decls) {
      switch (d) {
        case CoreModel.Func f -> functions.add(f.name);
        case CoreModel.Enum en -> en.variants.forEach(v -> {
          constructors.add(v);
          constructors.add(en.name + "." + v);
        });
        case CoreModel.Data data -> constructors.add(data.name);
        default -> { }
      }
    }
  }

  /**
   * 整个模块的 E730–E732。
   *
   * @param builtins 该 target 的白名单（调用方已处理 node 白名单缺失的 503）
   */
  public static List<ExportDiagnostic> checkModule(CoreModel.Module module, String target, ExportBuiltins builtins) {
    ExportChecker c = new ExportChecker(module, builtins, target);
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f) {
        c.checkFunc(f);
      }
    }
    return List.copyOf(c.out);
  }

  /** 入口级 E737：JVM 引擎固定先选 {@code main}，入口不是 main 而模块里有 main 时入口被抢占。 */
  public static List<ExportDiagnostic> checkEntry(CoreModel.Module module, String entry, String target) {
    if (!"jre".equals(target) || "main".equals(entry)) {
      return List.of();
    }
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && "main".equals(f.name)) {
        return List.of(ExportDiagnosticCode.E737.at(line(f.origin), column(f.origin), Map.of("entry", entry)));
      }
    }
    return List.of();
  }

  private void checkFunc(CoreModel.Func f) {
    if (f.effects != null && f.effects.contains("io")) {
      out.add(ExportDiagnosticCode.E730.at(line(f.origin), column(f.origin),
          Map.of("function", f.name, "capability", "io")));
    }
    if (f.effectCapsExplicit && f.effectCaps != null) {
      f.effectCaps.stream().filter(IO_CAPABILITIES::contains).distinct().forEach(cap ->
          out.add(ExportDiagnosticCode.E730.at(line(f.origin), column(f.origin),
              Map.of("function", f.name, "capability", cap))));
    }
    Deque<Set<String>> scopes = new ArrayDeque<>();
    scopes.push(new HashSet<>(paramNames(f.params)));
    stmt(f.body, f.name, scopes);
  }

  private void stmt(CoreModel.Stmt s, String fn, Deque<Set<String>> scopes) {
    switch (s) {
      case null -> { }
      case CoreModel.Let let -> {
        expr(let.expr, fn, scopes);
        scopes.peek().add(let.name);
      }
      case CoreModel.Set set -> expr(set.expr, fn, scopes);
      case CoreModel.Return r -> expr(r.expr, fn, scopes);
      case CoreModel.If i -> {
        expr(i.cond, fn, scopes);
        nested(i.thenBlock, fn, scopes, Set.of());
        nested(i.elseBlock, fn, scopes, Set.of());
      }
      case CoreModel.Match m -> {
        expr(m.expr, fn, scopes);
        for (CoreModel.Case c : m.cases) {
          Set<String> bound = new HashSet<>();
          bind(c.pattern, bound);
          nested(c.body, fn, scopes, bound);
        }
      }
      case CoreModel.Scope sc -> block(sc.statements, fn, scopes);
      case CoreModel.Block b -> block(b.statements, fn, scopes);
      case CoreModel.Start st -> asyncUse(fn, "Start", st.origin);
      case CoreModel.Wait w -> asyncUse(fn, "Wait", w.origin);
      case CoreModel.Workflow w -> asyncUse(fn, "workflow", w.origin);
    }
  }

  private void block(List<CoreModel.Stmt> statements, String fn, Deque<Set<String>> scopes) {
    scopes.push(new HashSet<>());
    statements.forEach(st -> stmt(st, fn, scopes));
    scopes.pop();
  }

  private void nested(CoreModel.Stmt body, String fn, Deque<Set<String>> scopes, Set<String> bound) {
    scopes.push(new HashSet<>(bound));
    stmt(body, fn, scopes);
    scopes.pop();
  }

  private void expr(CoreModel.Expr e, String fn, Deque<Set<String>> scopes) {
    switch (e) {
      case null -> { }
      case CoreModel.Name n -> name(n, fn, scopes);
      case CoreModel.Ok ok -> expr(ok.expr, fn, scopes);
      case CoreModel.Err err -> expr(err.expr, fn, scopes);
      case CoreModel.Some some -> expr(some.expr, fn, scopes);
      case CoreModel.Construct c -> c.fields.forEach(fi -> expr(fi.expr, fn, scopes));
      case CoreModel.Call call -> {
        expr(call.target, fn, scopes);
        call.args.forEach(a -> expr(a, fn, scopes));
      }
      case CoreModel.Lambda l -> nested(l.body, fn, scopes, new HashSet<>(paramNames(l.params)));
      case CoreModel.Await a -> {
        asyncUse(fn, "Await", a.origin);
        expr(a.expr, fn, scopes);
      }
      case CoreModel.IfE i -> {
        expr(i.cond, fn, scopes);
        expr(i.thenE, fn, scopes);
        expr(i.elseE, fn, scopes);
      }
      case CoreModel.ListE l -> l.elements.forEach(x -> expr(x, fn, scopes));
      default -> { }
    }
  }

  private void name(CoreModel.Name n, String fn, Deque<Set<String>> scopes) {
    String name = n.name;
    int dot = name.indexOf('.');
    String head = dot < 0 ? name : name.substring(0, dot);
    boolean resolved = scopes.stream().anyMatch(s -> s.contains(head))
        || functions.contains(name)
        || constructors.contains(name)
        || builtins.contains(name);
    if (!resolved) {
      out.add(ExportDiagnosticCode.E732.at(line(n.origin), column(n.origin),
          Map.of("name", name, "function", fn, "target", target)));
    }
  }

  private void asyncUse(String fn, String construct, CoreModel.Origin origin) {
    out.add(ExportDiagnosticCode.E731.at(line(origin), column(origin), Map.of("function", fn, "construct", construct)));
  }

  private static void bind(CoreModel.Pattern p, Set<String> bound) {
    switch (p) {
      case CoreModel.PatName pn -> bound.add(pn.name);
      case CoreModel.PatCtor pc -> pc.args.forEach(a -> bind(a, bound));
      case null, default -> { }
    }
  }

  private static List<String> paramNames(List<CoreModel.Param> params) {
    return params == null ? List.of() : params.stream().map(p -> p.name).toList();
  }

  static Integer line(CoreModel.Origin o) {
    return o == null || o.start == null ? null : o.start.line;
  }

  static Integer column(CoreModel.Origin o) {
    return o == null || o.start == null ? null : o.start.col;
  }
}
