package io.aster.policy.governance;

/**
 * 输入字段的 PII 标记（ADR 0042 §12 D2）：{@code path} 按 §11 D2 路径语法（记录字段 {@code .}、记录列表 {@code []}），
 * {@code level ∈ {L1, L2, L3}}，{@code category} 取类型注解的类别；{@code source} 本期恒为 {@code "ir"}
 * （为 §12 D3 预留的数据目录来源保留取值 {@code "catalog"}）。
 */
public record PiiMark(String path, String level, String category, String source) {
  /** 由被执行 IR 的 {@code @pii} 类型注解推导。 */
  public static final String SOURCE_IR = "ir";
}
