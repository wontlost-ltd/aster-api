package io.aster.policy.cache;

import io.aster.policy.compiler.CompilationMetadata;

import java.time.Instant;

/**
 * 编译后的策略缓存结构
 *
 * 缓存编译后的策略，避免重复编译。
 * 包含版本信息、源码哈希、Core JSON 和元数据。
 */
public class CompiledPolicy {
    private final String versionId;
    private final String sourceHash;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final Instant compiledAt;
    /** 非空表示该版本无法执行所请求的函数（确定性失败），coreJson 为 null。 */
    private final String entryFailure;

    public CompiledPolicy(
        String versionId,
        String sourceHash,
        String coreJson,
        CompilationMetadata metadata
    ) {
        this(versionId, sourceHash, coreJson, metadata, null);
    }

    private CompiledPolicy(
        String versionId,
        String sourceHash,
        String coreJson,
        CompilationMetadata metadata,
        String entryFailure
    ) {
        this.versionId = versionId;
        this.sourceHash = sourceHash;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.compiledAt = Instant.now();
        this.entryFailure = entryFailure;
    }

    /**
     * 该版本的入口函数选择失败（函数不存在 / 被 main 抢占）。对同一 versionId 结果是确定的，
     * 缓存下来避免配置错误的部署在每个请求上都重新加载并编译。
     */
    public static CompiledPolicy entryFailure(String versionId, String sourceHash, String message) {
        return new CompiledPolicy(versionId, sourceHash, null, null, message);
    }

    /** 入口选择失败的原因；成功编译时为 null。 */
    public String getEntryFailure() {
        return entryFailure;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public String getCoreJson() {
        return coreJson;
    }

    public CompilationMetadata getMetadata() {
        return metadata;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    @Override
    public String toString() {
        return "CompiledPolicy{" +
            "versionId='" + versionId + '\'' +
            ", sourceHash='" + sourceHash + '\'' +
            ", compiledAt=" + compiledAt +
            '}';
    }
}
