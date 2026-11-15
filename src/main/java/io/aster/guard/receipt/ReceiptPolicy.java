package io.aster.guard.receipt;

/**
 * 执行收据绑定的策略（ADR 0040 §12 D1，§13 R68）：决策评估的策略地址 {@code module.function} 与已保存的版本 id。
 *
 * <p>三项都可能缺失并按 NON_EMPTY 从签名载荷中省略：aster-cloud {@code evaluateSource} 锚定的 from-evidence 决策
 * 没有版本 id；评估事件未记地址时地址为空串。缺失项不进入签名字节，执行方据此 fail-closed（期望某地址却读不到即拒绝）。
 *
 * @param module    策略模块名（决策行 {@code policy_module}）
 * @param function  策略函数名（决策行 {@code policy_function}）
 * @param versionId 已保存的策略版本 id，可为 null
 */
public record ReceiptPolicy(String module, String function, Long versionId) {
}
