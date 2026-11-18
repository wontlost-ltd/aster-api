package io.aster.policy.export;

import java.util.Map;

/**
 * 策略包导出诊断码（ADR 0059 §8，修订 3 P2-R5 / P2-R6）。英文模板与 ts {@code shared/error_codes.json} 将登记的 message
 * 逐字相同；在这些登记随列车 eco-1.0.50 发布之前由本类产出同码同文的诊断，列车后（计划 T14）改用 core
 * {@code ErrorCode.EXPORT_*} 并删除本类。E736 保留不启用。E735、E739 只由 cloud 判定，这里只提供模板。
 */
public enum ExportDiagnosticCode {
  E730("Function '{function}' declares IO capability {capability}; exported packages cannot perform IO"),
  E731("Function '{function}' uses {construct} (workflow / async); exported packages cannot await"),
  E732("Name '{name}' in function '{function}' does not resolve to a module function, local binding, type constructor "
      + "or a pure builtin of the {target} engine"),
  E733("Entry '{entry}' {position} has type {type}, which has no JSON input/output form"),
  E734("Package size {size} bytes exceeds the {limit}-byte limit"),
  E735("Node probe {probe} disagrees with the platform: expected {expected}, got {actual}"),
  E737("Entry '{entry}' is shadowed by function 'main' on the JVM engine"),
  E738("Entry '{entry}' uses {type} at {path}; its value form is not defined yet"),
  E739("Node recompilation of the policy differs from the platform Core IR (positionFreeSha256 {platform} vs {node})");

  private final String template;

  ExportDiagnosticCode(String template) {
    this.template = template;
  }

  public String template() {
    return template;
  }

  /** 按命名参数填充模板；缺参数时保留占位符原样（便于在测试中发现）。 */
  public String format(Map<String, String> params) {
    String out = template;
    for (Map.Entry<String, String> p : params.entrySet()) {
      out = out.replace("{" + p.getKey() + "}", p.getValue());
    }
    return out;
  }

  /** error 级诊断；{@code line} / {@code column} 为 1-based，无位置时为 null。 */
  public ExportDiagnostic at(Integer line, Integer column, Map<String, String> params) {
    return new ExportDiagnostic(name(), format(params), "error", line, column);
  }
}
