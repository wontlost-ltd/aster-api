package io.aster.billing.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.json.JsonObject;

/**
 * 用户配额 snapshot —— 本地 redis 缓存的 value 类型
 *
 * 由 cloud 主动推送（SnapshotPushResource）或 lazy fetch（cloud /precheck）填充。
 * TTL 1h；对账 cron 每小时全量校准。
 *
 * <p>{@code monthlyUsedBaseline} / {@code baselinePeriod}：cloud 统计的配额 owner 池当月合并用量
 * （含 cloud REST 调用写入 owner 池、不经过本服务 Redis 计数器的部分）及其账期（YYYY-MM）。
 * 快速路径取 max(本地计数, 同账期基线) 裁决，见 {@link #effectiveUsed}。两者缺省为 null，
 * 序列化时省略 null 字段——没有基线时写入 Redis 的 JSON 与旧格式逐字一致，滚动发布期间旧副本可照常读取。
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSnapshot(
    String userId,
    String plan,
    long apiCallsLimit,
    String subscriptionStatus,
    Long aiBannedUntilEpochMs,   // null = 未封禁
    Long gracePeriodEndsEpochMs, // null = 无 grace period
    Long monthlyUsedBaseline,    // null = cloud 未提供 owner 池用量
    String baselinePeriod        // monthlyUsedBaseline 所属账期（YYYY-MM）
) {
    /** 不带 owner 池用量基线的快照。 */
    public UserSnapshot(String userId, String plan, long apiCallsLimit, String subscriptionStatus,
                        Long aiBannedUntilEpochMs, Long gracePeriodEndsEpochMs) {
        this(userId, plan, apiCallsLimit, subscriptionStatus, aiBannedUntilEpochMs, gracePeriodEndsEpochMs,
            null, null);
    }

    /**
     * 从 cloud 推送 / 全量同步的 JSON 构造快照。{@code monthlyUsed}、{@code usagePeriod} 可选；
     * 只给用量不给账期时按接收时的账期 {@code receiptPeriod} 计（cloud 推送的是推送时刻的当月用量）。
     */
    public static UserSnapshot fromJson(String userId, JsonObject json, String receiptPeriod) {
        Long monthlyUsed = json.getLong("monthlyUsed", null);
        String period = monthlyUsed == null ? null : json.getString("usagePeriod", receiptPeriod);
        return new UserSnapshot(
            userId,
            json.getString("plan", "free"),
            json.getLong("apiCallsLimit", 0L),
            json.getString("subscriptionStatus", null),
            json.getLong("aiBannedUntilEpochMs", null),
            json.getLong("gracePeriodEndsEpochMs", null),
            monthlyUsed,
            period
        );
    }

    public boolean apiAccessAllowed() {
        return apiCallsLimit != 0;
    }

    public boolean unlimitedApi() {
        return apiCallsLimit == -1;
    }

    public boolean banned(long now) {
        return aiBannedUntilEpochMs != null && aiBannedUntilEpochMs > now;
    }

    /**
     * 裁决用的当月用量：本地计数与同账期的 cloud 基线取最大值。
     *
     * <p>取最大值而不是相加：cloud 基线已包含本服务上报的直连调用，相加会重复计数。
     * 基线账期与 {@code period} 不同（月切后旧快照尚未刷新）时忽略基线，不把上月用量带入新月。
     */
    public long effectiveUsed(long localUsed, String period) {
        if (monthlyUsedBaseline == null || baselinePeriod == null || !baselinePeriod.equals(period)) {
            return localUsed;
        }
        return Math.max(localUsed, monthlyUsedBaseline);
    }
}
