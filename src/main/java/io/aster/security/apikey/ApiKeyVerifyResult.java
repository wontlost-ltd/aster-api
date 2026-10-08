package io.aster.security.apikey;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Objects;

/**
 * API key 验证结果（5 分钟 Caffeine 缓存的 value）
 *
 * - valid=true 时其余字段必填（apiKeyId, userId, tenantId, plan）
 * - valid=false 时 reason 给出原因（not_found / revoked / expired / orphan_key）
 *
 * 注意：缓存命中后 plan 字段可能略过期（最多 5 min）；
 * plan 维度的实时性由 PlanGateService 保证，此处仅作展示/审计用。
 */
@RegisterForReflection
public record ApiKeyVerifyResult(
    boolean valid,
    String reason,
    String apiKeyId,
    String userId,
    String tenantId,
    String plan,
    String subscriptionStatus,
    // RBAC 角色（owner/admin/member/viewer），由 cloud verify 按 key 所属
    // 用户在其租户内的真实角色返回。authoritative —— ApiKeyAuthFilter 用它
    // 无条件覆盖客户端传的 X-User-Role，杜绝持普通 key 自带 ADMIN 头提权。
    // 缺失/旧 snapshot 时回退到最小权限 MEMBER（fail-safe 下限）。
    String role,
    // 配额归属：团队 key 为 team owner，个人 key 等于 userId；旧 cloud 不返回时回退 userId（ADR 0015 §3）。
    String quotaOwnerId,
    // 业务角色（ADR 0042 §2.2，如 "Data Protection Officer"）：cloud verify / 快照下发的已验证集合，
    // ApiKeyAuthFilter 以它覆盖调用方自报的业务角色头；个人 key / 旧 cloud 为空列表。
    List<String> businessRoles
) {
    /** RBAC 角色缺失时的安全下限。 */
    public static final String DEFAULT_ROLE = "MEMBER";

    /** businessRoles 归一：null → 空列表；去 null/空白、去首尾空白、保序，结果不可变。 */
    public ApiKeyVerifyResult {
        businessRoles = businessRoles == null ? List.of() : businessRoles.stream()
            .filter(Objects::nonNull).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }

    public static ApiKeyVerifyResult invalid(String reason) {
        return new ApiKeyVerifyResult(false, reason, null, null, null, null, null, null, null, List.of());
    }

    public static ApiKeyVerifyResult valid(
        String apiKeyId,
        String userId,
        String tenantId,
        String plan,
        String subscriptionStatus
    ) {
        // 旧调用点：未提供 role → 回退最小权限 MEMBER。
        return valid(apiKeyId, userId, tenantId, plan, subscriptionStatus, DEFAULT_ROLE);
    }

    public static ApiKeyVerifyResult valid(
        String apiKeyId,
        String userId,
        String tenantId,
        String plan,
        String subscriptionStatus,
        String role
    ) {
        // 未提供 quotaOwnerId → 由 7 参版本回退 userId。
        return valid(apiKeyId, userId, tenantId, plan, subscriptionStatus, role, null);
    }

    public static ApiKeyVerifyResult valid(
        String apiKeyId,
        String userId,
        String tenantId,
        String plan,
        String subscriptionStatus,
        String role,
        String quotaOwnerId
    ) {
        // 未提供业务角色 → 空列表（个人 key / 旧调用点行为不变）。
        return valid(apiKeyId, userId, tenantId, plan, subscriptionStatus, role, quotaOwnerId, List.of());
    }

    public static ApiKeyVerifyResult valid(
        String apiKeyId,
        String userId,
        String tenantId,
        String plan,
        String subscriptionStatus,
        String role,
        String quotaOwnerId,
        List<String> businessRoles
    ) {
        String effectiveRole = (role == null || role.isBlank()) ? DEFAULT_ROLE : role.trim();
        String effectiveOwner = (quotaOwnerId == null || quotaOwnerId.isBlank()) ? userId : quotaOwnerId.trim();
        return new ApiKeyVerifyResult(true, null, apiKeyId, userId, tenantId, plan, subscriptionStatus,
            effectiveRole, effectiveOwner, businessRoles);
    }
}
