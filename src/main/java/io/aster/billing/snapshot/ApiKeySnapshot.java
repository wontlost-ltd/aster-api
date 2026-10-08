package io.aster.billing.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * API key snapshot —— 本地 redis 缓存
 *
 * keyHash 作为 redis key（SHA-256(明文)）；明文不存任何地方。
 *
 * <p>滚动升级期间新旧 pod 共享同一 Redis，旧 pod 用 vanilla mapper 读（未知字段即失败）。
 * 因此 quotaOwnerId 为 null 时不写该键，旧 cloud 的 key 写出的 JSON 与升级前逐字节相同；
 * 其余字段的 null 照常写出，同样保持原样。
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
    String quotaOwnerId      // 配额归属（团队 key = team owner）；旧 snapshot 可能为 null → 回退 userId
) {
    public static ApiKeySnapshot invalid(String reason) {
        return new ApiKeySnapshot(false, reason, null, null, null, null, null, null, null);
    }

    public boolean revoked() {
        return revokedAtEpochMs != null;
    }

    /** 实际配额归属：quotaOwnerId 空白（旧 cloud / 旧 snapshot）时回退 userId（ADR 0015 §3）。 */
    public String effectiveQuotaOwnerId() {
        return quotaOwnerId == null || quotaOwnerId.isBlank() ? userId : quotaOwnerId.trim();
    }
}
