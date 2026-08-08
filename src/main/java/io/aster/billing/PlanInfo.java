package io.aster.billing;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 租户的当前订阅档位信息
 *
 * 由 aster-cloud /internal/tenant/{id}/plan 接口返回，aster-api 缓存使用。
 */
@RegisterForReflection
public record PlanInfo(
    String plan,
    String legacyTier,
    boolean allowsApproval,
    int maxTeamMembers,
    long evaluationsLimit,
    long apiCallsLimit,
    /**
     * What-If 并发批次上限（ADR 0034 §7.2）。
     *
     * <p>语义：{@code 0} = **无此功能**（free 档）、{@code -1} = 不限（enterprise 按合同）、
     * 正数 = 同时允许的 PENDING/RUNNING 批次数。
     *
     * <p>★ 注意 {@code 0} 与「限流为 0」不是一回事：它表示租户**没有买这个能力**，
     * 调用方应返回 403 而非 409。两者是不同的事，见 {@link #allowsReplayBatch()}。
     */
    int concurrentReplayBatches
) {

    /**
     * 默认 fail-open 实例：plan=pro 视为最宽松档位，不阻塞业务
     * 注意：仅在 plan-gate 服务不可达且 failOpen=true 时使用
     */
    public static PlanInfo failOpen() {
        // ★concurrentReplayBatches 刻意取 0 而非 pro 档的 1（ADR 0034 §7.2）。
        //   failOpen 的设计意图是「plan-gate 抖动时不阻塞**既有**业务」，对读类操作合理；
        //   但 What-If 批次是**新发起的、消耗计算资源的付费能力**——
        //   在这里 fail-open 等于「plan-gate 一抖动，free 租户就能免费跑批」。
        //   故此项 fail-closed，与本记录其余字段的宽松取向刻意不一致。
        return new PlanInfo("pro", null, true, -1, 50_000L, 5_000L, 0);
    }

    /** 是否**拥有** What-If 能力（与「当前能否再开一个」无关）。 */
    public boolean allowsReplayBatch() {
        return concurrentReplayBatches != 0;
    }

    /** 并发上限是否不受限。 */
    public boolean hasUnlimitedReplayBatches() {
        return concurrentReplayBatches < 0;
    }

    public boolean isFreePlan() {
        return "free".equals(plan);
    }

    /** API 调用配额：0 = 无 API 访问；-1 = 无限；其他 = 月度上限 */
    public boolean apiAccessAllowed() {
        return apiCallsLimit != 0;
    }

    public boolean unlimitedApi() {
        return apiCallsLimit == -1;
    }
}
