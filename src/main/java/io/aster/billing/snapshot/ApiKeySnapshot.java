package io.aster.billing.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.json.JsonObject;

/**
 * API key snapshot —— 本地 redis 缓存
 *
 * keyHash 作为 redis key（SHA-256(明文)）；明文不存任何地方。
 *
 * <p>滚动升级期间新旧 pod 共享同一 Redis，旧 pod 用 vanilla mapper 读（未知字段即失败）。
 * 因此 quotaOwnerId 为 null（旧 cloud 的 key，或经归一的个人 key）时不写该键，写出的 JSON 与升级前
 * 逐字节相同；其余字段的 null 照常写出，同样保持原样。
 */
@RegisterForReflection
public record ApiKeySnapshot(
    boolean valid,
    String reason,           // 仅 invalid 时有意义
    String apiKeyId,
    String userId,
    String tenantId,         // key 所属租户；旧 snapshot 可能为 null
    String plan,
    String role,             // RBAC 角色；旧 snapshot 可能为 null（回退 MEMBER）
    Long revokedAtEpochMs,   // null = 未撤销
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String quotaOwnerId      // 配额归属（团队 key = team owner）；个人 key / 旧 snapshot 为 null → 回退 userId
) {
    /**
     * quotaOwnerId 归一：空白或等于 userId（个人 key）时存 null，否则去首尾空白。
     * 个人 key 因此永远不写该键，push / warmup 写入 Redis 的 JSON 与升级前逐字节相同，
     * 旧 pod 与回滚 pod 仍能读个人 key 快照；只有团队 key 才带 quotaOwnerId。
     */
    public ApiKeySnapshot {
        String owner = quotaOwnerId == null ? null : quotaOwnerId.trim();
        quotaOwnerId = owner == null || owner.isEmpty() || owner.equals(userId) ? null : owner;
    }

    public static ApiKeySnapshot invalid(String reason) {
        return new ApiKeySnapshot(false, reason, null, null, null, null, null, null, null);
    }

    /**
     * cloud 下发的 key 快照条目 → 快照：webhook 推送（{@code SnapshotPushResource}）与全量同步
     * （{@code SnapshotWarmupService}）共用同一字段形状，在此单一解析，新增字段只需读一次。
     * valid 缺省视为 false；失效条目只保留 reason（缺省 {@code "invalid"}），身份字段一律丢弃。
     */
    static ApiKeySnapshot fromJson(JsonObject json) {
        if (!json.getBoolean("valid", false)) {
            return invalid(json.getString("reason", "invalid"));
        }
        return new ApiKeySnapshot(true, null,
            json.getString("apiKeyId"),
            json.getString("userId"),
            json.getString("tenantId"),
            json.getString("plan"),
            json.getString("role"),
            json.getLong("revokedAtEpochMs"),
            json.getString("quotaOwnerId"));
    }

    public boolean revoked() {
        return revokedAtEpochMs != null;
    }

    /** 实际配额归属：quotaOwnerId 缺省（个人 key / 旧 cloud / 旧 snapshot）时回退 userId（ADR 0015 §3）。 */
    public String effectiveQuotaOwnerId() {
        return quotaOwnerId == null ? userId : quotaOwnerId;
    }
}
