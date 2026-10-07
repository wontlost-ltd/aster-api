package io.aster.policy.event;

/**
 * 审计事件类型定义。
 */
public enum EventType {
    POLICY_EVALUATION,
    POLICY_ROLLBACK,
    POLICY_CREATED,
    ORDER_SUBMITTED,
    ORDER_STATUS_QUERIED,

    // Phase 3.7: 异常响应自动化事件
    ANOMALY_VERIFICATION,     // 异常 Replay 验证
    ANOMALY_STATUS_CHANGE,    // 异常状态变更
    ANOMALY_AUTO_ROLLBACK,    // 异常自动回滚

    // P0-C 稳定性门禁（ADR 0031 M3）
    STABILITY_EXCEPTION_USED,   // 经白名单放行 Experimental 特性（含 approvalRef）
    STABILITY_EXCEPTION_DENIED, // 未授权 Experimental 被拒（留痕反复尝试）

    // Action Guard（ADR 0040）：同步入链，决策须在收据落链后才返回
    ACTION_GUARD_DECISION,      // 动作守卫决策（ALLOW / DENY / 待审批）
    ACTION_GUARD_APPROVAL       // 人工审批结论（批准 / 驳回）
}
