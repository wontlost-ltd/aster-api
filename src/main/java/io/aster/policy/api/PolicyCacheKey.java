package io.aster.policy.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Cache key for policy evaluation results
 *
 * 该类用于生成缓存键，基于策略模块、函数名、版本信息和输入参数。
 * 实现了equals和hashCode以确保缓存正确工作。
 * 包含版本信息确保热更新后不会读到旧缓存。
 */
public class PolicyCacheKey {
    private final String tenantId;
    private final String policyModule;
    private final String policyFunction;
    private final String versionId;  // 新增：策略版本标识
    /**
     * 租户结果缓存 epoch（issue #484）：失效即推进租户 epoch，旧 epoch 的键此后不会再被构造出来，
     * 不依赖逐键删除。0 表示不参与 epoch 的键（失效 / 追踪用途）。
     */
    private final long epoch;
    private final Object[] context;
    private final int hashCode;

    /**
     * 构造缓存键（带版本信息）
     */
    public PolicyCacheKey(String tenantId, String policyModule, String policyFunction, String versionId, Object[] context) {
        this(tenantId, policyModule, policyFunction, versionId, 0L, context);
    }

    /**
     * 构造带租户 epoch 的结果缓存键（评估读写 policy-results 用）。
     */
    public PolicyCacheKey(String tenantId, String policyModule, String policyFunction, String versionId,
                          long epoch, Object[] context) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.versionId = versionId;
        this.epoch = epoch;
        this.context = context;
        // 预计算哈希码以提高性能
        this.hashCode = computeHashCode();
    }

    /**
     * 构造缓存键（向后兼容，不带版本信息）
     */
    public PolicyCacheKey(String tenantId, String policyModule, String policyFunction, Object[] context) {
        this(tenantId, policyModule, policyFunction, null, context);
    }

    private int computeHashCode() {
        int result = Objects.hash(tenantId, policyModule, policyFunction, versionId, epoch);
        result = 31 * result + Arrays.deepHashCode(context);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyCacheKey that = (PolicyCacheKey) o;
        return Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(policyModule, that.policyModule) &&
               Objects.equals(policyFunction, that.policyFunction) &&
               Objects.equals(versionId, that.versionId) &&
               epoch == that.epoch &&
               Arrays.deepEquals(context, that.context);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "PolicyCacheKey{" +
                "tenantId='" + tenantId + '\'' +
                ", policyModule='" + policyModule + '\'' +
                ", policyFunction='" + policyFunction + '\'' +
                ", versionId='" + versionId + '\'' +
                ", epoch=" + epoch +
                ", contextSize=" + (context != null ? context.length : 0) +
                '}';
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPolicyModule() {
        return policyModule;
    }

    public String getPolicyFunction() {
        return policyFunction;
    }

    public String getVersionId() {
        return versionId;
    }

    /** 参与 Redis 缓存键的 JSON 编码（Quarkus Redis 缓存按 getter 序列化键），不同 epoch 落在不同的 Redis 键上。 */
    public long getEpoch() {
        return epoch;
    }

    public Object[] getContext() {
        return context;
    }
}
